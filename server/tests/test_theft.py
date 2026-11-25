import base64
import json
import secrets
import time
import uuid
from dataclasses import replace

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient

from app.main import Settings, create_app
from app.theft import AAD, Heartbeat, TheftGuard


@pytest.fixture
def guard(tmp_path, monkeypatch):
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    settings = Settings(app_secret="synthetic", verify_token="synthetic", device_token="synthetic-device-token-at-least-32-characters",
                        store_key=b"s" * 32, device_public_key=private.public_key().public_bytes(
                            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo),
                        phone_id="123", waba_id="456", database=str(tmp_path / "guard.db"), guard_payload_key=b"g" * 32,
                        guard_smtp_host="smtp.example.invalid", guard_smtp_user="synthetic", guard_smtp_password="synthetic",
                        guard_mail_from="from@example.invalid", guard_mail_to="owner@example.invalid")
    delivered = []
    monkeypatch.setattr(TheftGuard, "send_email", lambda self, kind, box: delivered.append((kind, box)))
    app = create_app(settings)
    with TestClient(app) as client:
        yield client, settings, app, private, delivered
    app.state.store.db.close()


def heartbeat(session=None, **changes):
    return dict(session_id=session or str(uuid.uuid4()), sequence=1, timestamp=int(time.time()), armed=True,
                active=False, battery=70, network="online", **changes)


def evidence(session):
    return dict(id=str(uuid.uuid4()), session_id=session, timestamp=int(time.time()), trigger="manual_test",
                location={"latitude": -6.8, "longitude": 39.2, "accuracy": 5.0, "timestamp": int(time.time())},
                images=[{"facing": "front", "jpeg": base64.b64encode(b"\xff\xd8synthetic\xff\xd9").decode()}], errors=[])


def sealed(settings, value, aad=AAD):
    nonce = secrets.token_bytes(12)
    raw = b"\x01" + nonce + AESGCM(settings.guard_payload_key).encrypt(nonce, json.dumps(value).encode(), aad)
    return {"payload": base64.b64encode(raw).decode()}


def post(guard, path, value):
    client, settings, *_ = guard
    return client.post("/device/theft/" + path, json=sealed(settings, value),
                       headers={"Authorization": "Bearer " + settings.device_token})


def test_both_routes_require_bearer_and_reject_plaintext_and_wrong_aad(guard):
    client, settings, *_ = guard
    for path in ("evidence", "heartbeat"):
        url = "/device/theft/" + path
        assert client.post(url, json={}).status_code == 401
        headers = {"Authorization": "Bearer " + settings.device_token}
        assert client.post(url, json=heartbeat(), headers=headers).status_code == 400
        assert client.post(url, json=sealed(settings, heartbeat(), b"wrong"), headers=headers).status_code == 400
        assert client.post(url, json={"payload": "invalid"}, headers=headers).status_code == 400


def test_arm_trigger_evidence_email_roundtrip_and_deduplication(guard):
    _, _, app, private, delivered = guard
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).json()["active"] is False
    value = evidence(beat["session_id"])
    assert post(guard, "evidence", value).json() == {"accepted": True, "duplicate": False, "email": "queued"}
    assert post(guard, "evidence", value).json()["duplicate"] is True
    app.state.guard.tick()
    assert len(delivered) == 1
    kind, box = delivered[0]
    assert kind == "evidence"
    stored = app.state.store.db.execute("SELECT box FROM guard_evidence").fetchone()[0]
    raw = json.loads(stored)
    key = private.decrypt(base64.b64decode(raw["key"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    actual = json.loads(AESGCM(key).decrypt(base64.b64decode(raw["nonce"]), base64.b64decode(raw["data"]), b"hellfire-relay-v1"))
    assert actual == value
    stored = app.state.store.db.execute("SELECT box FROM guard_evidence").fetchone()[0]
    assert "latitude" not in stored and "synthetic" not in stored
    app.state.guard.tick()
    assert len(delivered) == 1
    assert app.state.store.db.execute("SELECT box FROM guard_mail").fetchone()[0] is None


def test_missed_heartbeat_fires_once_recovers_and_propagates_active(guard):
    _, _, app, _, delivered = guard
    beat = heartbeat()
    now = beat["timestamp"]
    assert post(guard, "heartbeat", beat).status_code == 200
    app.state.guard.tick(now + 179)
    assert not delivered
    app.state.guard.tick(now + 180)
    app.state.guard.tick(now + 181)
    assert [m[0] for m in delivered] == ["missing"]
    beat["sequence"] += 1
    assert post(guard, "heartbeat", beat).json()["active"] is True
    # A separate real outage after a recovered check-in is eligible for one new alert.
    app.state.store.db.execute("UPDATE guard_session SET last_seen=?", (now + 10,)); app.state.store.db.commit()
    app.state.guard.tick(now + 190)
    assert [m[0] for m in delivered] == ["missing", "missing"]


def test_disarm_cancels_deadman_and_closed_sessions_cannot_be_revived(guard):
    _, _, app, _, delivered = guard
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 200
    beat.update(sequence=2, armed=False)
    assert post(guard, "heartbeat", beat).status_code == 200
    app.state.guard.tick(beat["timestamp"] + 500)
    assert not delivered
    beat.update(sequence=3, armed=True)
    assert post(guard, "heartbeat", beat).status_code == 409
    assert post(guard, "heartbeat", heartbeat()).status_code == 200


def test_stale_clock_sequence_wrong_session_and_conflicting_evidence_refused(guard):
    beat = heartbeat()
    assert post(guard, "heartbeat", {**beat, "timestamp": beat["timestamp"] - 301}).status_code == 400
    assert post(guard, "heartbeat", beat).status_code == 200
    assert post(guard, "heartbeat", beat).status_code == 409
    assert post(guard, "heartbeat", heartbeat()).status_code == 409
    assert post(guard, "evidence", evidence(str(uuid.uuid4()))).status_code == 409
    value = evidence(beat["session_id"])
    assert post(guard, "evidence", value).status_code == 200
    value["trigger"] = "sim_changed"
    assert post(guard, "evidence", value).status_code == 409


@pytest.mark.parametrize("change", [
    {"unexpected": "reject"}, {"images": [{"facing": "front", "jpeg": "bm90LWpwZWc="}]},
    {"location": {"latitude": 999, "longitude": 39, "accuracy": 5, "timestamp": 1}},
    {"errors": ["private error text should not be stored"]}, {"timestamp": int(time.time()) + 1000},
])
def test_malformed_evidence_is_refused(guard, change):
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 200
    assert post(guard, "evidence", {**evidence(beat["session_id"]), **change}).status_code == 400


def test_smtp_failure_is_durable_retried_and_not_duplicated(guard, monkeypatch):
    _, settings, app, _, delivered = guard
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 200
    assert post(guard, "evidence", evidence(beat["session_id"])).status_code == 200
    def fail(*_):
        raise OSError("synthetic SMTP unavailable")
    monkeypatch.setattr(app.state.guard, "send_email", fail)
    now = int(time.time())
    app.state.guard.tick(now)
    assert app.state.guard.smtp_error is True
    assert app.state.store.db.execute("SELECT sent FROM guard_mail").fetchone()[0] is None
    monkeypatch.setattr(app.state.guard, "send_email", lambda kind, box: delivered.append((kind, box)))
    # Restart the guard object: persisted outbox and deadlines survive process restart.
    recovered = TheftGuard(app.state.store, settings)
    recovered.tick(now + 60)
    recovered.tick(now + 61)
    assert len(delivered) == 1


def test_real_smtp_adapter_uses_tls_login_and_readable_recovery_attachment(guard, monkeypatch):
    _, _, app, *_ = guard
    import smtplib
    calls = []
    class SMTP:
        def __init__(self, host, port, timeout): calls.append((host, port, timeout))
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def login(self, user, password): calls.append((user, password))
        def send_message(self, message): calls.append(message)
    monkeypatch.setattr(smtplib, "SMTP_SSL", SMTP)
    # The fixture patches class dispatch; use the unpatched implementation saved below.
    value = evidence(str(uuid.uuid4()))
    nonce = secrets.token_bytes(12)
    encrypted = base64.b64encode(nonce + AESGCM(app.state.store.key).encrypt(nonce, json.dumps(value).encode(), b"hellfire-guard-mail-v1")).decode()
    REAL_SEND(app.state.guard, "evidence", encrypted)
    assert calls[0] == ("smtp.example.invalid", 465, 15)
    assert calls[1] == ("synthetic", "synthetic")
    message = calls[2]
    assert message["To"] == "owner@example.invalid"
    attachment = list(message.iter_attachments())[0]
    assert attachment.get_filename() == "front.jpg"
    assert attachment.get_payload(decode=True) == b"\xff\xd8synthetic\xff\xd9"
    assert "Latitude: -6.8" in message.get_body(preferencelist=("plain",)).get_content()


def test_missing_smtp_prevents_remote_arm_but_permits_disarm(guard):
    _, _, app, *_ = guard
    app.state.guard.settings = replace(app.state.guard.settings, guard_smtp_password="")
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 503
    beat["armed"] = False
    assert post(guard, "heartbeat", beat).status_code == 200


def test_new_routes_use_existing_rate_limit(guard):
    client, settings, app, *_ = guard
    original = app.state.store.rate
    app.state.store.rate = lambda *args: False
    try:
        assert post(guard, "heartbeat", heartbeat()).status_code == 429
        assert post(guard, "evidence", evidence(str(uuid.uuid4()))).status_code == 429
    finally:
        app.state.store.rate = original


REAL_SEND = TheftGuard.send_email


def test_guard_body_budget_and_ciphertext_tampering_are_rejected(guard):
    client, settings, *_ = guard
    headers = {"Authorization": "Bearer " + settings.device_token, "Content-Type": "application/json"}
    assert client.post("/device/theft/evidence", content=b"x" * (4 * 1024 * 1024 + 1), headers=headers).status_code == 413
    wrapper = sealed(settings, heartbeat())
    encrypted = bytearray(base64.b64decode(wrapper["payload"]))
    encrypted[-1] ^= 1
    wrapper["payload"] = base64.b64encode(encrypted).decode()
    assert client.post("/device/theft/heartbeat", json=wrapper, headers=headers).status_code == 400


def test_mail_outbox_encrypts_recovery_copy_and_refuses_unconfigured_transport(guard):
    client, settings, app, *_ = guard
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 200
    value = evidence(beat["session_id"])
    assert post(guard, "evidence", value).status_code == 200
    encrypted = app.state.store.db.execute("SELECT box FROM guard_mail").fetchone()[0]
    assert "latitude" not in encrypted
    raw = base64.b64decode(encrypted)
    assert json.loads(AESGCM(settings.store_key).decrypt(raw[:12], raw[12:], b"hellfire-guard-mail-v1")) == value
    # Both APIs fail closed without a separately configured guard transport key.
    app.state.guard.settings = replace(settings, guard_payload_key=b"")
    absent = create_app(replace(settings, database=settings.database + ".absent", guard_payload_key=b""))
    with TestClient(absent) as other:
        assert other.post("/device/theft/heartbeat", json={"payload": "invalid"}, headers={"Authorization": "Bearer " + settings.device_token}).status_code == 503
    absent.state.store.db.close()


def test_complete_relay_cycle_reaches_readable_smtp_message(guard, monkeypatch):
    _, _, app, *_ = guard
    import smtplib
    messages = []
    class SMTP:
        def __init__(self, *args, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def login(self, *args): pass
        def send_message(self, message): messages.append(message)
    monkeypatch.setattr(smtplib, "SMTP_SSL", SMTP)
    monkeypatch.setattr(app.state.guard, "send_email", lambda kind, box: REAL_SEND(app.state.guard, kind, box))
    beat = heartbeat()
    assert post(guard, "heartbeat", beat).status_code == 200
    beat.update(sequence=2, active=True)
    assert post(guard, "heartbeat", beat).json()["active"] is True
    value = evidence(beat["session_id"])
    assert post(guard, "evidence", value).status_code == 200
    app.state.guard.tick()
    assert len(messages) == 1
    assert "Latitude: -6.8" in messages[0].get_body(preferencelist=("plain",)).get_content()
    assert next(messages[0].iter_attachments()).get_payload(decode=True) == b"\xff\xd8synthetic\xff\xd9"
    sent, box = app.state.store.db.execute("SELECT sent,box FROM guard_mail").fetchone()
    assert sent is not None and box is None
