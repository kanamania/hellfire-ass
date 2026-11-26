'use server';

import { cookies, headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { API_URL, SESSION_COOKIE } from '@/lib/api';

export async function login(_previous: string | null, form: FormData): Promise<string | null> {
  const password = form.get('password');
  if (typeof password !== 'string' || !password) return 'Enter your password.';
  const response = await fetch(`${API_URL}/api/auth/login`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ password }),
    // nginx's visitor address, so the API limits guessing per visitor instead of for the whole site.
    headers: { 'content-type': 'application/json', 'x-real-ip': (await headers()).get('x-real-ip') ?? '' },
  });
  if (response.status === 429) return 'Too many attempts. Wait a minute and try again.';
  if (!response.ok) return 'That password is not right.';
  const { token, expiresAt } = (await response.json()) as { token: string; expiresAt: number };
  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, secure: true, sameSite: 'strict', path: '/', expires: new Date(expiresAt) });
  redirect('/');
}

export async function logout() {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  // Revoke server-side too: the cookie alone would leave a copied token valid until it expires.
  if (token) await fetch(`${API_URL}/api/auth/logout`, { method: 'POST', headers: { authorization: `Bearer ${token}` }, cache: 'no-store' }).catch(() => undefined);
  jar.delete(SESSION_COOKIE);
  redirect('/login');
}
