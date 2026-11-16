import Link from 'next/link';
import { param, records } from '@/lib/api';
import { tzs, when, words } from '@/lib/format';
import { InboxItem, List, Pager } from '@/lib/rows';

const channels: Record<string, { label: string; query: Record<string, string | string[]> }> = {
  all: { label: 'All', query: { kinds: 'message,event' } },
  email: { label: 'Email', query: { kinds: 'message', channel: 'EMAIL_IMAP' } },
  whatsapp: { label: 'WhatsApp', query: { kinds: 'message', channel: ['WHATSAPP_WEB', 'WHATSAPP_GATEWAY', 'WHATSAPP_BUSINESS'] } },
  sms: { label: 'SMS', query: { kinds: 'event', source: 'android_sms' } },
  notifications: { label: 'Notifications', query: { kinds: 'event', source: 'android_notification' } },
  drafts: { label: 'Drafts', query: { kinds: 'draft' } },
};

export default async function InboxPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const key = param(params, 'channel') ?? 'all';
  const channel = channels[key] ?? channels.all;
  const q = param(params, 'q');
  const page = await records({ ...channel.query, q, limit: '50', afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId') });
  return (
    <>
      <h1>Inbox</h1>
      <nav className="tabs">{Object.entries(channels).map(([value, c]) => (
        <Link key={value} href={`?channel=${value}`} aria-current={value === key ? 'page' : undefined}>{c.label}</Link>
      ))}</nav>
      <form className="filters">
        <input type="hidden" name="channel" value={key} />
        <label>Contains<input name="q" defaultValue={q} /></label>
        <button type="submit">Search</button>
      </form>
      <section className="card">
        {key === 'drafts' ? <List rows={page.rows} item={DraftItem} empty="No drafts." /> : <List rows={page.rows} item={InboxItem} empty="Nothing here." />}
        <Pager page={page} params={{ channel: key, q }} />
      </section>
      <p className="muted small">SMS and notifications appear when they look like messages or payments. Security codes are never copied. Replies are approved and sent from the phone.</p>
    </>
  );
}

function DraftItem({ row }: { row: { data: Record<string, any>; at: number } }) {
  const d = row.data;
  return (
    <li>
      <div className="main">
        <div className="title">To {d.recipient}{d.subject ? ` · ${d.subject}` : ''}</div>
        <div className="body">{d.body}</div>
        <div className="muted small">{when(row.at)} · {words(d.channel)}{d.error ? ` · ${d.error}` : ''}</div>
      </div>
      <span className="pill">{words(d.state)}</span>
    </li>
  );
}
