import Link from 'next/link';
import { api, type Row } from '@/lib/api';
import { tzs } from '@/lib/format';
import { InboxItem, LedgerItem, List, SyncBanner, TaskItem, type Summary } from '@/lib/rows';

interface Overview {
  lastSyncAt: number | null; summary: Summary | null;
  toConfirm: number; tasksToReview: number; draftsWaiting: number;
  dueTasks: Row[]; recentLedger: Row[]; recentInbox: Row[];
}

export default async function OverviewPage() {
  const o = await api<Overview>('/api/overview');
  const s = o.summary;
  return (
    <>
      <h1>Overview</h1>
      <SyncBanner lastSyncAt={o.lastSyncAt} />
      <div className="kpis">
        <div className="card kpi"><div className="label">Spent in {s?.month ?? 'this month'}</div><div className="value">{tzs(s?.expenses)}</div></div>
        <div className="card kpi"><div className="label">Received in {s?.month ?? 'this month'}</div><div className="value">{tzs(s?.income)}</div></div>
        <Link href="/money#confirm" className="card kpi"><div className="label">Payments to confirm</div><div className="value">{o.toConfirm}</div></Link>
        <Link href="/tasks?state=REVIEW" className="card kpi"><div className="label">Tasks to review</div><div className="value">{o.tasksToReview}</div></Link>
        <Link href="/inbox?channel=drafts" className="card kpi"><div className="label">Drafts waiting</div><div className="value">{o.draftsWaiting}</div></Link>
      </div>
      <p className="muted small">Confirming, sending and other changes happen on the phone. Totals count confirmed ledger entries only, as on the phone.</p>
      <div className="grid">
        <section className="card">
          <h2>Balances</h2>
          {s?.balances.length ? (
            <ul className="list">{s.balances.map((b) => (
              <li key={b.accountId}><Link className="main" href={`/money?account=${encodeURIComponent(b.accountId)}`}>{b.label}</Link><span className="amount">{tzs(b.balance)}</span></li>
            ))}</ul>
          ) : <p className="muted">No accounts synced.</p>}
        </section>
        <section className="card"><h2>Due in the next two days</h2><List rows={o.dueTasks} item={TaskItem} empty="Nothing due." /></section>
        <section className="card"><h2>Recent payments</h2><List rows={o.recentLedger} item={LedgerItem} empty="No confirmed payments yet." /></section>
        <section className="card"><h2>Recent messages</h2><List rows={o.recentInbox} item={InboxItem} empty="No messages synced." /></section>
      </div>
    </>
  );
}
