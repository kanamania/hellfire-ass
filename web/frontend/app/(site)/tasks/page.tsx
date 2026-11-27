import Link from 'next/link';
import { api, param, records } from '@/lib/api';
import { List, Pager, TaskItem } from '@/lib/rows';
import { PhoneCommand, type Command } from '@/lib/phone-command';

const states = [['ACTIVE', 'Active'], ['REVIEW', 'To review'], ['DONE', 'Done'], ['REJECTED', 'Rejected']];

export default async function TasksPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const state = param(params, 'state') ?? 'ACTIVE';
  const [page, { commands }] = await Promise.all([
    records({ kinds: 'task', state, limit: '100', order: state === 'ACTIVE' ? 'asc' : undefined, afterAt: param(params, 'afterAt'), afterId: param(params, 'afterId') }),
    api<{ commands: Command[] }>('/api/commands'),
  ]);
  const actions = (row: { id: string }) => state === 'ACTIVE' ? (
    <span className="actions">
      <PhoneCommand kind="task.done" target={row.id} label="Done" back="/tasks" recent={commands} />{' '}
      <PhoneCommand kind="task.snooze" target={row.id} label="Snooze" back="/tasks" recent={commands} />
    </span>) : null;
  return (
    <>
      <h1>Tasks</h1>
      <nav className="tabs">{states.map(([value, label]) => <Link key={value} href={`?state=${value}`} aria-current={value === state ? 'page' : undefined}>{label}</Link>)}</nav>
      <section className="card">
        <List rows={page.rows} item={({ row }) => <TaskItem row={row}>{actions(row)}</TaskItem>} empty="No tasks here." />
        <Pager page={page} params={{ state }} />
      </section>
      <p className="muted small">Done and Snooze are sent to the phone, which applies them at its next sync. Activate or reschedule on the phone.</p>
    </>
  );
}
