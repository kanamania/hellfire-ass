import Link from 'next/link';
import { param, records } from '@/lib/api';
import { List, Pager, TaskItem } from '@/lib/rows';

const states = [['ACTIVE', 'Active'], ['REVIEW', 'To review'], ['DONE', 'Done'], ['REJECTED', 'Rejected']];

export default async function TasksPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const state = param(params, 'state') ?? 'ACTIVE';
  const page = await records({ kinds: 'task', state, limit: '100', order: state === 'ACTIVE' ? 'asc' : undefined, afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId') });
  return (
    <>
      <h1>Tasks</h1>
      <nav className="tabs">{states.map(([value, label]) => <Link key={value} href={`?state=${value}`} aria-current={value === state ? 'page' : undefined}>{label}</Link>)}</nav>
      <section className="card">
        <List rows={page.rows} item={TaskItem} empty="No tasks here." />
        <Pager page={page} params={{ state }} />
      </section>
      <p className="muted small">Activate, finish, snooze or reschedule tasks on the phone.</p>
    </>
  );
}
