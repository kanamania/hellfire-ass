'use server';

import { cookies } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { API_URL, SESSION_COOKIE } from './api';

/**
 * Asks the phone to make a reversible change (audit HF-061). The phone applies it at its next sync only if it is
 * still valid there; this site never changes the records itself.
 */
export async function askPhone(form: FormData): Promise<void> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const kind = String(form.get('kind') ?? '');
  const target = String(form.get('target') ?? '');
  const back = String(form.get('back') ?? '/');
  if (token) await fetch(`${API_URL}/api/commands`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ kind, target }), cache: 'no-store',
  });
  revalidatePath(back.startsWith('/') ? back : '/');
}
