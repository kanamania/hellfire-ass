import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createDecipheriv } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync, rmSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CaptureSpool, normalizeMessage, attachCapture } from '../src/capture.js';

const message = overrides => ({ id: { _serialized: 'synthetic-message' }, timestamp: 1794571200,
  from: 'synthetic-contact@c.us', to: 'synthetic-owner@c.us', body: 'Habari, synthetic conversation', type: 'chat', ...overrides });
function fixture(t, max) {
  const dir = mkdtempSync(join(tmpdir(), 'hellfire-web-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return { dir, key: Buffer.alloc(32, 42), spool: new CaptureSpool(dir, Buffer.alloc(32, 42), max) };
}

test('captures incoming, outgoing, group authors and metadata without fetching media', () => {
  assert.equal(normalizeMessage(message()).event.collection, 'messages');
  const outgoing = normalizeMessage(message({ fromMe: true, from: 'owner@c.us', to: 'group@g.us', author: 'owner@c.us', hasMedia: true, type: 'ptt', body: '' })).event;
  assert.equal(outgoing.collection, 'message_echoes');
  assert.equal(outgoing.item.thread_id, 'group@g.us');
  assert.equal(outgoing.item.is_group, true);
  assert.equal(outgoing.item.author, 'owner@c.us');
  assert.equal(outgoing.item.has_media, true);
  assert.equal(outgoing.item.media_downloaded, false);
  assert.equal(outgoing.item.text.body, '');
  assert.throws(() => normalizeMessage(message({ timestamp: NaN })), /INVALID_MESSAGE/);
});

test('spool survives reopening and uses authenticated ciphertext bound to identity', t => {
  const { dir, key, spool } = fixture(t);
  const id = spool.enqueue(message({ _data: { password: 'must-not-be-serialized' } }));
  const bytes = readFileSync(join(dir, `${id}.json`));
  assert.equal(bytes.includes('synthetic conversation'), false);
  const box = JSON.parse(bytes);
  const data = Buffer.from(box.data, 'base64');
  const decode = aad => {
    const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(box.nonce, 'base64'));
    decipher.setAAD(Buffer.from(aad)); decipher.setAuthTag(data.subarray(-16));
    return Buffer.concat([decipher.update(data.subarray(0, -16)), decipher.final()]).toString();
  };
  const plain = decode(`hellfire-web-spool-v1:${id}`);
  assert.equal(JSON.parse(plain).item.text.body, message().body);
  assert.equal(plain.includes('must-not-be-serialized'), false);
  assert.throws(() => decode('wrong-identity'));
  assert.equal(new CaptureSpool(dir, key).enqueue(message()), id);
  assert.equal(readdirSync(dir).length, 1);
});

test('queue quota preserves old events and expired entries are removed', t => {
  const { dir, spool } = fixture(t, 1);
  const id = spool.enqueue(message());
  assert.throws(() => spool.enqueue(message({ id: { _serialized: 'second' } })), /QUEUE_FULL/);
  assert.equal(readdirSync(dir).length, 1);
  utimesSync(join(dir, `${id}.json`), new Date(0), new Date(0));
  spool.enqueue(message({ id: { _serialized: 'second' } }));
  assert.equal(readdirSync(dir).length, 1);
});

test('event listener captures own and incoming messages once and reports failures', t => {
  const { spool } = fixture(t, 1);
  const client = new EventEmitter();
  let status;
  attachCapture(client, spool, value => { status = value; }, async () => 'qr');
  client.emit('ready');
  assert.equal(status.state, 'CONNECTED');
  client.emit('message_create', message({ fromMe: true }));
  client.emit('message_create', message({ fromMe: true }));
  client.emit('message_create', message({ id: { _serialized: 'second' } }));
  assert.equal(status.error, 'QUEUE_FULL');
  client.emit('disconnected', 'private provider reason');
  assert.equal(status.state, 'DISCONNECTED');
  assert.equal(JSON.stringify(status).includes('private provider reason'), false);
});

test('late QR rendering cannot expose a code after authentication', async t => {
  const { spool } = fixture(t);
  const client = new EventEmitter();
  let complete;
  let status;
  attachCapture(client, spool, value => { status = value; }, () => new Promise(resolve => { complete = resolve; }));
  client.emit('qr', 'private-qr-token');
  client.emit('authenticated');
  complete('private-image');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(status.state, 'SYNCING');
  assert.equal(status.qr, null);
  client.emit('auth_failure');
  assert.equal(status.state, 'AUTH_FAILED');
});

test('oversized bodies are explicitly marked truncated and invalid keys fail closed', t => {
  const { dir } = fixture(t);
  const event = normalizeMessage(message({ body: 'x'.repeat(20000) })).event;
  assert.equal(event.item.text.body.length, 16000);
  assert.equal(event.item.text_truncated, true);
  assert.throws(() => new CaptureSpool(dir, Buffer.alloc(2)), /STORE_KEY_REQUIRED/);
});
