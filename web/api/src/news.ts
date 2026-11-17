import { BadRequestException, Body, Controller, Get, HttpCode, Injectable, NotFoundException, Param, Post, Query, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { SessionGuard, SyncGuard } from './auth.js';
import { Db } from './db.js';
import { integer, text, type Params } from './view.js';

/**
 * News views and the one web → phone channel. The phone stays the owner of news state: the web records the owner's
 * choices as actions, the phone pulls, applies (last writer wins per field) and acknowledges them, then pushes its
 * merged state. Until then, the views overlay pending actions on the phone's state so the web reflects them at once.
 */

const FLAGS = ['bookmarked', 'readLater', 'hidden', 'followed'] as const;
const FIELDS = new Set<string>([...FLAGS, 'seen', 'position']);
const LEVELS = new Set(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']);
const PULL_LIMIT = 500;

export interface NewsAction { seq: number; at: number; target: string; field: string; value: any }
type Data = Record<string, any>;

export interface NewsState {
  eventId: string; deliveredVersion: number; seenVersion: number; readAt: number | null;
  bookmarked: boolean; readLater: boolean; hidden: boolean; followed: boolean; updatedAt: number;
  /** A web choice the phone has not confirmed yet. */
  pending: boolean;
}
export interface NewsProgress { briefingId: string; position: number; audioMs: number; updatedAt: number; pending: boolean }

const count = (value: unknown) => (Number.isSafeInteger(value) ? (value as number) : 0);
const isCount = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;

export function parseAction(body: unknown): Omit<NewsAction, 'seq' | 'at'> {
  const fail = (why: string): never => { throw new BadRequestException(why); };
  if (!body || typeof body !== 'object' || Array.isArray(body)) fail('body must be an object');
  const { target, field, value } = body as Record<string, unknown>;
  if (typeof field !== 'string' || !FIELDS.has(field)) fail('unknown field');
  if (typeof target !== 'string' || !target.length || target.length > 300) fail('target must be an id of at most 300 characters');
  if (field === 'seen') {
    if (!isCount(value) || value > 1_000_000) fail('seen takes a version number');
    return { target: target as string, field, value };
  }
  if (field === 'position') {
    const { position, audioMs } = (value ?? {}) as Record<string, unknown>;
    if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some((key) => key !== 'position' && key !== 'audioMs')
      || !isCount(position) || !isCount(audioMs)) fail('position takes {position, audioMs}');
    return { target: target as string, field, value: { position, audioMs } };
  }
  if (typeof value !== 'boolean') fail(`${field} takes true or false`);
  return { target: target as string, field: field as string, value };
}

/** The phone's state for one event with the pending web actions laid over it; same rules as the phone (NewsSync.kt). */
export function effectiveState(eventId: string, phone: Data | undefined, actions: NewsAction[]): NewsState {
  const state: NewsState = {
    eventId, deliveredVersion: count(phone?.deliveredVersion), seenVersion: count(phone?.seenVersion), readAt: phone?.readAt ?? null,
    bookmarked: !!phone?.bookmarked, readLater: !!phone?.readLater, hidden: !!phone?.hidden, followed: !!phone?.followed,
    updatedAt: count(phone?.updatedAt), pending: false,
  };
  const times: Data = phone?.fieldTimes && typeof phone.fieldTimes === 'object' ? { ...phone.fieldTimes } : {};
  for (const action of [...actions].sort((a, b) => a.seq - b.seq)) {
    if (action.target !== eventId) continue;
    if (action.field === 'seen') {
      // Reading only grows, whoever wrote last.
      if (action.value > state.seenVersion) { state.seenVersion = action.value; state.readAt = action.at; state.pending = true; }
      state.deliveredVersion = Math.max(state.deliveredVersion, action.value);
    } else if ((FLAGS as readonly string[]).includes(action.field)) {
      const field = action.field as (typeof FLAGS)[number];
      if (action.at > count(times[field] ?? state.updatedAt)) {
        state[field] = action.value === true;
        times[field] = action.at;
        state.pending = true;
      }
    }
  }
  return state;
}

export function effectiveProgress(briefingId: string, phone: Data | undefined, actions: NewsAction[]): NewsProgress {
  const progress: NewsProgress = { briefingId, position: count(phone?.position), audioMs: count(phone?.audioMs), updatedAt: count(phone?.updatedAt), pending: false };
  for (const action of [...actions].sort((a, b) => a.seq - b.seq)) {
    if (action.target !== briefingId || action.field !== 'position' || action.at <= progress.updatedAt) continue;
    Object.assign(progress, { position: action.value.position, audioMs: action.value.audioMs, updatedAt: action.at, pending: true });
  }
  return progress;
}

@Injectable()
export class NewsService {
  constructor(private readonly db: Db) {}

  private async data(sql: string, values: unknown[]): Promise<Data[]> {
    return (await this.db.pool.query<{ data: Data }>(sql, values)).rows.map((row) => row.data);
  }

  private async actions(targets: string[], position: boolean): Promise<NewsAction[]> {
    if (!targets.length) return [];
    const { rows } = await this.db.pool.query(
      `SELECT seq, at, target, field, value FROM news_action WHERE target = ANY($1::text[]) AND (field = 'position') = $2 ORDER BY seq`, [targets, position]);
    return rows.map((row) => ({ ...row, seq: Number(row.seq), at: Number(row.at) }));
  }

  async states(ids: string[]): Promise<Map<string, NewsState>> {
    const unique = [...new Set(ids)];
    const [phone, actions] = await Promise.all([
      this.data("SELECT data FROM record WHERE kind = 'news_state' AND id = ANY($1::text[])", [unique]),
      this.actions(unique, false),
    ]);
    const byId = new Map(phone.map((row) => [String(row.eventId), row]));
    return new Map(unique.map((id) => [id, effectiveState(id, byId.get(id), actions)]));
  }

  async progress(briefingId: string): Promise<NewsProgress> {
    const [phone, actions] = await Promise.all([
      this.data("SELECT data FROM record WHERE kind = 'news_progress' AND id = $1", [briefingId]),
      this.actions([briefingId], true),
    ]);
    return effectiveProgress(briefingId, phone[0], actions);
  }

  /** Events with their coverage and history, keyed by event id. */
  private async stories(ids: string[]) {
    const [events, articles, versions, states] = await Promise.all([
      this.data("SELECT data FROM record WHERE kind = 'news_event' AND id = ANY($1::text[])", [ids]),
      this.data("SELECT data FROM record WHERE kind = 'news_article' AND data->>'eventId' = ANY($1::text[]) ORDER BY at, id", [ids]),
      this.data("SELECT data FROM record WHERE kind = 'news_version' AND data->>'eventId' = ANY($1::text[]) ORDER BY at, id", [ids]),
      this.states(ids),
    ]);
    const event = new Map(events.map((row) => [String(row.id), row]));
    return (id: string) => ({
      event: event.get(id) ?? null,
      articles: articles.filter((row) => row.eventId === id),
      versions: versions.filter((row) => row.eventId === id).sort((a, b) => count(a.version) - count(b.version)),
      state: states.get(id)!,
    });
  }

  private async withStates(events: Data[]) {
    const states = await this.states(events.map((event) => String(event.id)));
    return events.map((event) => ({ event, state: states.get(String(event.id))! }));
  }

  async briefings(params: Params) {
    const slot = text(params, 'slot');
    const values: unknown[] = [];
    const where = ["kind = 'news_briefing'"];
    if (slot) { values.push(slot); where.push(`data->>'slot' = $${values.length}`); }
    const rows = await this.data(`SELECT data FROM record WHERE ${where.join(' AND ')} ORDER BY at DESC, id DESC LIMIT 100`, values);
    const items = (row: Data) => itemsOf(row.items);
    const states = await this.states(rows.flatMap((row) => items(row).map((item) => item.eventId)));
    return rows.map((row) => {
      const list = items(row);
      const { items: _items, ...briefing } = row;
      return {
        ...briefing,
        counts: {
          stories: list.length,
          top: list.filter((item) => item.section === 'TOP').length,
          changed: list.filter((item) => item.section === 'CHANGED').length,
          unread: list.filter((item) => (states.get(item.eventId)?.seenVersion ?? 0) < item.version).length,
        },
      };
    });
  }

  async briefing(id: string) {
    const [row] = await this.data("SELECT data FROM record WHERE kind = 'news_briefing' AND id = $1", [id]);
    if (!row) throw new NotFoundException();
    const items = itemsOf(row.items);
    const [story, progress] = await Promise.all([this.stories(items.map((item) => item.eventId)), this.progress(id)]);
    return { briefing: row, items: items.map((item) => ({ ...item, ...story(item.eventId) })), progress };
  }

  async story(id: string) {
    const story = (await this.stories([id]))(id);
    if (!story.event) throw new NotFoundException();
    return story;
  }

  async saved() {
    const { rows } = await this.db.pool.query<{ id: string }>(
      `SELECT id FROM record WHERE kind = 'news_state' AND (data->'bookmarked' = 'true'::jsonb OR data->'readLater' = 'true'::jsonb)
       UNION SELECT target FROM news_action WHERE field IN ('bookmarked', 'readLater') LIMIT 1000`);
    const states = await this.states(rows.map((row) => row.id));
    const ids = [...states.values()].filter((state) => state.bookmarked || state.readLater).map((state) => state.eventId);
    const events = await this.data("SELECT data FROM record WHERE kind = 'news_event' AND id = ANY($1::text[]) ORDER BY at DESC, id DESC", [ids]);
    return events.map((event) => ({ event, state: states.get(String(event.id))! }));
  }

  async search(params: Params) {
    const values: unknown[] = [];
    const where = ["kind = 'news_event'"];
    const add = (sql: (n: string) => string, value: unknown) => { values.push(value); where.push(sql(`$${values.length}`)); };
    const q = text(params, 'q');
    if (q !== undefined) add((n) => `data::text ILIKE ${n}`, `%${q.replace(/[\\%_]/g, '\\$&')}%`);
    const from = integer(params, 'from');
    if (from !== undefined) add((n) => `at >= ${n}`, from);
    const to = integer(params, 'to');
    if (to !== undefined) add((n) => `at < ${n}`, to);
    const levels = [params.level].flat().filter((value) => value !== undefined && value !== '');
    if (levels.length) {
      if (!levels.every((level) => typeof level === 'string' && LEVELS.has(level))) throw new BadRequestException('invalid level');
      add((n) => `data->>'level' = ANY(${n}::text[])`, levels);
    }
    return this.withStates(await this.data(`SELECT data FROM record WHERE ${where.join(' AND ')} ORDER BY at DESC, id DESC LIMIT 100`, values));
  }

  async addAction(body: unknown) {
    const action = parseAction(body);
    const at = Date.now();
    const { rows } = await this.db.pool.query<{ seq: string }>(
      'INSERT INTO news_action (at, target, field, value) VALUES ($1, $2, $3, $4) RETURNING seq', [at, action.target, action.field, JSON.stringify(action.value)]);
    return { seq: Number(rows[0].seq), at };
  }

  async pending(after: unknown) {
    const cursor = after === undefined || after === '' ? 0 : typeof after === 'string' && /^\d{1,16}$/.test(after) ? Number(after) : null;
    if (cursor === null) throw new BadRequestException('invalid after');
    const { rows } = await this.db.pool.query('SELECT seq, at, target, field, value FROM news_action WHERE seq > $1 ORDER BY seq LIMIT $2', [cursor, PULL_LIMIT]);
    return { actions: rows.map((row) => ({ ...row, seq: Number(row.seq), at: Number(row.at) })) };
  }

  async ack(body: unknown) {
    const upTo = (body as { upTo?: unknown } | null)?.upTo;
    if (!isCount(upTo)) throw new BadRequestException('upTo must be a sequence number');
    const { rowCount } = await this.db.pool.query('DELETE FROM news_action WHERE seq <= $1', [upTo]);
    return { deleted: rowCount ?? 0 };
  }
}

/** Briefing items as the phone wrote them ({eventId, version, section, classification?}), tolerating snake_case. */
function itemsOf(raw: unknown): { eventId: string; version: number; section: string; classification: string | null }[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((item) => {
    const eventId = item?.eventId ?? item?.event_id;
    if (typeof eventId !== 'string') return [];
    return [{ eventId, version: count(item.version), section: String(item.section ?? 'STORY'), classification: item.classification ?? null }];
  });
}

@Controller('api/news')
@UseGuards(SessionGuard)
export class NewsController {
  constructor(private readonly news: NewsService) {}

  @Get('briefings') briefings(@Query() params: Params) { return this.news.briefings(params); }
  @Get('briefing/:id') briefing(@Param('id') id: string) { return this.news.briefing(id); }
  @Get('story/:id') story(@Param('id') id: string) { return this.news.story(id); }
  @Get('saved') saved() { return this.news.saved(); }
  @Get('search') search(@Query() params: Params) { return this.news.search(params); }

  @Post('action') @HttpCode(200) @Throttle({ default: { limit: 120, ttl: 60_000 } })
  action(@Body() body: unknown) { return this.news.addAction(body); }
}

@Controller('api/sync/news-actions')
@UseGuards(SyncGuard)
export class NewsSyncController {
  constructor(private readonly news: NewsService) {}

  @Get() pending(@Query('after') after: unknown) { return this.news.pending(after); }
  @Post('ack') @HttpCode(200) ack(@Body() body: unknown) { return this.news.ack(body); }
}
