import base64
import hashlib
import hmac
import json
import logging
import sqlite3
from dataclasses import replace

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient

from app.main import Settings, create_app
from app.logging import SafeAccessFilter


@pytest.fixture
def gateway(tmp_path):
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    settings = Settings(app_secret="synthetic-signing-key", verify_token="synthetic-verify-token",
        device_token="synthetic-device-token-at-least-32-characters", store_key=b"x" * 32,
        device_public_key=private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo),
        graph_token="synthetic-meta-token", graph_version="v1.0", phone_id="12345", waba_id="67890", database=str(tmp_path / "relay.db"))
    sends = []
    def mock(request):
        sends.append(json.loads(request.content))
        return httpx.Response(200, json={"messages": [{"id": "synthetic-outbound-id"}]})
    app = create_app(settings, httpx.MockTransport(mock))
    with TestClient(app) as client:
        yield client, settings, private, sends, app
    app.state.store.db.close()


def auth(settings):
    return {"Authorization": "Bearer " + settings.device_token}


def payload(status=None, waba="67890", phone="12345"):
    value = {"metadata": {"phone_number_id": phone}}
    if status:
        value["statuses"] = [{"id": "synthetic-message", "status": status, "timestamp": "1"}]
    else:
        value["messages"] = [{"id": "synthetic-message", "from": "255700000000", "type": "text", "timestamp": "1", "text": {"body": "Synthetic webhook text"}}]
    return {"object": "whatsapp_business_account", "entry": [{"id": waba, "changes": [{"field": "messages", "value": value}]}]}


def post(client, settings, value):
    raw = json.dumps(value).encode()
    signature = "sha256=" + hmac.new(settings.app_secret.encode(), raw, hashlib.sha256).hexdigest()
    return client.post("/webhook", content=raw, headers={"X-Hub-Signature-256": signature})


def test_verification_and_signature_rejection(gateway):
    client, settings, _, _, _ = gateway
    assert client.get("/webhook", params={"hub.mode": "subscribe", "hub.verify_token": settings.verify_token, "hub.challenge": "fixture"}).text == "fixture"
    assert client.get("/webhook", params={"hub.mode": "subscribe", "hub.verify_token": "wrong", "hub.challenge": "fixture"}).status_code == 403
    assert client.post("/webhook", json=payload(), headers={"X-Hub-Signature-256": "sha256=wrong"}).status_code == 401


def test_duplicate_delivery_encryption_and_ack_replay(gateway):
    client, settings, private, _, app = gateway
    assert post(client, settings, payload()).json()["accepted"] == 1
    assert post(client, settings, payload()).json()["accepted"] == 0
    assert client.get("/device/events").status_code == 401
    event = client.get("/device/events", headers=auth(settings)).json()["events"][0]
    box = event["box"]
    key = private.decrypt(base64.b64decode(box["key"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    plain = AESGCM(key).decrypt(base64.b64decode(box["nonce"]), base64.b64decode(box["data"]), b"hellfire-relay-v1")
    assert json.loads(plain)["item"]["text"]["body"] == "Synthetic webhook text"
    stored = app.state.store.db.execute("SELECT box FROM inbox").fetchone()[0]
    assert "Synthetic webhook text" not in stored
    assert client.post(f"/device/events/{event['id']}/ack", headers=auth(settings)).status_code == 200
    assert post(client, settings, payload()).json()["accepted"] == 0
    assert not client.get("/device/events", headers=auth(settings)).json()["events"]


def test_out_of_order_statuses_and_wrong_business_are_not_lost_or_crossed(gateway):
    client, settings, _, _, _ = gateway
    assert post(client, settings, payload("read")).json()["accepted"] == 1
    assert post(client, settings, payload("sent")).json()["accepted"] == 1
    assert post(client, settings, payload(waba="other-business")).json()["accepted"] == 0
    assert post(client, settings, payload(phone="99999")).json()["accepted"] == 0
    assert len(client.get("/device/events", headers=auth(settings)).json()["events"]) == 2


def test_send_requires_fresh_owner_approval_and_is_not_replayed(gateway):
    client, settings, _, sends, _ = gateway
    draft = client.post("/drafts", headers=auth(settings), json={"to": "255700000000", "text": "Synthetic approved reply"}).json()
    approval = {"nonce": draft["nonce"]}
    assert client.post(f"/drafts/{draft['id']}/send", headers=auth(settings), json=approval).status_code == 409
    assert client.post(f"/drafts/{draft['id']}/approve", headers=auth(settings), json=approval).status_code == 200
    assert client.post(f"/drafts/{draft['id']}/send", headers=auth(settings), json=approval).json()["status"] == "SENT"
    assert client.post(f"/drafts/{draft['id']}/send", headers=auth(settings), json=approval).status_code == 409
    assert len(sends) == 1


def test_expired_nonce_and_unknown_delivery_cannot_auto_retry(gateway):
    client, settings, _, _, app = gateway
    draft = client.post("/drafts", headers=auth(settings), json={"to": "255700000000", "text": "Synthetic"}).json()
    app.state.store.db.execute("UPDATE drafts SET created=1 WHERE id=?", (draft["id"],)); app.state.store.db.commit()
    assert client.post(f"/drafts/{draft['id']}/approve", headers=auth(settings), json={"nonce": draft["nonce"]}).status_code == 409
    bad_app = create_app(replace(settings, database=settings.database + ".bad"), httpx.MockTransport(lambda r: httpx.Response(500)))
    with TestClient(bad_app) as bad:
        draft = bad.post("/drafts", headers=auth(settings), json={"to": "255700000000", "text": "Synthetic"}).json()
        approval = {"nonce": draft["nonce"]}
        bad.post(f"/drafts/{draft['id']}/approve", headers=auth(settings), json=approval)
        assert bad.post(f"/drafts/{draft['id']}/send", headers=auth(settings), json=approval).status_code == 502
        assert bad.post(f"/drafts/{draft['id']}/send", headers=auth(settings), json=approval).status_code == 409
    bad_app.state.store.db.close()


def test_input_bounds_unconfigured_gateway_and_rate_limits(gateway):
    client, settings, _, _, _ = gateway
    assert client.post("/drafts", headers=auth(settings), json={"to": "invalid", "text": "Synthetic"}).status_code == 422
    with TestClient(create_app(Settings())) as empty:
        assert not empty.get("/health").json()["configured"]
        assert empty.get("/device/events", headers=auth(settings)).status_code == 503
    results = [client.get("/health").status_code for _ in range(125)]
    assert 429 in results


def test_access_logging_redacts_verification_query():
    record = logging.LogRecord("uvicorn.access", logging.INFO, "", 1, '%s %s %s %s %s',
        ("fixture", "GET", "/webhook?hub.verify_token=synthetic-secret", "HTTP/1.1", 200), None)
    assert SafeAccessFilter().filter(record)
    assert "synthetic-secret" not in record.getMessage()
