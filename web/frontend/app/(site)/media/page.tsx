import Link from 'next/link';
import { param, records } from '@/lib/api';
import { duration, when, words } from '@/lib/format';
import { Pager } from '@/lib/rows';

export default async function MediaPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const page = await records({ kinds: 'media', limit: '50', afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId') });
  return (
    <>
      <h1>Recordings and media</h1>
      <p className="muted">Transcripts and summaries made on the phone. Original audio, images and video stay on the phone.</p>
      <section className="card">
        {page.rows.length ? <ul className="list">{page.rows.map((m) => (
          <li key={m.id}>
            <div className="main">
              <Link className="title" href={`/media/${encodeURIComponent(m.id)}`}>{m.data.name}</Link>
              {m.data.summary && <div className="body">{m.data.summary}</div>}
              <div className="muted small">{when(m.data.recordedAt ?? m.at)} · {words(m.data.kind)} · {duration(m.data.durationMs)}</div>
            </div>
            <span className="pill">{words(m.data.state)}</span>
          </li>
        ))}</ul> : <p className="muted">No media synced.</p>}
        <Pager page={page} params={{}} />
      </section>
    </>
  );
}
