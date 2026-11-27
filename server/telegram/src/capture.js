import { createHash } from 'node:crypto';
import { CaptureSpool, atomicJson } from '../../whatsapp-web/src/capture.js';

export { CaptureSpool, atomicJson };
const hash = text => createHash('sha256').update(text).digest('hex');

/** Text of a TDLib message: plain text, or the caption of media. Other content is recorded by type only. */
export function messageText(content) {
  if (!content || typeof content._ !== 'string') return '';
  if (content._ === 'messageText') return String(content.text?.text ?? '');
  return String(content.caption?.text ?? '');
}

/**
 * Select documented TDLib message fields only (capture-only; owner decision 2026-11-27).
 * [chat] is { title, group } for the message's chat, looked up once and cached by the caller.
 */
export function normalizeTelegram({ message, chat }) {
  if (!message || !Number.isSafeInteger(message.id) || !Number.isSafeInteger(message.chat_id) || !Number.isSafeInteger(message.date) || message.date <= 0) {
    throw new Error('INVALID_MESSAGE');
  }
  const id = `${message.chat_id}:${message.id}`;
  const title = String(chat?.title ?? '').slice(0, 256);
  const original = messageText(message.content);
  const outgoing = Boolean(message.is_outgoing);
  const event = {
    schema_version: 1, transport: 'telegram', field: 'updateNewMessage',
    collection: outgoing ? 'message_echoes' : 'messages',
    item: {
      id, from: outgoing ? 'me' : title, to: outgoing ? title : 'me', author: '',
      thread_id: `tg:${message.chat_id}`, is_group: Boolean(chat?.group), timestamp: String(message.date),
      type: String(message.content?._ ?? 'unknown').slice(0, 64),
      text: { body: original.slice(0, 16000) }, text_truncated: original.length > 16000,
      has_media: message.content?._ !== 'messageText', media_downloaded: false,
    },
  };
  return { id: hash(`telegram:message:${id}`), event };
}

/**
 * Drives TDLib's QR login and capture. [client] is a tdl client; [publish] writes status.json; [qrDataUrl] renders a
 * tg://login link. Owner 2FA password, when the account has one, comes from [password] (never logged).
 */
export function attachTelegram(client, spool, publish, qrDataUrl, password = '') {
  let current = { state: 'STARTING', qr: null, error: null };
  let version = 0;
  const chats = new Map();
  const update = patch => { current = { ...current, ...patch }; publish(current); };
  const clearQr = () => { version++; return { qr: null, qr_expires_at: null }; };
  async function chat(id) {
    if (!chats.has(id)) {
      const value = await client.invoke({ _: 'getChat', chat_id: id });
      const kind = value?.type?._;
      chats.set(id, { title: value?.title ?? '', group: kind === 'chatTypeBasicGroup' || kind === 'chatTypeSupergroup' });
      if (chats.size > 2000) chats.delete(chats.keys().next().value);
    }
    return chats.get(id);
  }
  async function authorization(state) {
    switch (state?._) {
      case 'authorizationStateWaitPhoneNumber':
        update({ state: 'PAIRING_REQUIRED', ...clearQr(), error: null });
        await client.invoke({ _: 'requestQrCodeAuthentication', other_user_ids: [] });
        return;
      case 'authorizationStateWaitOtherDeviceConfirmation': {
        const mine = ++version;
        update({ state: 'PAIRING_REQUIRED', qr: null, error: null });
        const data = await qrDataUrl(state.link);
        if (mine === version) update({ qr: data, qr_expires_at: Math.floor(Date.now() / 1000) + 30 });
        return;
      }
      case 'authorizationStateWaitPassword':
        if (!password) { update({ state: 'AUTH_FAILED', ...clearQr(), error: 'PASSWORD_REQUIRED' }); return; }
        await client.invoke({ _: 'checkAuthenticationPassword', password });
        return;
      case 'authorizationStateReady': update({ state: 'CONNECTED', ...clearQr(), error: null }); return;
      case 'authorizationStateLoggingOut':
      case 'authorizationStateClosed': update({ state: 'DISCONNECTED', ...clearQr(), error: 'RESTART_WORKER_OR_PAIR_AGAIN' }); return;
      default: return;
    }
  }
  client.on('update', item => {
    if (item?._ === 'updateAuthorizationState') {
      authorization(item.authorization_state).catch(() => update({ error: 'PAIR_AGAIN' }));
    } else if (item?._ === 'updateNewMessage') {
      chat(item.message?.chat_id).then(info => spool.enqueue({ message: item.message, chat: info }))
        .catch(error => update({ error: error?.message === 'QUEUE_FULL' ? 'QUEUE_FULL' : 'CAPTURE_FAILED' }));
    }
  });
  client.on('error', () => update({ error: 'CAPTURE_FAILED' }));
  publish(current);
  return {
    heartbeat: () => { try { spool.prune(); update({}); } catch { update({ error: 'SPOOL_UNAVAILABLE' }); } },
    fail: () => update({ state: 'FAILED', ...clearQr(), error: 'BROWSER_START_FAILED' }),
    stop: () => update({ state: 'STOPPED', ...clearQr() }),
  };
}
