import { createCipheriv, createHash, randomBytes } from 'node:crypto';
import { mkdirSync, readdirSync, readFileSync, renameSync, writeFileSync, unlinkSync, statSync } from 'node:fs';
import { join } from 'node:path';

const hash = text => createHash('sha256').update(text).digest('hex');
export const MAX_EVENT_BYTES = 128 * 1024;

// Select documented message fields only; never serialize the client or raw browser data.
export function normalizeMessage(message) {
  const id = message.id?._serialized;
  if (typeof id !== 'string' || !id || id.length > 512 || !Number.isSafeInteger(message.timestamp) || message.timestamp <= 0) {
    throw new Error('INVALID_MESSAGE');
  }
  const from = String(message.from ?? '').slice(0, 512);
  const to = String(message.to ?? '').slice(0, 512);
  const thread = message.fromMe ? to : from;
  const original = String(message.body ?? '');
  const event = {
    schema_version: 1, transport: 'whatsapp_web', field: 'message_create',
    collection: message.fromMe ? 'message_echoes' : 'messages',
    item: {
      id, from, to, author: String(message.author ?? '').slice(0, 512),
      thread_id: thread, is_group: thread.endsWith('@g.us'), timestamp: String(message.timestamp),
      type: String(message.type ?? 'unknown').slice(0, 64),
      text: { body: original.slice(0, 16000) }, text_truncated: original.length > 16000,
      has_media: Boolean(message.hasMedia), media_downloaded: false,
    },
  };
  return { id: hash(`whatsapp-web:message:${id}`), event };
}

export function atomicJson(path, value) {
  const temp = `${path}.${randomBytes(8).toString('hex')}.tmp`;
  writeFileSync(temp, JSON.stringify(value), { mode: 0o600, flush: true });
  renameSync(temp, path);
}

export class CaptureSpool {
  // [normalize] turns a client message into { id, event }; the Telegram worker passes its own.
  constructor(directory, key, maxEvents = 10000, normalize = normalizeMessage) {
    if (key.length !== 32) throw new Error('STORE_KEY_REQUIRED');
    this.directory = directory;
    this.key = key;
    this.maxEvents = maxEvents;
    this.normalize = normalize;
    mkdirSync(directory, { recursive: true, mode: 0o700 });
  }
  enqueue(message) {
    const { id, event } = this.normalize(message);
    const target = join(this.directory, `${id}.json`);
    // An already pending message is immutable; replay after ACK is deduplicated by the relay.
    try { readFileSync(target); return id; } catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (this.prune().length >= this.maxEvents) throw new Error('QUEUE_FULL');
    const plain = Buffer.from(JSON.stringify(event));
    if (plain.length > MAX_EVENT_BYTES) throw new Error('MESSAGE_TOO_LARGE');
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, nonce);
    cipher.setAAD(Buffer.from(`hellfire-web-spool-v1:${id}`));
    const data = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);
    atomicJson(target, { version: 1, nonce: nonce.toString('base64'), data: data.toString('base64') });
    plain.fill(0);
    return id;
  }
  prune(now = Date.now()) {
    const files = [];
    for (const name of readdirSync(this.directory)) {
      if (!/^[a-f0-9]{64}\.json$/.test(name)) continue;
      const path = join(this.directory, name);
      try {
        if (now - statSync(path).mtimeMs > 7 * 86400000) unlinkSync(path);
        else files.push(name);
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    return files;
  }
}

export function attachCapture(client, spool, publish, qrDataUrl) {
  let current = { state: 'STARTING', qr: null, error: null };
  let qrVersion = 0;
  const update = patch => { current = { ...current, ...patch }; publish(current); };
  const clearQr = () => { qrVersion++; return { qr: null, qr_expires_at: null }; };
  client.on('qr', qr => {
    const version = ++qrVersion;
    update({ state: 'PAIRING_REQUIRED', qr: null, error: null });
    Promise.resolve(qrDataUrl(qr)).then(data => {
      if (version === qrVersion) update({ qr: data, qr_expires_at: Math.floor(Date.now() / 1000) + 45 });
    }).catch(() => update({ error: 'QR_RENDER_FAILED' }));
  });
  client.on('authenticated', () => update({ state: 'SYNCING', ...clearQr(), error: null }));
  client.on('ready', () => update({ state: 'CONNECTED', ...clearQr(), error: null }));
  client.on('auth_failure', () => update({ state: 'AUTH_FAILED', ...clearQr(), error: 'PAIR_AGAIN' }));
  client.on('disconnected', () => update({ state: 'DISCONNECTED', ...clearQr(), error: 'RESTART_WORKER_OR_PAIR_AGAIN' }));
  client.on('message_create', message => {
    try { spool.enqueue(message); }
    catch (error) { update({ error: error.message === 'QUEUE_FULL' ? 'QUEUE_FULL' : 'CAPTURE_FAILED' }); }
  });
  publish(current);
  return {
    heartbeat: () => {
      try { spool.prune(); update({}); }
      catch { update({ error: 'SPOOL_UNAVAILABLE' }); }
    },
    fail: () => update({ state: 'FAILED', ...clearQr(), error: 'BROWSER_START_FAILED' }),
    stop: () => update({ state: 'STOPPED', ...clearQr() }),
  };
}
