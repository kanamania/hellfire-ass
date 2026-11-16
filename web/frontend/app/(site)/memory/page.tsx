import { param, records } from '@/lib/api';
import { when, words } from '@/lib/format';
import { Pager } from '@/lib/rows';

export default async function MemoryPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const page = await records({ kinds: 'memory', limit: '100', afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId') });
  return (
    <>
      <h1>Memory</h1>
      <section className="card">
        {page.rows.length ? <ul className="list">{page.rows.map((m) => (
          <li key={m.id}>
            <div className="main">
              <div className="title">{m.data.subject}</div>
              <div className="body">{m.data.text}</div>
              <div className="muted small">{when(m.at)} · {words(m.data.kind)}{m.data.confirmedAt ? ' · confirmed' : ''}</div>
            </div>
            <span className="pill">{m.data.confidence}%</span>
          </li>
        ))}</ul> : <p className="muted">Nothing saved.</p>}
        <Pager page={page} params={{}} />
      </section>
    </>
  );
}
