import { askPhone } from './commands';

export interface Command { seq: number; kind: string; target: string; status: string; result: string | null }

/** A button that asks the phone, or what happened to the last request for this record. */
export function PhoneCommand({ kind, target, label, back, recent }: { kind: string; target: string; label: string; back: string; recent: Command[] }) {
  const last = recent.find((c) => c.kind === kind && c.target === target);
  if (last?.status === 'PENDING') return <span className="pill">Sent · applies at the phone’s next sync</span>;
  return (
    <form action={askPhone} style={{ display: 'inline' }}>
      <input type="hidden" name="kind" value={kind} />
      <input type="hidden" name="target" value={target} />
      <input type="hidden" name="back" value={back} />
      <button type="submit" className="small">{label}</button>
      {last?.status === 'STALE' && <span className="muted small"> {last.result ?? 'Already changed on the phone'}</span>}
    </form>
  );
}
