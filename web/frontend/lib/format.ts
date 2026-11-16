const zone = 'Africa/Dar_es_Salaam';
const number = new Intl.NumberFormat('en-GB');
const dateTime = new Intl.DateTimeFormat('en-GB', { timeZone: zone, day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
const dayOnly = new Intl.DateTimeFormat('en-GB', { timeZone: zone, day: 'numeric', month: 'short', year: 'numeric' });

/** Whole Tanzanian shillings, as stored by the phone's ledger. */
export const tzs = (amount: number | null | undefined) =>
  amount == null ? '—' : `${amount < 0 ? '−' : ''}TZS ${number.format(Math.abs(amount))}`;
export const when = (ms: number | null | undefined) => (ms ? `${dateTime.format(ms)} EAT` : '—');
export const day = (ms: number | null | undefined) => (ms ? dayOnly.format(ms) : '—');
export const duration = (ms: number | null | undefined) => {
  if (ms == null) return 'unknown length';
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
};
export const words = (value: string | null | undefined) =>
  (value ?? '').toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

/** First month day and next month day in EAT (UTC+3, no daylight saving) for a yyyy-mm value. */
export function monthRange(month: string): { from: number; to: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (!match) return null;
  const [year, index] = [Number(match[1]), Number(match[2]) - 1];
  const offset = 3 * 3600_000;
  return { from: Date.UTC(year, index, 1) - offset, to: Date.UTC(year, index + 1, 1) - offset };
}

/** Text of a captured SMS/notification, from the phone's normalized fields. */
export function eventText(fields: Record<string, string[]> | undefined): { title: string; body: string } {
  const get = (name: string) => (fields?.[name] ?? []).filter(Boolean).join('\n');
  return { title: get('title') || get('sender_hint') || get('conversation_title'), body: get('big_text') || get('text') || get('text_lines') || get('messages') };
}
