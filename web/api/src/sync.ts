import { BadRequestException, Body, ConflictException, Controller, Get, HttpCode, Injectable, Post, UseGuards } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { SyncGuard } from './auth.js';
import { Db } from './db.js';

/** Kinds the phone may push. Each is a projection the phone builds; the server never derives them. */
export const KINDS = ['account', 'ledger', 'candidate', 'balance', 'task', 'message', 'draft',
  'media', 'transcript', 'memory', 'event', 'summary',
  'news_event', 'news_version', 'news_article', 'news_briefing', 'news_state', 'news_progress', 'news_source'] as const;
const KIND_SET = new Set<string>(KINDS);
const MAX_ROWS = 500;

interface Row { id: string; at: number; data: Record<string, unknown> }
export interface Batch { dataset: string; kind: string; upserts: Row[]; deletes: string[] }

function parseBatch(body: unknown): Batch {
  const fail = (why: string): never => { throw new BadRequestException(why); };
  if (!body || typeof body !== 'object') fail('body must be an object');
  const { dataset, kind, upserts = [], deletes = [] } = body as Record<string, unknown>;
  if (typeof dataset !== 'string') fail('dataset is required');
  if (typeof kind !== 'string' || !KIND_SET.has(kind)) fail('unknown kind');
  if (!Array.isArray(upserts) || !Array.isArray(deletes)) fail('upserts and deletes must be arrays');
  const rows = upserts as unknown[];
  const removed = deletes as unknown[];
  if (rows.length + removed.length > MAX_ROWS) fail(`at most ${MAX_ROWS} rows per batch`);
  const ids = new Set<string>();
  const validId = (id: unknown): id is string => typeof id === 'string' && id.length > 0 && id.length <= 300;
  for (const row of rows) {
    const { id, at, data } = (row ?? {}) as Record<string, unknown>;
    if (!validId(id) || ids.has(id)) fail('each upsert needs a unique id');
    if (!Number.isSafeInteger(at)) fail('at must be an integer');
    if (!data || typeof data !== 'object' || Array.isArray(data)) fail('data must be an object');
    ids.add(id as string);
  }
  if (!removed.every(validId)) fail('deletes must be ids');
  return { dataset: dataset as string, kind: kind as string, upserts: rows as Row[], deletes: removed as string[] };
}

@Injectable()
export class SyncService {
  constructor(private readonly db: Db) {}

  async state() {
    const { rows } = await this.db.pool.query<{ key: string; value: string }>("SELECT key, value FROM meta WHERE key IN ('dataset', 'last_sync_at')");
    const meta = Object.fromEntries(rows.map((row) => [row.key, row.value]));
    return { dataset: meta.dataset, lastSyncAt: meta.last_sync_at ? Number(meta.last_sync_at) : null };
  }

  /** Drops the whole mirror and starts a new dataset; the phone then uploads everything again. */
  async reset() {
    const dataset = randomUUID();
    await this.db.transaction(async (client) => {
      await client.query("UPDATE meta SET value = $1 WHERE key = 'dataset'", [dataset]);
      await client.query("DELETE FROM meta WHERE key = 'last_sync_at'");
      await client.query('DELETE FROM record');
      await client.query('DELETE FROM news_action');
    });
    return { dataset };
  }

  async apply(body: unknown) {
    const batch = parseBatch(body);
    return this.db.transaction(async (client) => {
      // FOR SHARE blocks a concurrent reset until this batch commits, and vice versa.
      const { rows } = await client.query<{ value: string }>("SELECT value FROM meta WHERE key = 'dataset' FOR SHARE");
      if (rows[0]?.value !== batch.dataset) throw new ConflictException('dataset changed; start a full sync');
      if (batch.upserts.length) {
        await client.query(
          `INSERT INTO record (kind, id, at, data)
           SELECT $1, u.id, u.at, u.data FROM unnest($2::text[], $3::bigint[], $4::jsonb[]) AS u(id, at, data)
           ON CONFLICT (kind, id) DO UPDATE SET at = excluded.at, data = excluded.data, synced_at = now()`,
          [batch.kind, batch.upserts.map((r) => r.id), batch.upserts.map((r) => r.at), batch.upserts.map((r) => JSON.stringify(r.data))],
        );
      }
      if (batch.deletes.length) await client.query('DELETE FROM record WHERE kind = $1 AND id = ANY($2::text[])', [batch.kind, batch.deletes]);
      return { upserted: batch.upserts.length, deleted: batch.deletes.length };
    });
  }

  async finish(body: unknown) {
    const dataset = (body as { dataset?: unknown } | null)?.dataset;
    const at = Date.now();
    const { rowCount } = await this.db.pool.query(
      `INSERT INTO meta (key, value) SELECT 'last_sync_at', $2 WHERE EXISTS (SELECT 1 FROM meta WHERE key = 'dataset' AND value = $1)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value`, [dataset, String(at)]);
    if (!rowCount) throw new ConflictException('dataset changed; start a full sync');
    return { lastSyncAt: at };
  }
}

@Controller('api/sync')
@UseGuards(SyncGuard)
export class SyncController {
  constructor(private readonly sync: SyncService) {}

  @Get('state') state() { return this.sync.state(); }
  @Post('reset') @HttpCode(200) reset() { return this.sync.reset(); }
  @Post('batch') @HttpCode(200) batch(@Body() body: unknown) { return this.sync.apply(body); }
  @Post('finish') @HttpCode(200) finish(@Body() body: unknown) { return this.sync.finish(body); }
}
