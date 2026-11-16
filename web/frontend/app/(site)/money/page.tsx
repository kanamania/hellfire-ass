import { api, param, records } from '@/lib/api';
import { monthRange, tzs, when, words } from '@/lib/format';
import { Pager, type Summary } from '@/lib/rows';

type Search = Promise<Record<string, string | string[] | undefined>>;

export default async function MoneyPage({ searchParams }: { searchParams: Search }) {
  const params = await searchParams;
  const filters = { account: param(params, 'account'), category: param(params, 'category'), kind: param(params, 'kind'), month: param(params, 'month') };
  const range = filters.month ? monthRange(filters.month) : null;
  const [overview, accounts, wallets, candidates, ledger] = await Promise.all([
    api<{ summary: Summary | null }>('/api/overview'),
    records({ kinds: 'account', limit: '200' }),
    records({ kinds: 'balance', limit: '50' }),
    records({ kinds: 'candidate', limit: '100' }),
    records({
      kinds: 'ledger', limit: '100', accountId: filters.account, category: filters.category, kind: filters.kind,
      from: range ? String(range.from) : undefined, to: range ? String(range.to) : undefined,
      afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId'),
    }),
  ]);
  const label = new Map(accounts.rows.map((a) => [a.id, a.data.label as string]));
  const s = overview.summary;
  return (
    <>
      <h1>Money</h1>
      {s && <p className="muted">{s.month}: received {tzs(s.income)}, spent {tzs(s.expenses)}. {s.previousMonth}: received {tzs(s.previousIncome)}, spent {tzs(s.previousExpenses)}.</p>}
      <div className="grid">
        <section className="card">
          <h2>Accounts</h2>
          {s?.balances.length ? <ul className="list">{s.balances.map((b) => <li key={b.accountId}><span className="main">{b.label}</span><span className="amount">{tzs(b.balance)}</span></li>)}</ul>
            : <p className="muted">No accounts synced.</p>}
        </section>
        <section className="card">
          <h2>Balances from SMS</h2>
          {wallets.rows.length ? <ul className="list">{wallets.rows.map((w) => (
            <li key={w.id}><div className="main"><div className="title">{w.data.provider}</div><div className="muted small">as of {when(w.at)}</div></div><span className="amount">{tzs(w.data.amount)}</span></li>
          ))}</ul> : <p className="muted">None seen yet.</p>}
          <p className="muted small">The latest balance a provider stated. Display only; never counted in totals.</p>
        </section>
      </div>

      <section className="card" id="confirm" style={{ marginTop: 16 }}>
        <h2>To confirm on the phone ({candidates.rows.length})</h2>
        {candidates.rows.length ? <ul className="list">{candidates.rows.map((c) => (
          <li key={c.id}>
            <div className="main">
              <div className="title">{c.data.merchant || c.data.provider} · {words(c.data.kind)}</div>
              <div className="body">“{c.data.evidence}”</div>
              <div className="muted small">{when(c.at)}{c.data.fee ? ` · fee ${tzs(c.data.fee)}` : ''}{c.data.reference ? ` · ref ${c.data.reference}` : ''}</div>
            </div>
            <span className="amount">{tzs(c.data.amount)}</span>
          </li>
        ))}</ul> : <p className="muted">Nothing waiting.</p>}
      </section>

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Ledger</h2>
        <form className="filters">
          <label>Month<input type="month" name="month" defaultValue={filters.month} /></label>
          <label>Account<select name="account" defaultValue={filters.account ?? ''}>
            <option value="">All</option>
            {accounts.rows.map((a) => <option key={a.id} value={a.id}>{a.data.label}</option>)}
          </select></label>
          <label>Category<input name="category" defaultValue={filters.category} placeholder="Exact name" /></label>
          <label>Type<select name="kind" defaultValue={filters.kind ?? ''}>
            <option value="">All</option>
            {['EXPENSE', 'INCOME', 'FEE', 'REFUND', 'OWN_TRANSFER', 'BALANCE'].map((k) => <option key={k} value={k}>{words(k)}</option>)}
          </select></label>
          <button type="submit">Filter</button>
        </form>
        <div className="table-wrap">
          <table>
            <thead><tr><th>When</th><th>Description</th><th>Category</th><th>Account</th><th>Type</th><th className="num">Amount</th></tr></thead>
            <tbody>{ledger.rows.map((e) => (
              <tr key={e.id}>
                <td>{when(e.at)}</td><td>{e.data.description}</td><td>{e.data.category}</td>
                <td>{label.get(e.data.accountId) ?? '—'}</td><td>{words(e.data.kind)}{e.data.reversesId ? ' (reversal)' : ''}</td>
                <td className="num">{tzs(e.data.amount)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
        {!ledger.rows.length && <p className="muted">No entries match.</p>}
        <Pager page={ledger} params={filters} />
      </section>
    </>
  );
}
