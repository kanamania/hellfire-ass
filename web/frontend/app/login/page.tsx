'use client';

import { useActionState } from 'react';
import { login } from '../actions';

export default function LoginPage() {
  const [error, action, pending] = useActionState(login, null);
  return (
    <main className="login card">
      <h1>HellFire <span style={{ color: 'var(--primary)' }}>Ass</span></h1>
      <p className="muted">A read-only copy of what your phone has synced.</p>
      <form action={action}>
        <label className="muted small" htmlFor="password">Password</label>
        <input id="password" name="password" type="password" autoComplete="current-password" required autoFocus />
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </main>
  );
}
