import { join } from 'node:path';
import whatsapp from 'whatsapp-web.js';
import QRCode from 'qrcode';
import { CaptureSpool, atomicJson, attachCapture } from './capture.js';

process.umask(0o077);
const directory = process.env.WHATSAPP_WEB_STATE_DIR || './state/whatsapp-web';
const key = Buffer.from(process.env.STORE_KEY_BASE64 || '', 'base64');
const spool = new CaptureSpool(join(directory, 'outbox'), key);
const client = new whatsapp.Client({
  authStrategy: new whatsapp.LocalAuth({ dataPath: process.env.WHATSAPP_WEB_SESSION_DIR || join(directory, 'session') }),
  puppeteer: {
    headless: true,
    ...(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : {}),
  },
});
const capture = attachCapture(client, spool, state => atomicJson(join(directory, 'status.json'), {
  ...state, updated_at: Math.floor(Date.now() / 1000), transport: 'web', capture_only: true,
}), qr => QRCode.toDataURL(qr, { errorCorrectionLevel: 'M', width: 320, margin: 4 }));
const timer = setInterval(capture.heartbeat, 30000);
async function stop() {
  clearInterval(timer);
  capture.stop();
  try { await client.destroy(); } finally { process.exit(0); }
}
process.once('SIGTERM', stop);
process.once('SIGINT', stop);
// Never print QR tokens, message bodies, browser errors or authentication profiles.
client.initialize().catch(async () => {
  capture.fail(); clearInterval(timer);
  try { await client.destroy(); } catch { /* Initialization may have failed before a browser existed. */ }
  process.exitCode = 1;
});
