'use server';

import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { API_URL, SESSION_COOKIE } from '@/lib/api';

export async function login(_previous: string | null, form: FormData): Promise<string | null> {
  const password = form.get('password');
  if (typeof password !== 'string' || !password) return 'Enter your password.';
  const response = await fetch(`${API_URL}/api/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ password }), cache: 'no-store',
  });
  if (response.status === 429) return 'Too many attempts. Wait a minute and try again.';
  if (!response.ok) return 'That password is not right.';
  const { token, expiresAt } = (await response.json()) as { token: string; expiresAt: number };
  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, secure: true, sameSite: 'strict', path: '/', expires: new Date(expiresAt) });
  redirect('/');
}

export async function logout() {
  (await cookies()).delete(SESSION_COOKIE);
  redirect('/login');
}
