import Link from 'next/link';
import type { Page, Row } from './api';
import { eventText, tzs, when, words } from './format';

export interface Summary {
  month: string; income: number; expenses: number;
  previousMonth: string; previousIncome: number; previousExpenses: number;
  balances: { accountId: string; label: string; balance: number }[];
}

const channelName: Record<string, string> = {
  EMAIL_IMAP: 'Email', WHATSAPP_WEB: 'WhatsApp', WHATSAPP_GATEWAY: 'WhatsApp', WHATSAPP_BUSINESS: 'WhatsApp',
  android_sms: 'SMS', android_notification: 'Notification',
};

export function SyncBanner({ lastSyncAt }: { lastSyncAt: number | null }) {
  if (!lastSyncAt) return <p className="banner">Nothing has synced yet. Turn on Web access in the phone’s Settings.</p>;
  const stale = Date.now() - lastSyncAt > 3 * 3600_000;
  return stale
    ? <p className="banner">Last synced {when(lastSyncAt)}. The phone may be off, offline or holding back background work.</p>
    : <p className="muted small">Copied from your phone · last synced {when(lastSyncAt)}</p>;
}

export function LedgerItem({ row }: { row: Row }) {
  const d = row.data;
  return (
    <li>
      <div className="main">
        <div className="title">{d.description || d.category}</div>
        <div className="muted small">{when(row.at)} · {d.category} · {words(d.kind)}{d.reversesId ? ' · reversal' : ''}</div>
      </div>
      <span className={`amount${d.amount > 0 ? ' in' : ''}`}>{tzs(d.amount)}</span>
    </li>
  );
}

export function TaskItem({ row }: { row: Row }) {
  const d = row.data;
  return (
    <li>
      <div className="main">
        <div className="title">{d.title}</div>
        <div className="muted small">
          Due {when(d.dueAt)}{d.party ? ` · ${d.party}` : ''}
          {d.direction === 'I_OWE' ? ' · I owe' : d.direction === 'OWED_TO_ME' ? ' · owed to me' : ''}
          {d.kind === 'BILL' ? ` · bill${d.repeatMonthly ? ', monthly' : ''}` : ''}
          {d.completedAt ? ` · done ${when(d.completedAt)}` : ''}
        </div>
        {d.evidence && <div className="body">“{d.evidence}”</div>}
      </div>
      <span className="amount">{d.amount != null ? tzs(d.amount) : <span className="pill">{words(d.state)}</span>}</span>
    </li>
  );
}

/** An email/WhatsApp message or a captured SMS/notification. */
export function InboxItem({ row }: { row: Row }) {
  const d = row.data;
  if (row.kind === 'event') {
    const { title, body } = eventText(d.fields);
    return (
      <li>
        <div className="main">
          <div className="title">{title || d.packageName}</div>
          <div className="body">{body}</div>
          <div className="muted small">{when(row.at)} · {channelName[d.source] ?? d.source}{d.source === 'android_notification' ? ` · ${d.packageName}` : ''}</div>
        </div>
        {d.label === 'FINANCE_CANDIDATE' && <span className="pill accent">Payment</span>}
      </li>
    );
  }
  return (
    <li>
      <div className="main">
        <div className="title">{d.direction === 'OUTGOING' ? `To ${d.recipient || 'unknown'}` : d.sender || 'Unknown sender'}{d.subject ? ` · ${d.subject}` : ''}</div>
        <div className="body">{d.body}</div>
        <div className="muted small">{when(row.at)} · {channelName[d.channel] ?? d.channel}</div>
      </div>
      {d.direction === 'OUTGOING' && <span className="pill">Sent</span>}
    </li>
  );
}

export function List({ rows, item: Item, empty }: { rows: Row[]; item: (props: { row: Row }) => React.ReactNode; empty: string }) {
  if (!rows.length) return <p className="muted">{empty}</p>;
  return <ul className="list">{rows.map((row) => <Item key={`${row.kind}:${row.id}`} row={row} />)}</ul>;
}

/** “Older” link carrying the current filters plus the keyset cursor. */
export function Pager({ page, params }: { page: Page; params: Record<string, string | undefined> }) {
  if (!page.next) return null;
  const query = new URLSearchParams(Object.entries(params).filter((entry): entry is [string, string] => !!entry[1]));
  query.set('afterAt', String(page.next.afterAt));
  query.set('afterId', page.next.afterId);
  return <p className="pager"><Link className="button quiet" href={`?${query}`}>Older</Link></p>;
}
