import { join } from 'node:path';
import * as tdl from 'tdl';
import { getTdjson } from 'prebuilt-tdlib';
import QRCode from 'qrcode';
import { CaptureSpool, atomicJson, attachTelegram, normalizeTelegram } from './capture.js';

process.umask(0o077);
const directory = process.env.TELEGRAM_STATE_DIR || './state/telegram';
const key = Buffer.from(process.env.STORE_KEY_BASE64 || '', 'base64');
const apiId = Number(process.env.TELEGRAM_API_ID);
const apiHash = process.env.TELEGRAM_API_HASH || '';
const publish = state => atomicJson(join(directory, 'status.json'), {
  ...state, updated_at: Math.floor(Date.now() / 1000), transport: 'telegram', capture_only: true,
});
if (!Number.isSafeInteger(apiId) || apiId <= 0 || !/^[a-f0-9]{32}$/.test(apiHash)) {
  // The owner's own api_id/api_hash from https://my.telegram.org are required; never invent defaults.
  publish({ state: 'FAILED', qr: null, error: 'TELEGRAM_API_CREDENTIALS_REQUIRED' });
  process.exit(2);
}
const spool = new CaptureSpool(join(directory, 'outbox'), key, 10000, normalizeTelegram);
tdl.configure({ tdjson: getTdjson(), verbosityLevel: 0 });
const client = tdl.createClient({
  apiId, apiHash,
  databaseDirectory: join(directory, 'tdlib-db'), filesDirectory: join(directory, 'tdlib-files'),
  // Encrypts TDLib's local database with the relay's store key.
  databaseEncryptionKey: key.toString('base64'),
  tdlibParameters: { use_message_database: true, use_secret_chats: false, system_language_code: 'en',
    application_version: '0.1.0', device_model: 'HellFire relay', system_version: 'Linux' },
});
const capture = attachTelegram(client, spool, publish,
  link => QRCode.toDataURL(link, { errorCorrectionLevel: 'M', width: 320, margin: 4 }), process.env.TELEGRAM_PASSWORD || '');
const timer = setInterval(capture.heartbeat, 30000);
async function stop() {
  clearInterval(timer);
  capture.stop();
  try { await client.close(); } finally { process.exit(0); }
}
process.once('SIGTERM', stop);
process.once('SIGINT', stop);
// Never print QR links, message bodies, TDLib logs or credentials. systemd restarts the worker on failure.
client.on('error', () => {});
