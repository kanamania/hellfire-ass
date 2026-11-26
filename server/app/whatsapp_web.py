"""Consume the linked-device worker's encrypted spool; no browser control API."""
import base64
import binascii
import hashlib
import json
import re
import time
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_FILE_BYTES = 192 * 1024
STATES = {"STARTING", "PAIRING_REQUIRED", "SYNCING", "CONNECTED", "AUTH_FAILED", "DISCONNECTED", "FAILED", "STOPPED"}
ERRORS = {"QUEUE_FULL", "CAPTURE_FAILED", "SPOOL_UNAVAILABLE", "QR_RENDER_FAILED", "PAIR_AGAIN",
          "RESTART_WORKER_OR_PAIR_AGAIN", "BROWSER_START_FAILED"}


def read_json(path: Path, limit: int):
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Worker file too large")
    return json.loads(data)


def capture_status(directory: str, pairing: bool = False) -> dict:
    status = {"state": "WORKER_UNAVAILABLE", "capture_only": True, "error": None}
    try:
        value = read_json(Path(directory) / "status.json", 64 * 1024)
        updated = value["updated_at"]
        if type(updated) is not int or not 0 <= time.time() - updated <= 90:
            return status
        state = value["state"]
        if state not in STATES:
            return status
        status.update(state=state, updated_at=updated)
        if value.get("error"):
            status["error"] = value["error"] if value["error"] in ERRORS else "CAPTURE_REQUIRES_ATTENTION"
        if pairing and state == "PAIRING_REQUIRED":
            qr = value.get("qr")
            expires = value.get("qr_expires_at", 0)
            if (type(expires) is int and time.time() < expires <= updated + 60
                    and isinstance(qr, str) and re.fullmatch(r"data:image/png;base64,[A-Za-z0-9+/=]+", qr)):
                status["qr"] = qr
    except (OSError, ValueError, KeyError, TypeError):
        pass
    rejected = rejected_count(directory)
    if rejected:
        status["rejected"] = rejected
        status["error"] = status["error"] or "SPOOL_EVENTS_REJECTED"
    return status


def rejected_count(directory: str) -> int:
    try:
        return sum(1 for _ in (Path(directory) / "rejected").glob("*.json"))
    except OSError:
        return 0


def quarantine(path: Path) -> None:
    """Keep an unreadable event for inspection without letting it block every later event."""
    target = path.parent.parent / "rejected"
    target.mkdir(mode=0o700, exist_ok=True)
    path.replace(target / path.name)


def drain_spool(directory: str, store) -> bool:
    outbox = Path(directory) / "outbox"
    if not outbox.exists():
        return False
    # Bounded batch; the worker bounds the spool to 10,000 events and seven days.
    paths = sorted(path for path in outbox.glob("*.json") if re.fullmatch(r"[a-f0-9]{64}", path.stem))
    for path in paths[:100]:
        identity = path.stem
        if not re.fullmatch(r"[a-f0-9]{64}", identity):
            continue
        try:
            box = read_json(path, MAX_FILE_BYTES)
            if box["version"] != 1:
                raise ValueError("Unknown spool version")
            nonce = base64.b64decode(box["nonce"], validate=True)
            data = base64.b64decode(box["data"], validate=True)
            if len(nonce) != 12:
                raise ValueError("Invalid nonce")
        except FileNotFoundError:
            continue
        except (ValueError, KeyError, TypeError, binascii.Error):
            quarantine(path)
            continue
        try:
            event = json.loads(AESGCM(store.key).decrypt(nonce, data, f"hellfire-web-spool-v1:{identity}".encode()))
            item = event["item"]
            expected = hashlib.sha256(f"whatsapp-web:message:{item['id']}".encode()).hexdigest()
            if (expected != identity or event.get("schema_version") != 1 or event.get("transport") != "whatsapp_web"
                    or event.get("collection") not in {"messages", "message_echoes"}
                    or not isinstance(item.get("text", {}).get("body"), str)
                    or not re.fullmatch(r"[0-9]{1,12}", item.get("timestamp", ""))):
                raise ValueError("Invalid linked-device event")
        except (ValueError, KeyError, TypeError, AttributeError, InvalidTag):
            quarantine(path)
            continue
        try:
            store.ingest([(identity, event)])
            # Commit the phone-encrypted envelope first. Replay after a crash is harmless.
            path.unlink(missing_ok=True)
        except FileNotFoundError:
            continue
    return len(paths) > 100
