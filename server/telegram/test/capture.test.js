import test from 'node:test';
import assert from 'node:assert/strict';
import { createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CaptureSpool, attachTelegram, normalizeTelegram } from '../src/capture.js';

const message = (extra = {}) => ({ id: 42, chat_id: -100, date: 1794571200, is_outgoing: false,
  content: { _: 'messageText', text: { text: 'Synthetic Telegram text' } }, ...extra });

test('normalizes text, captions and direction with the relay identity', () => {
  const { id, event } = normalizeTelegram({ message: message(), chat: { title: 'Asha', group: false } });
  assert.equal(id, createHash('sha256').update('telegram:message:-100:42').digest('hex'));
  assert.equal(event.transport, 'telegram');
  assert.equal(event.collection, 'messages');
  assert.equal(event.item.from, 'Asha');
  assert.equal(event.item.text.body, 'Synthetic Telegram text');
  const out = normalizeTelegram({ message: message({ is_outgoing: true, content: { _: 'messagePhoto', caption: { text: 'Invoice' } } }), chat: { title: 'Asha' } }).event;
  assert.equal(out.collection, 'message_echoes');
  assert.equal(out.item.to, 'Asha');
  assert.equal(out.item.text.body, 'Invoice');
  assert.equal(out.item.has_media, true);
  assert.throws(() => normalizeTelegram({ message: { id: 'x' } }), /INVALID_MESSAGE/);
});

test('spool encrypts Telegram events with the relay format', () => {
  const directory = mkdtempSync(join(tmpdir(), 'tg-'));
  const key = randomBytes(32);
  const spool = new CaptureSpool(directory, key, 10, normalizeTelegram);
  const id = spool.enqueue({ message: message(), chat: { title: 'Asha' } });
  const box = JSON.parse(readFileSync(join(directory, `${id}.json`), 'utf8'));
  const data = Buffer.from(box.data, 'base64');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(box.nonce, 'base64'));
  decipher.setAAD(Buffer.from(`hellfire-web-spool-v1:${id}`));
  decipher.setAuthTag(data.subarray(data.length - 16));
  const plain = Buffer.concat([decipher.update(data.subarray(0, data.length - 16)), decipher.final()]).toString();
  assert.equal(JSON.parse(plain).item.id, '-100:42');
  assert.ok(!readdirSync(directory).some(name => readFileSync(join(directory, name), 'utf8').includes('Synthetic')));
});

test('QR login and capture follow TDLib authorization updates', async () => {
  const handlers = {};
  const invoked = [];
  const client = { on: (name, fn) => { handlers[name] = fn; }, invoke: async request => { invoked.push(request._); return request._ === 'getChat' ? { title: 'Asha', type: { _: 'chatTypePrivate' } } : {}; } };
  const states = [];
  const queued = [];
  attachTelegram(client, { enqueue: value => queued.push(value), prune: () => [] }, state => states.push(state), async link => `data:${link}`);
  handlers.update({ _: 'updateAuthorizationState', authorization_state: { _: 'authorizationStateWaitPhoneNumber' } });
  await new Promise(r => setImmediate(r));
  assert.ok(invoked.includes('requestQrCodeAuthentication'));
  handlers.update({ _: 'updateAuthorizationState', authorization_state: { _: 'authorizationStateWaitOtherDeviceConfirmation', link: 'tg://login?token=x' } });
  await new Promise(r => setImmediate(r));
  assert.equal(states.at(-1).qr, 'data:tg://login?token=x');
  handlers.update({ _: 'updateAuthorizationState', authorization_state: { _: 'authorizationStateWaitPassword' } });
  await new Promise(r => setImmediate(r));
  assert.equal(states.at(-1).error, 'PASSWORD_REQUIRED');
  handlers.update({ _: 'updateAuthorizationState', authorization_state: { _: 'authorizationStateReady' } });
  await new Promise(r => setImmediate(r));
  assert.equal(states.at(-1).state, 'CONNECTED');
  assert.equal(states.at(-1).qr, null);
  handlers.update({ _: 'updateNewMessage', message: message() });
  await new Promise(r => setTimeout(r, 10));
  assert.equal(queued.length, 1);
  assert.equal(queued[0].chat.title, 'Asha');
});
