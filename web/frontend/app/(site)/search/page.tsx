import { param, records, type Row } from '@/lib/api';
import { tzs, when, words } from '@/lib/format';
import { InboxItem, LedgerItem, TaskItem } from '@/lib/rows';

const names: Record<string, string> = {
  account: 'Account', ledger: 'Payment', candidate: 'To confirm', balance: 'SMS balance', task: 'Task', message: 'Message',
  draft: 'Draft', media: 'Media', transcript: 'Transcript', memory: 'Memory', event: 'Captured',
};

function Hit({ row }: { row: Row }) {
  if (row.kind === 'ledger') return <LedgerItem row={row} />;
  if (row.kind === 'task') return <TaskItem row={row} />;
  if (row.kind === 'message' || row.kind === 'event') return <InboxItem row={row} />;
  const d = row.data;
  const title = d.title ?? d.subject ?? d.name ?? d.label ?? d.merchant ?? d.recipient ?? d.provider ?? '';
  const body = d.text ?? d.body ?? d.summary ?? d.evidence ?? '';
  return (
    <li>
      <div className="main">
        {title && <div className="title">{title}</div>}
        {body && <div className="body">{body}</div>}
        <div className="muted small">{when(row.at)}{d.amount != null ? ` · ${tzs(d.amount)}` : ''}{d.state ? ` · ${words(d.state)}` : ''}</div>
      </div>
      <span className="pill">{names[row.kind] ?? row.kind}</span>
    </li>
  );
}

export default async function SearchPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const q = param(params, 'q');
  const page = q ? await records({ q, limit: '100' }) : null;
  return (
    <>
      <h1>Search</h1>
      <form className="filters">
        <label>Words to find<input name="q" defaultValue={q} autoFocus /></label>
        <button type="submit">Search</button>
      </form>
      <p className="muted small">Literal text match over everything synced. Meaning search runs only on the phone.</p>
      {page && (
        <section className="card">
          {page.rows.length ? <ul className="list">{page.rows.map((row) => <Hit key={`${row.kind}:${row.id}`} row={row} />)}</ul> : <p className="muted">No matches.</p>}
          {page.next && <p className="muted small">Showing the newest 100 matches.</p>}
        </section>
      )}
    </>
  );
}
