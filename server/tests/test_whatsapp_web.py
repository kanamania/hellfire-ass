import base64
import hashlib
import json
import shutil
import subprocess
import time
from dataclasses import replace
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient

from app.main import create_app
from test_gateway import auth, gateway


@pytest.fixture
def web_gateway(gateway, tmp_path):
    _, original, private, sends, _ = gateway
    directory = tmp_path / "web"
    (directory / "outbox").mkdir(parents=True)
    settings = replace(original, whatsapp_transport="web", web_state_directory=str(directory),
                       database=str(tmp_path / "web.db"), app_secret="", verify_token="", waba_id="", phone_id="")
    app = create_app(settings)
    with TestClient(app) as client:
        yield client, settings, private, directory, app
    app.state.store.db.close()


def spool(directory, key, identity="synthetic-message", **overrides):
    event = {"schema_version": 1, "transport": "whatsapp_web", "collection": "messages", "field": "message_create",
             "item": {"id": identity, "timestamp": "1794571200", "from": "contact@c.us", "to": "owner@c.us",
                      "thread_id": "contact@c.us", "text": {"body": "Synthetic linked-device text"}}}
    event.update(overrides)
    identity = hashlib.sha256(f"whatsapp-web:message:{identity}".encode()).hexdigest()
    nonce = b"0" * 12
    data = AESGCM(key).encrypt(nonce, json.dumps(event).encode(), f"hellfire-web-spool-v1:{identity}".encode())
    path = directory / "outbox" / f"{identity}.json"
    path.write_text(json.dumps({"version": 1, "nonce": base64.b64encode(nonce).decode(), "data": base64.b64encode(data).decode()}))
    return identity, path


def test_web_mode_requires_no_meta_credentials_but_never_enables_sends(web_gateway):
    client, settings, _, _, _ = web_gateway
    assert settings.ready()
    assert not settings.outbound_ready()
    assert client.get("/health").json()["transport"] == "web"
    assert client.post("/drafts", headers=auth(settings), json={"to": "255700000000", "text": "Synthetic"}).status_code == 409
    assert client.post("/drafts/synthetic/send", headers=auth(settings), json={"nonce": "x" * 32}).status_code == 503
    assert client.post("/webhook", json={}).status_code == 409


def test_pairing_and_status_require_authentication_and_never_cache_qr(web_gateway):
    client, settings, _, directory, _ = web_gateway
    now = int(time.time())
    qr = "data:image/png;base64,c3ludGhldGlj"
    (directory / "status.json").write_text(json.dumps({"state": "PAIRING_REQUIRED", "updated_at": now,
                                                       "qr": qr, "qr_expires_at": now + 45}))
    for route in ("status", "pairing"):
        assert client.get(f"/device/whatsapp-web/{route}").status_code == 401
    response = client.get("/device/whatsapp-web/pairing", headers=auth(settings))
    assert response.json()["qr"] == qr
    assert response.headers["cache-control"] == "no-store"
    assert "qr" not in client.get("/device/whatsapp-web/status", headers=auth(settings)).json()
    page = client.get("/pair")
    assert qr not in page.text and settings.device_token not in page.text
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]


@pytest.mark.parametrize("state,age,qr_age", [("CONNECTED", 0, 45), ("PAIRING_REQUIRED", 100, 45), ("PAIRING_REQUIRED", 0, -1)])
def test_stale_worker_or_expired_code_never_returns_qr(web_gateway, state, age, qr_age):
    client, settings, _, directory, _ = web_gateway
    now = int(time.time())
    (directory / "status.json").write_text(json.dumps({"state": state, "updated_at": now - age,
        "qr": "data:image/png;base64,c3ludGhldGlj", "qr_expires_at": now + qr_age}))
    value = client.get("/device/whatsapp-web/pairing", headers=auth(settings)).json()
    assert "qr" not in value
    if age:
        assert value["state"] == "WORKER_UNAVAILABLE"


def test_web_spool_replay_is_encrypted_durable_and_acknowledged_after_commit(web_gateway):
    client, settings, _, directory, app = web_gateway
    identity, path = spool(directory, settings.store_key)
    first = client.get("/device/events", headers=auth(settings)).json()
    assert first["events"][0]["id"] == identity
    assert first["capture_status"]["state"] == "WORKER_UNAVAILABLE"
    assert not path.exists()
    assert "Synthetic linked-device text" not in json.dumps(first)
    assert client.get("/device/events", headers=auth(settings)).json()["events"] == first["events"]
    spool(directory, settings.store_key)
    assert len(client.get("/device/events", headers=auth(settings)).json()["events"]) == 1
    client.post(f"/device/events/{identity}/ack", headers=auth(settings))
    spool(directory, settings.store_key)
    assert not client.get("/device/events", headers=auth(settings)).json()["events"]
    assert app.state.store.db.execute("SELECT COUNT(*) FROM seen").fetchone()[0] == 1


@pytest.mark.parametrize("fault", ["wrong-key", "wrong-transport", "oversized", "malformed"])
def test_invalid_spool_is_not_deleted_or_accepted(web_gateway, fault):
    client, settings, _, directory, _ = web_gateway
    _, path = spool(directory, b"z" * 32 if fault == "wrong-key" else settings.store_key,
                    **({"transport": "wrong"} if fault == "wrong-transport" else {}))
    if fault == "oversized":
        path.write_bytes(b"x" * (192 * 1024 + 1))
    if fault == "malformed":
        path.write_text("[]")
    assert client.get("/device/events", headers=auth(settings)).status_code == 503
    assert path.exists()


def test_failed_relay_commit_leaves_spool_for_recovery(web_gateway, monkeypatch):
    client, settings, _, directory, app = web_gateway
    _, path = spool(directory, settings.store_key)
    def fail(_):
        raise OSError("synthetic unavailable storage")
    monkeypatch.setattr(app.state.store, "ingest", fail)
    assert client.get("/device/events", headers=auth(settings)).status_code == 503
    assert path.exists()


def test_large_batches_fit_android_limit_and_signal_follow_up(web_gateway):
    client, settings, _, directory, _ = web_gateway
    for index in range(101):
        spool(directory, settings.store_key, identity=f"synthetic-{index}", item={
            "id": f"synthetic-{index}", "timestamp": "1794571200", "text": {"body": "a" * 16000}})
    response = client.get("/device/events", headers=auth(settings))
    value = response.json()
    assert len(response.content) < 2 * 1024 * 1024
    assert 0 < len(value["events"]) < 100
    assert value["has_more"]
    assert len(list((directory / "outbox").glob("*.json"))) == 1


@pytest.mark.skipif(not shutil.which("node"), reason="Node.js required for cross-language spool test")
def test_real_node_spool_is_consumed_by_python(web_gateway):
    client, settings, private, directory, _ = web_gateway
    module = Path(__file__).parents[1] / "whatsapp-web/src/capture.js"
    # Dynamic import accepts the module path as data; no shell or real account is used.
    script = """const {CaptureSpool} = await import(process.argv[1]);
      new CaptureSpool(process.argv[2], Buffer.from(process.argv[3], 'base64')).enqueue({
        id:{_serialized:'node-synthetic'}, timestamp:1794571200, from:'group@g.us', to:'owner@c.us',
        author:'member@c.us', body:'Synthetic Unicode habari 👋', type:'chat'});"""
    subprocess.run(["node", "--input-type=module", "-e", script, module.as_uri(), str(directory / "outbox"),
                    base64.b64encode(settings.store_key).decode()], check=True, capture_output=True, timeout=15)
    value = client.get("/device/events", headers=auth(settings)).json()
    assert len(value["events"]) == 1
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    box = value["events"][0]["box"]
    key = private.decrypt(base64.b64decode(box["key"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    event = json.loads(AESGCM(key).decrypt(base64.b64decode(box["nonce"]), base64.b64decode(box["data"]), b"hellfire-relay-v1"))
    assert event["item"]["text"]["body"] == "Synthetic Unicode habari 👋"
    assert event["item"]["is_group"]


def test_meta_mode_rejects_linked_device_pairing(gateway):
    client, settings, _, _, _ = gateway
    assert client.get("/device/whatsapp-web/pairing", headers=auth(settings)).status_code == 409
