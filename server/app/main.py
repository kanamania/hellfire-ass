"""Single-owner WhatsApp relay. No phone data or provider credentials are logged."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from pydantic import BaseModel, ConfigDict, Field


@dataclass(frozen=True)
class Settings:
    app_secret: str = ""
    verify_token: str = ""
    device_token: str = ""
    store_key: bytes = b""
    device_public_key: bytes = b""
    graph_token: str = ""
    graph_version: str = ""
    phone_id: str = ""
    waba_id: str = ""
    database: str = "state/relay.db"

    @classmethod
    def from_env(cls):
        public = os.getenv("DEVICE_PUBLIC_KEY_FILE", "")
        return cls(
            app_secret=os.getenv("META_APP_SECRET", ""), verify_token=os.getenv("WEBHOOK_VERIFY_TOKEN", ""),
            device_token=os.getenv("DEVICE_TOKEN", ""), store_key=base64.b64decode(os.getenv("STORE_KEY_BASE64", "")),
            device_public_key=Path(public).read_bytes() if public else b"",
            graph_token=os.getenv("META_ACCESS_TOKEN", ""), graph_version=os.getenv("GRAPH_API_VERSION", ""),
            phone_id=os.getenv("META_PHONE_NUMBER_ID", ""), waba_id=os.getenv("META_WABA_ID", ""), database=os.getenv("RELAY_DATABASE", "state/relay.db"),
        )

    def ready(self):
        return (bool(self.app_secret and self.verify_token and self.device_public_key and self.phone_id and self.waba_id)
                and len(self.device_token) >= 32 and len(self.store_key) == 32)

    def outbound_ready(self):
        return bool(self.graph_token and re.fullmatch(r"v[0-9]+\.[0-9]+", self.graph_version)
                    and re.fullmatch(r"[0-9]+", self.phone_id))


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


class Store:
    def __init__(self, settings: Settings):
        self.key = settings.store_key
        self.public = serialization.load_pem_public_key(settings.device_public_key)
        if not isinstance(self.public, rsa.RSAPublicKey) or self.public.key_size < 2048:
            raise ValueError("A device RSA public key of at least 2048 bits is required")
        Path(settings.database).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(settings.database, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY, box TEXT NOT NULL, created INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS seen(id TEXT PRIMARY KEY, created INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY, payload BLOB NOT NULL, nonce_hash TEXT NOT NULL,
                created INTEGER NOT NULL, status TEXT NOT NULL, approved INTEGER, external_id TEXT, error TEXT);
            CREATE TABLE IF NOT EXISTS rate(ip TEXT NOT NULL, minute INTEGER NOT NULL, count INTEGER NOT NULL,
                PRIMARY KEY(ip, minute));
        """)
        self.db.commit()

    def rate(self, ip: str) -> bool:
        minute = int(time.time()) // 60
        with self.lock, self.db:
            self.db.execute("DELETE FROM rate WHERE minute < ?", (minute - 2,))
            self.db.execute("INSERT INTO rate VALUES(?, ?, 1) ON CONFLICT(ip,minute) DO UPDATE SET count = count + 1", (ip, minute))
            return self.db.execute("SELECT count FROM rate WHERE ip=? AND minute=?", (ip, minute)).fetchone()[0] <= 120

    def seal(self, payload: bytes) -> str:
        key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
        wrapped = self.public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
        return json.dumps({"version": 1, "key": b64(wrapped), "nonce": b64(nonce),
                           "data": b64(AESGCM(key).encrypt(nonce, payload, b"hellfire-relay-v1"))}, separators=(",", ":"))

    def ingest(self, events: list[tuple[str, dict]]) -> int:
        inserted = 0
        now = int(time.time())
        with self.lock, self.db:
            for identity, event in events:
                if self.db.execute("INSERT OR IGNORE INTO seen VALUES(?,?)", (identity, now)).rowcount:
                    self.db.execute("INSERT INTO inbox VALUES(?,?,?)", (identity, self.seal(json.dumps(event).encode()), now))
                    inserted += 1
            # Encrypted backlog has bounded retention. Seen fingerprints outlive acknowledgments.
            self.db.execute("DELETE FROM inbox WHERE created < ?", (now - 7 * 86400,))
            self.db.execute("DELETE FROM seen WHERE created < ?", (now - 30 * 86400,))
            self.db.execute("DELETE FROM drafts WHERE created < ?", (now - 86400,))
        return inserted


def normalize_webhook(payload: dict, waba_id: str = "", phone_id: str = "") -> list[tuple[str, dict]]:
    if payload.get("object") != "whatsapp_business_account":
        raise ValueError("Unexpected webhook object")
    result = []
    for entry in payload.get("entry", []):
        if waba_id and str(entry.get("id", "")) != waba_id:
            continue
        for change in entry.get("changes", []):
            value = change.get("value", {})
            if phone_id and value.get("metadata", {}).get("phone_number_id", phone_id) != phone_id:
                continue
            field = change.get("field", "unknown")
            # Preserve unknown Coexistence event fields without fabricating supported features.
            for collection in ("messages", "statuses", "message_echoes"):
                for item in value.get(collection, []):
                    event = {"schema_version": 1, "field": field, "collection": collection,
                             "entry_id": str(entry.get("id", "")), "metadata": value.get("metadata", {}), "item": item}
                    canonical = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    identity = hashlib.sha256(canonical.encode()).hexdigest()
                    result.append((identity, event))
            if not any(value.get(key) for key in ("messages", "statuses", "message_echoes")):
                event = {"schema_version": 1, "field": field, "collection": "unmapped", "value": value,
                         "entry_id": str(entry.get("id", ""))}
                result.append((hashlib.sha256(json.dumps(event, sort_keys=True).encode()).hexdigest(), event))
    if len(result) > 500:
        raise ValueError("Webhook batch too large")
    return result


class Draft(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    to: str = Field(pattern=r"^[0-9]{6,20}$")
    text: str = Field(min_length=1, max_length=4096)


class Approval(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    nonce: str = Field(min_length=32, max_length=256)


def create_app(settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    app = FastAPI(title="HellFire Ass relay", docs_url=None, redoc_url=None)
    store = Store(settings) if settings.ready() else None
    app.state.store = store

    @app.middleware("http")
    async def limit(request: Request, call_next):
        if store and not store.rate(request.client.host if request.client else "unknown"):
            return JSONResponse({"detail": "Rate limit exceeded"}, status_code=429)
        return await call_next(request)

    def authorized(authorization: str | None):
        if not store:
            raise HTTPException(503, "Gateway not configured")
        if not hmac.compare_digest(authorization or "", "Bearer " + settings.device_token):
            raise HTTPException(401, "Device authorization required")

    @app.get("/health")
    async def health():
        return {"service": "HellFire Ass", "configured": store is not None,
                "outbound_configured": settings.outbound_ready(), "coexistence": "ACCOUNT_VERIFICATION_REQUIRED"}

    @app.get("/webhook")
    async def verify(request: Request):
        q = request.query_params
        if (not store or q.get("hub.mode") != "subscribe"
                or not hmac.compare_digest(q.get("hub.verify_token", ""), settings.verify_token)):
            raise HTTPException(403, "Verification rejected")
        challenge = q.get("hub.challenge", "")
        if not challenge or len(challenge) > 512:
            raise HTTPException(400, "Invalid challenge")
        return PlainTextResponse(challenge)

    @app.post("/webhook")
    async def webhook(request: Request, x_hub_signature_256: str | None = Header(default=None)):
        if not store:
            raise HTTPException(503, "Gateway not configured")
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 1024 * 1024:
                raise HTTPException(413, "Webhook too large")
        expected = "sha256=" + hmac.new(settings.app_secret.encode(), body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(x_hub_signature_256 or "", expected):
            raise HTTPException(401, "Invalid signature")
        try:
            events = normalize_webhook(json.loads(body), settings.waba_id, settings.phone_id)
        except (ValueError, TypeError, AttributeError, KeyError):
            raise HTTPException(400, "Invalid webhook structure") from None
        return {"accepted": store.ingest(events)}

    @app.get("/device/events")
    async def events(authorization: str | None = Header(default=None)):
        authorized(authorization)
        with store.lock:
            rows = store.db.execute("SELECT id, box, created FROM inbox ORDER BY created,id LIMIT 100").fetchall()
        return {"events": [{"id": r[0], "box": json.loads(r[1]), "created": r[2]} for r in rows]}

    @app.post("/device/events/{identity}/ack")
    async def ack(identity: str, authorization: str | None = Header(default=None)):
        authorized(authorization)
        with store.lock, store.db:
            store.db.execute("DELETE FROM inbox WHERE id=?", (identity,))
        return {"acknowledged": True}

    @app.post("/drafts")
    async def draft(value: Draft, authorization: str | None = Header(default=None)):
        authorized(authorization)
        identity, nonce = secrets.token_hex(16), secrets.token_urlsafe(32)
        iv = secrets.token_bytes(12)
        payload = iv + AESGCM(store.key).encrypt(iv, value.model_dump_json().encode(), identity.encode())
        with store.lock, store.db:
            store.db.execute("INSERT INTO drafts(id,payload,nonce_hash,created,status) VALUES(?,?,?,?,?)",
                             (identity, payload, hashlib.sha256(nonce.encode()).hexdigest(), int(time.time()), "DRAFT"))
        return {"id": identity, "nonce": nonce, "status": "DRAFT"}

    @app.post("/drafts/{identity}/approve")
    async def approve(identity: str, value: Approval, authorization: str | None = Header(default=None)):
        authorized(authorization)
        with store.lock, store.db:
            changed = store.db.execute("UPDATE drafts SET status='APPROVED', approved=? WHERE id=? AND nonce_hash=? AND status='DRAFT' AND created>=?",
                (int(time.time()), identity, hashlib.sha256(value.nonce.encode()).hexdigest(), int(time.time()) - 300)).rowcount
        if not changed:
            raise HTTPException(409, "Approval invalid, expired or already used")
        return {"status": "APPROVED"}

    @app.post("/drafts/{identity}/send")
    async def send(identity: str, value: Approval, authorization: str | None = Header(default=None)):
        authorized(authorization)
        if not settings.outbound_ready():
            raise HTTPException(503, "Graph version, phone ID and access token must be configured")
        with store.lock, store.db:
            row = store.db.execute("SELECT payload FROM drafts WHERE id=? AND nonce_hash=? AND status='APPROVED' AND approved>=?",
                (identity, hashlib.sha256(value.nonce.encode()).hexdigest(), int(time.time()) - 300)).fetchone()
            if not row:
                raise HTTPException(409, "Fresh explicit approval required; automatic retries are prohibited")
            store.db.execute("UPDATE drafts SET status='SENDING' WHERE id=?", (identity,))
        try:
            iv, encrypted = row[0][:12], row[0][12:]
            value = json.loads(AESGCM(store.key).decrypt(iv, encrypted, identity.encode()))
            async with httpx.AsyncClient(transport=transport, timeout=20, follow_redirects=False) as client:
                response = await client.post(f"https://graph.facebook.com/{settings.graph_version}/{settings.phone_id}/messages",
                    headers={"Authorization": "Bearer " + settings.graph_token},
                    json={"messaging_product": "whatsapp", "recipient_type": "individual", "to": value["to"],
                          "type": "text", "text": {"preview_url": False, "body": value["text"]}})
            response.raise_for_status()
            external = response.json()["messages"][0]["id"]
        except Exception:
            with store.lock, store.db:
                store.db.execute("UPDATE drafts SET status='UNKNOWN', error='DELIVERY_UNCONFIRMED' WHERE id=?", (identity,))
            raise HTTPException(502, "Delivery unconfirmed. Check channel status before creating another send.") from None
        with store.lock, store.db:
            store.db.execute("UPDATE drafts SET status='SENT', external_id=? WHERE id=?", (external, identity))
        return {"status": "SENT", "message_id": external}

    return app


app = create_app(Settings.from_env())
