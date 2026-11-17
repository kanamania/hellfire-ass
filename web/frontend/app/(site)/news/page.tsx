import Link from 'next/link';
import { api } from '@/lib/api';
import { when } from '@/lib/format';
import type { BriefingSummary } from '@/lib/news';

export default async function NewsPage() {
  const briefings = await api<BriefingSummary[]>('/api/news/briefings');
  return (
    <>
      <h1>News</h1>
      <p>Morning and evening briefings prepared on your phone. Reading here is sent back so the phone does not repeat it.</p>
      <ul className="list">
        {briefings.map((item) => (
          <li key={item.id}>
            <Link href={`/news/briefing/${encodeURIComponent(item.id)}`}>{item.title || item.slot}</Link>
            <span> · {when(item.generatedAt)} · {item.counts.stories} stories · {item.counts.unread} unread · {item.counts.changed} changed</span>
          </li>
        ))}
        {briefings.length === 0 && <li>No briefing has been synced yet.</li>}
      </ul>
    </>
  );
}
