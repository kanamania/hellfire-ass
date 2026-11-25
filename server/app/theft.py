"""Single-owner guard transport and durable dead-man/SMTP outbox. No sensor plaintext on disk."""
from __future__ import annotations

import base64
import json
import secrets
import smtplib
import time
from email.message import EmailMessage
from typing import Literal

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

AAD = b"hellfire-guard-v1"
MAX_BODY = 4 * 1024 * 1024


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Heartbeat(Strict):
    session_id: str = Field(pattern=r"^[a-f0-9-]{36}$")
    sequence: int = Field(ge=1)
    timestamp: int = Field(ge=0)
    armed: bool
    active: bool
    battery: int = Field(ge=-1, le=100)
    network: Literal["online", "offline"]


class Fix(Strict):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy: float = Field(ge=0, le=100000)
    timestamp: int = Field(ge=0)


class Image(Strict):
    facing: Literal["front", "back"]
    jpeg: str = Field(min_length=4, max_length=1400000)


class Evidence(Strict):
    id: str = Field(pattern=r"^[a-f0-9-]{36}$")
    session_id: str = Field(pattern=r"^[a-f0-9-]{36}$")
    timestamp: int = Field(ge=0)
    trigger: Literal["manual_test", "unlock_failures", "sim_changed", "connectivity_lost", "dead_man"]
    location: Fix | None
    images: list[Image] = Field(max_length=2)
    errors: list[str] = Field(max_length=4)


async def open_payload(request: Request, settings, model):
    if len(settings.guard_payload_key) != 32:
        raise HTTPException(503, "Guard transport key not configured")
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > MAX_BODY:
            raise HTTPException(413, "Guard payload too large")
    try:
        wrapper = json.loads(body)
        if not isinstance(wrapper, dict) or set(wrapper) != {"payload"}:
            raise ValueError()
        encrypted = base64.b64decode(wrapper["payload"], validate=True)
        if len(encrypted) < 29 or encrypted[0] != 1:
            raise ValueError()
        plaintext = AESGCM(settings.guard_payload_key).decrypt(encrypted[1:13], encrypted[13:], AAD)
        return model.model_validate_json(plaintext)
    except Exception:
        # No exception text: validation errors can include personal sensor data.
        raise HTTPException(400, "Invalid encrypted guard payload") from None


class TheftGuard:
    def __init__(self, store, settings):
        self.store, self.settings = store, settings
        self.smtp_error = False
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS guard_session(
                    id TEXT PRIMARY KEY, sequence INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    armed INTEGER NOT NULL, active INTEGER NOT NULL, missed INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS guard_evidence(
                    id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL,
                    digest TEXT NOT NULL, box TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS guard_mail(
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL, box TEXT, created INTEGER NOT NULL,
                    sent INTEGER, next_attempt INTEGER NOT NULL);
            """)

    def smtp_ready(self):
        s = self.settings
        return bool(s.guard_smtp_host and s.guard_smtp_user and s.guard_smtp_password
                    and s.guard_mail_from and s.guard_mail_to)

    def heartbeat(self, value: Heartbeat, now: int | None = None):
        now = int(time.time()) if now is None else now
        if abs(now - value.timestamp) > 300:
            raise HTTPException(400, "Heartbeat clock outside five-minute tolerance")
        if value.armed and not self.smtp_ready():
            raise HTTPException(503, "Guard SMTP not configured")
        s = self.store
        with s.lock, s.db:
            row = s.db.execute("SELECT sequence,armed,active FROM guard_session WHERE id=?", (value.session_id,)).fetchone()
            if row:
                if value.sequence <= row[0]:
                    raise HTTPException(409, "Stale heartbeat sequence")
                if not row[1] and value.armed:
                    raise HTTPException(409, "Disarmed session cannot be revived")
                active = bool(value.active or row[2]) and value.armed
                s.db.execute("UPDATE guard_session SET sequence=?,last_seen=?,armed=?,active=?,missed=0 WHERE id=?",
                             (value.sequence, now, value.armed, active, value.session_id))
            else:
                if value.armed and s.db.execute("SELECT 1 FROM guard_session WHERE armed=1").fetchone():
                    raise HTTPException(409, "Another session is still armed")
                active = value.active and value.armed
                s.db.execute("INSERT INTO guard_session VALUES(?,?,?,?,?,0)",
                             (value.session_id, value.sequence, now, value.armed, active))
        return {"accepted": True, "active": active, "smtp_configured": self.smtp_ready()}

    def evidence(self, value: Evidence, now: int | None = None):
        import hashlib
        now = int(time.time()) if now is None else now
        if value.timestamp > now + 60 or value.timestamp < now - 7 * 86400:
            raise HTTPException(400, "Evidence timestamp outside retention window")
        facings = [image.facing for image in value.images]
        if len(facings) != len(set(facings)):
            raise HTTPException(400, "Duplicate camera facing")
        for image in value.images:
            try:
                jpeg = base64.b64decode(image.jpeg, validate=True)
                if len(jpeg) > 1024 * 1024 or not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9"):
                    raise ValueError()
            except ValueError:
                raise HTTPException(400, "Invalid JPEG attachment") from None
        if any(len(error) > 80 or not error.replace("_", "").isalnum() for error in value.errors):
            raise HTTPException(400, "Invalid capture status")
        payload = value.model_dump_json().encode()
        digest = hashlib.sha256(payload).hexdigest()
        s = self.store
        with s.lock, s.db:
            old = s.db.execute("SELECT digest FROM guard_evidence WHERE id=?", (value.id,)).fetchone()
            if old:
                if old[0] != digest:
                    raise HTTPException(409, "Evidence identity already used")
                return {"accepted": True, "duplicate": True, "email": "queued"}
            session = s.db.execute("SELECT armed FROM guard_session WHERE id=?", (value.session_id,)).fetchone()
            if not session or not session[0]:
                raise HTTPException(409, "Armed heartbeat required before evidence")
            retained = s.db.execute("SELECT COALESCE(SUM(length(box)),0) FROM guard_evidence").fetchone()[0]
            if retained + len(payload) * 2 > 256 * 1024 * 1024:
                raise HTTPException(507, "Guard encrypted evidence storage full")
            box = s.seal(payload)
            s.db.execute("INSERT INTO guard_evidence VALUES(?,?,?,?,?)", (value.id, value.session_id, now, digest, box))
            # Recovery mail must be usable without the stolen phone's private key.
            nonce = secrets.token_bytes(12)
            mail = base64.b64encode(nonce + AESGCM(s.key).encrypt(nonce, payload, b"hellfire-guard-mail-v1")).decode()
            s.db.execute("INSERT INTO guard_mail VALUES(?,?,?,?,NULL,?)", ("evidence:" + value.id, "evidence", mail, now, now))
        return {"accepted": True, "duplicate": False, "email": "queued"}

    def tick(self, now: int | None = None):
        now = int(time.time()) if now is None else now
        s = self.store
        with s.lock, s.db:
            rows = s.db.execute("SELECT id,last_seen FROM guard_session WHERE armed=1 AND missed=0 AND last_seen<=?",
                                (now - self.settings.guard_timeout_seconds,)).fetchall()
            for session_id, last_seen in rows:
                identity = f"missing:{session_id}:{last_seen}"
                s.db.execute("INSERT OR IGNORE INTO guard_mail VALUES(?,?,NULL,?,NULL,?)", (identity, "missing", now, now))
                s.db.execute("UPDATE guard_session SET missed=1,active=1 WHERE id=?", (session_id,))
            # Retain encrypted evidence for seven days; unsent mail also expires after seven days.
            s.db.execute("DELETE FROM guard_evidence WHERE created < ?", (now - 7 * 86400,))
            s.db.execute("DELETE FROM guard_mail WHERE created < ?", (now - 7 * 86400,))
            mail = s.db.execute("SELECT id,kind,box FROM guard_mail WHERE sent IS NULL AND next_attempt<=? LIMIT 3", (now,)).fetchall()
        for identity, kind, box in mail:
            try:
                self.send_email(kind, box)
            except Exception:
                self.smtp_error = True
                with s.lock, s.db:
                    s.db.execute("UPDATE guard_mail SET next_attempt=? WHERE id=?", (now + 60, identity))
            else:
                self.smtp_error = False
                with s.lock, s.db:
                    s.db.execute("UPDATE guard_mail SET sent=?,box=NULL WHERE id=?", (now, identity))

    def send_email(self, kind: str, box: str | None):
        s = self.settings
        if not self.smtp_ready():
            raise RuntimeError("Guard SMTP not configured")
        message = EmailMessage()
        message["From"], message["To"] = s.guard_mail_from, s.guard_mail_to
        message["Subject"] = "HellFire TheftGuard: " + ("new evidence" if kind == "evidence" else "heartbeat missing")
        if box:
            envelope = base64.b64decode(box, validate=True)
            payload = json.loads(AESGCM(self.store.key).decrypt(envelope[:12], envelope[12:], b"hellfire-guard-mail-v1"))
            fix = payload.get("location")
            location = (f"Latitude: {fix['latitude']}; longitude: {fix['longitude']}; accuracy: {fix['accuracy']} m"
                        if fix else "Location unavailable")
            message.set_content(f"TheftGuard triggered: {payload['trigger']}\n{location}\n"
                                f"Captured at Unix time {payload['timestamp']}.\n"
                                "These are sensor observations, not proof of theft or identity. Photos are attached when available.")
            for image in payload["images"]:
                message.add_attachment(base64.b64decode(image["jpeg"], validate=True), maintype="image", subtype="jpeg",
                                       filename=image["facing"] + ".jpg")
        else:
            message.set_content("The armed phone stopped checking in. Power loss, network loss, OS restrictions or theft may cause this. This is not proof of theft.")
        # Implicit TLS only. SMTP credentials and sensor plaintext are never logged.
        with smtplib.SMTP_SSL(s.guard_smtp_host, s.guard_smtp_port, timeout=15) as smtp:
            smtp.login(s.guard_smtp_user, s.guard_smtp_password)
            smtp.send_message(message)
