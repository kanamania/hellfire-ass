'use server';

import { cookies } from 'next/headers';
import { API_URL, SESSION_COOKIE } from '@/lib/api';

/**
 * Records one news choice (seen, bookmarked, readLater, hidden, followed or a briefing position) for the phone to pick up.
 * The API validates the field and value; this only forwards them with the owner's session.
 */
export async function newsAction(target: string, field: string, value: unknown): Promise<boolean> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return false;
  const response = await fetch(`${API_URL}/api/news/action`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ target, field, value }), cache: 'no-store',
  });
  return response.ok;
}
