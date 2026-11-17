import { BadRequestException, Body, Controller, Get, HttpCode, Inject, Injectable, NotFoundException, Param, Post, Query, UnauthorizedException, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { CONFIG, type Config } from './config.js';
import { issueSession, SessionGuard, verifyPassword } from './auth.js';
import { Db } from './db.js';
import { KINDS } from './sync.js';

/** Fields of the phone's JSON that the web may filter on with equality, per kind. */
const FILTERS: Record<string, string[]> = {
  ledger: ['accountId', 'category', 'kind'],
  candidate: ['accountId'],
  task: ['state', 'kind', 'direction'],
  message: ['channel', 'threadKey', 'direction'],
  draft: ['state', 'channel'],
  media: ['kind'],
  transcript: ['mediaId'],
  memory: ['kind'],
  event: ['source', 'label'],
  news_event: ['level', 'eventType', 'status'],
  news_article: ['eventId'],
  news_version: ['eventId'],
  news_briefing: ['slot'],
};
const LISTABLE = KINDS.filter((kind) => kind !== 'summary');
// News has its own views (news.ts); the general listing and search include it only when asked by kind.
const DEFAULT_KINDS = LISTABLE.filter((kind) => !kind.startsWith('news_'));

export interface RecordRow { kind: string; id: string; at: number; data: Record<string, any> }

export type Params = Record<string, unknown>;
export const text = (params: Params, name: string): string | undefined => {
  const value = params[name];
  if (value === undefined || value === '') return undefined;
  if (typeof value !== 'string' || value.length > 300) throw new BadRequestException(`invalid ${name}`);
  return value;
};
export const integer = (params: Params, name: string): number | undefined => {
  const value = text(params, name);
  if (value === undefined) return undefined;
  if (!/^-?\d{1,16}$/.test(value)) throw new BadRequestException(`invalid ${name}`);
  return Number(value);
};

@Injectable()
export class ViewService {
  constructor(private readonly db: Db) {}

  private async rows(sql: string, values: unknown[] = []): Promise<RecordRow[]> {
    const { rows } = await this.db.pool.query(sql, values);
    return rows.map((row) => ({ ...row, at: Number(row.at) }));
  }

  async overview(now = Date.now()) {
    const [meta, summary, counts, dueTasks, recentLedger, recentInbox] = await Promise.all([
      this.db.pool.query("SELECT value FROM meta WHERE key = 'last_sync_at'"),
      this.rows("SELECT kind, id, at, data FROM record WHERE kind = 'summary' AND id = 'current'"),
      this.db.pool.query(`SELECT
          count(*) FILTER (WHERE kind = 'candidate') AS "toConfirm",
          count(*) FILTER (WHERE kind = 'task' AND data->>'state' = 'REVIEW') AS "tasksToReview",
          count(*) FILTER (WHERE kind = 'draft' AND data->>'state' = 'DRAFT') AS "draftsWaiting"
        FROM record`),
      this.rows("SELECT kind, id, at, data FROM record WHERE kind = 'task' AND data->>'state' = 'ACTIVE' AND at < $1 ORDER BY at, id LIMIT 10", [now + 2 * 86_400_000]),
      this.rows("SELECT kind, id, at, data FROM record WHERE kind = 'ledger' ORDER BY at DESC, id DESC LIMIT 8"),
      this.rows("SELECT kind, id, at, data FROM record WHERE kind IN ('message', 'event') ORDER BY at DESC, id DESC LIMIT 8"),
    ]);
    const count = counts.rows[0];
    return {
      lastSyncAt: meta.rows[0] ? Number(meta.rows[0].value) : null,
      summary: summary[0]?.data ?? null,
      toConfirm: Number(count.toConfirm), tasksToReview: Number(count.tasksToReview), draftsWaiting: Number(count.draftsWaiting),
      dueTasks, recentLedger, recentInbox,
    };
  }

  /**
   * One listing for every page: kinds, equality filters, text search, a time range and
   * keyset paging on (at, id). Field names come from an allow-list; values are parameters.
   */
  async records(params: Params) {
    const kinds = (text(params, 'kinds') ?? DEFAULT_KINDS.join(',')).split(',');
    if (!kinds.every((kind) => (LISTABLE as readonly string[]).includes(kind))) throw new BadRequestException('unknown kind');
    const limit = Math.min(Math.max(integer(params, 'limit') ?? 50, 1), 200);
    const ascending = text(params, 'order') === 'asc';
    const values: unknown[] = [kinds];
    const where = ['kind = ANY($1::text[])'];
    const add = (sql: (n: string) => string, value: unknown) => { values.push(value); where.push(sql(`$${values.length}`)); };
    for (const field of kinds.length === 1 ? FILTERS[kinds[0]] ?? [] : []) {
      // A repeated parameter (channel=A&channel=B) matches any of its values.
      const options = [params[field]].flat().filter((value) => value !== undefined && value !== '');
      if (!options.length) continue;
      if (options.length > 20 || !options.every((value) => typeof value === 'string' && value.length <= 300)) throw new BadRequestException(`invalid ${field}`);
      add((n) => `data->>'${field}' = ANY(${n}::text[])`, options);
    }
    const q = text(params, 'q');
    if (q !== undefined) add((n) => `data::text ILIKE ${n}`, `%${q.replace(/[\\%_]/g, '\\$&')}%`);
    const from = integer(params, 'from');
    if (from !== undefined) add((n) => `at >= ${n}`, from);
    const to = integer(params, 'to');
    if (to !== undefined) add((n) => `at < ${n}`, to);
    const afterAt = integer(params, 'afterAt');
    const afterId = text(params, 'afterId');
    if (afterAt !== undefined && afterId !== undefined) {
      values.push(afterAt, afterId);
      where.push(`(at, id) ${ascending ? '>' : '<'} ($${values.length - 1}, $${values.length})`);
    }
    values.push(limit + 1);
    const direction = ascending ? 'ASC' : 'DESC';
    const rows = await this.rows(`SELECT kind, id, at, data FROM record WHERE ${where.join(' AND ')}
      ORDER BY at ${direction}, id ${direction} LIMIT $${values.length}`, values);
    const more = rows.length > limit;
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return { rows: page, next: more && last ? { afterAt: last.at, afterId: last.id } : null };
  }

  async record(kind: string, id: string) {
    const [row] = await this.rows('SELECT kind, id, at, data FROM record WHERE kind = $1 AND id = $2', [kind, id]);
    if (!row) throw new NotFoundException();
    return row;
  }
}

@Controller('api')
export class ViewController {
  constructor(private readonly view: ViewService, @Inject(CONFIG) private readonly config: Config) {}

  // Reached only from the Next.js server, which keeps the token in an HttpOnly cookie.
  @Post('auth/login') @HttpCode(200) @Throttle({ default: { limit: 5, ttl: 60_000 } })
  login(@Body() body: { password?: unknown }) {
    const password = body?.password;
    if (typeof password !== 'string' || password.length > 1024 || !verifyPassword(password, this.config.ownerPasswordHash))
      throw new UnauthorizedException();
    return issueSession(this.config.sessionSecret);
  }

  @Get('overview') @UseGuards(SessionGuard)
  overview() { return this.view.overview(); }

  @Get('records') @UseGuards(SessionGuard)
  records(@Query() params: Params) { return this.view.records(params); }

  @Get('record/:kind/:id') @UseGuards(SessionGuard)
  record(@Param('kind') kind: string, @Param('id') id: string) { return this.view.record(kind, id); }
}
