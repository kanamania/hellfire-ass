export const ITERATIONS = 600_000;
export const CHUNK = 256 * 1024;

function raw(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

export async function derive(passphrase: string, salt: Uint8Array): Promise<CryptoKey> {
  if (passphrase.length < 12 || passphrase.length > 1024) throw new Error('Use a passphrase of at least 12 characters');
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: raw(salt), iterations: ITERATIONS, hash: 'SHA-256' },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'],
  );
}

export function aad(kind: string, id: string): Uint8Array {
  return new TextEncoder().encode(`library-${kind}-v1:${id}`);
}

export async function seal(key: CryptoKey, plain: Uint8Array, extra: Uint8Array, iv = crypto.getRandomValues(new Uint8Array(12))): Promise<Uint8Array> {
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: raw(iv), additionalData: raw(extra) }, key, raw(plain)));
  const out = new Uint8Array(1 + iv.length + ct.length);
  out[0] = 1;
  out.set(iv, 1);
  out.set(ct, 13);
  return out;
}

export async function open(key: CryptoKey, envelope: Uint8Array, extra: Uint8Array): Promise<Uint8Array> {
  if (envelope.length < 29 || envelope[0] !== 1) throw new Error('Invalid encrypted payload');
  const iv = envelope.slice(1, 13);
  const ct = envelope.slice(13);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw(iv), additionalData: raw(extra) }, key, raw(ct)));
}

export function b64(bytes: Uint8Array): string {
  let text = '';
  for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(text);
}

export function unb64(text: string): Uint8Array {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', raw(bytes)));
  return [...digest].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function playable(mime: string): boolean {
  return /^(video\/(mp4|webm|ogg)|audio\/(mpeg|mp4|wav|webm|ogg|aac)|image\/(jpeg|png|gif|webp|bmp))$/.test(mime);
}
