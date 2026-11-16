import { cookies } from 'next/headers';
import { notFound, redirect } from 'next/navigation';

export const SESSION_COOKIE = 'hf_session';
export const API_URL = process.env.API_URL ?? 'http://localhost:3001';

export interface Row<T = Record<string, any>> { kind: string; id: string; at: number; data: T }
export interface Page<T = Record<string, any>> { rows: Row<T>[]; next: { afterAt: number; afterId: string } | null }

/** Server-side call to the API as the signed-in owner; sends the visitor to /login when the session is missing or expired. */
export async function api<T>(path: string, query?: Record<string, string | string[] | undefined>): Promise<T> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) redirect('/login');
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {}))
    for (const item of [value].flat()) if (item) params.append(key, item);
  const response = await fetch(`${API_URL}${path}${params.size ? `?${params}` : ''}`, {
    headers: { authorization: `Bearer ${token}` }, cache: 'no-store',
  });
  if (response.status === 401) redirect('/login');
  if (response.status === 404) notFound();
  if (!response.ok) throw new Error(`API request failed (${response.status})`);
  return response.json() as Promise<T>;
}

export const records = (query: Record<string, string | string[] | undefined>) => api<Page>('/api/records', query);

/** Search params arrive as string | string[] | undefined; pages use the first value. */
export function param(params: Record<string, string | string[] | undefined>, name: string): string | undefined {
  const value = params[name];
  return (Array.isArray(value) ? value[0] : value) || undefined;
}
