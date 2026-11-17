import { api } from '@/lib/api';
import type { NewsProgress, Story } from '@/lib/news';
import { Reader } from '../../reader';

export default async function BriefingPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const view = await api<{ items: Story[]; progress: NewsProgress | null }>(`/api/news/briefing/${encodeURIComponent(id)}`);
  return <Reader briefingId={id} items={view.items} start={view.progress?.position ?? 0} />;
}
