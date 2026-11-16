import { api, records, type Row } from '@/lib/api';
import { duration, when, words } from '@/lib/format';

const clock = (ms: number) => {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

export default async function MediaItemPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const item = await api<Row>(`/api/record/media/${encodeURIComponent(id)}`);
  // Transcripts can be long; page through every segment in time order.
  const segments: Row[] = [];
  let cursor: { afterAt?: string; afterId?: string } = {};
  for (let i = 0; i < 50; i++) {
    const page = await records({ kinds: 'transcript', mediaId: id, order: 'asc', limit: '200', ...cursor });
    segments.push(...page.rows);
    if (!page.next) break;
    cursor = { afterAt: String(page.next.afterAt), afterId: page.next.afterId };
  }
  const d = item.data;
  return (
    <>
      <h1>{d.name}</h1>
      <p className="muted">{when(d.recordedAt ?? item.at)} · {words(d.kind)} · {duration(d.durationMs)} · summary {words(d.summaryState)}</p>
      {d.summary && <section className="card"><h2>Summary</h2><p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{d.summary}</p><p className="muted small">Generated on the phone; an inference, not a record.</p></section>}
      <section className="card transcript" style={{ marginTop: 16 }}>
        <h2>Transcript</h2>
        {segments.length ? segments.map((s) => (
          <p key={s.id}><span className="t">{clock(s.data.startMs)}</span>{s.data.speaker && s.data.speaker !== 'UNKNOWN' && <strong>{s.data.speaker}: </strong>}{s.data.text}</p>
        )) : <p className="muted">No transcript yet.</p>}
      </section>
    </>
  );
}
