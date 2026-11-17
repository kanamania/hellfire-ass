import { cookies } from 'next/headers';
import { API_URL, SESSION_COOKIE } from '@/lib/api';

const ALLOWED = new Set(['state', 'salt', 'blobs', 'records', 'changes', 'search', 'search-opt-in']);

async function proxy(request: Request, path: string[], method: string) {
  if (!path.length || !ALLOWED.has(path[0])) return new Response('not found', { status: 404 });
  const header = request.headers.get('authorization');
  const token = header?.startsWith('Bearer ') ? header.slice(7) : (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return new Response('unauthorized', { status: 401 });
  const url = new URL(request.url);
  const target = `${API_URL}/api/library/${path.map(encodeURIComponent).join('/')}${url.search}`;
  const body = method === 'GET' || method === 'HEAD' ? undefined : await request.arrayBuffer();
  const response = await fetch(target, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      ...(body && body.byteLength ? { 'content-type': request.headers.get('content-type') ?? 'application/json' } : {}),
    },
    body,
    cache: 'no-store',
  });
  return new Response(await response.arrayBuffer(), {
    status: response.status,
    headers: { 'content-type': response.headers.get('content-type') ?? 'application/json', 'cache-control': 'no-store' },
  });
}

export async function GET(request: Request, ctx: { params: Promise<{ path: string[] }> }) {
  return proxy(request, (await ctx.params).path, 'GET');
}

export async function POST(request: Request, ctx: { params: Promise<{ path: string[] }> }) {
  return proxy(request, (await ctx.params).path, 'POST');
}
