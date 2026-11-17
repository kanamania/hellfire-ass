import { BadRequestException, Body, Controller, ForbiddenException, Get, HttpCode, Inject, Injectable, Param, PayloadTooLargeException, Post, Query, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { createHash, randomUUID } from 'node:crypto';
import { ClientGuard } from './auth.js';
import { CONFIG, type Config } from './config.js';
import { Db } from './db.js';

export const LIBRARY_TYPES = ['media_item', 'media_analysis', 'media_segment', 'media_bookmark', 'media_category', 'media_item_category',
  'media_item_tag', 'media_collection', 'collection_member', 'inbox_item', 'inbox_member', 'drawer_message',
  'saved_location', 'action_record', 'transcript_segment'] as const;
const TYPE_SET = new Set<string>(LIBRARY_TYPES);
const ID = /^[A-Za-z0-9_.:-]{1,80}$/;
const HASH = /^[0-9a-f]{64}$/;
const ENVELOPE_OVERHEAD = 29;
const MAX_CHUNK = 256 * 1024 + ENVELOPE_OVERHEAD;
const MAX_RECORDS = 50;

function fail(why: string): never { throw new BadRequestException(why); }
function id(value: unknown, name: string): string {
  if (typeof value !== 'string' || !ID.test(value)) fail(`invalid ${name}`);
  return value;
}
function sha256(bytes: Buffer): string { return createHash('sha256').update(bytes).digest('hex'); }

interface Incoming {
  entityType: string; entityId: string; baseVersion: number; deleted: boolean; updatedAt: number;
  deviceId: string; envelope: Record<string, unknown>; searchText?: string;
}

function parseRecords(body: unknown): Incoming[] {
  const rows = (body as { records?: unknown } | null)?.records;
  if (!Array.isArray(rows) || rows.length > MAX_RECORDS) fail('records must be an array of at most 50');
  return rows.map((row) => {
    const item = (row ?? {}) as Record<string, unknown>;
    const entityType = id(item.entityType, 'entityType');
    if (!TYPE_SET.has(entityType)) fail('unknown entity');
    const envelope = item.envelope;
    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) fail('envelope must be an object');
    const packed = JSON.stringify(envelope);
    if (packed.length > 300_000) fail('envelope too large');
    if (typeof item.baseVersion !== 'number' || !Number.isSafeInteger(item.baseVersion) || item.baseVersion < 0) fail('baseVersion must be an integer');
    if (typeof item.updatedAt !== 'number' || !Number.isSafeInteger(item.updatedAt)) fail('updatedAt must be an integer');
    if (typeof item.deleted !== 'boolean') fail('deleted must be boolean');
    const searchText = item.searchText === undefined ? undefined : item.searchText;
    if (searchText !== undefined && (typeof searchText !== 'string' || searchText.length > 4_000)) fail('invalid searchText');
    return {
      entityType, entityId: id(item.entityId, 'entityId'), baseVersion: item.baseVersion as number,
      deleted: item.deleted as boolean, updatedAt: item.updatedAt as number, deviceId: id(item.deviceId, 'deviceId'),
      envelope: envelope as Record<string, unknown>, searchText,
    };
  });
}

@Injectable()
export class LibraryService {
  constructor(private readonly db: Db, @Inject(CONFIG) private readonly config: Config) {}

  async state() {
    const meta = await this.db.pool.query<{ key: string; value: string }>("SELECT key, value FROM library_meta WHERE key IN ('epoch', 'kdf_salt', 'search_plaintext')");
    const map = Object.fromEntries(meta.rows.map((row) => [row.key, row.value]));
    const used = await this.db.pool.query<{ used: string }>("SELECT COALESCE(SUM(size + chunk_count::bigint * 29), 0)::text AS used FROM library_blob");
    const counts = await this.db.pool.query<{ items: string; tombstones: string }>(
      "SELECT count(*) FILTER (WHERE NOT deleted)::text AS items, count(*) FILTER (WHERE deleted)::text AS tombstones FROM library_record");
    const cursor = await this.db.pool.query<{ cursor: string }>("SELECT COALESCE(MAX(change_seq), 0)::text AS cursor FROM library_record");
    return {
      epoch: map.epoch ?? null,
      salt: map.kdf_salt ?? null,
      searchPlaintext: map.search_plaintext === '1',
      used: Number(used.rows[0].used),
      quota: this.config.quotaBytes,
      items: Number(counts.rows[0].items),
      tombstones: Number(counts.rows[0].tombstones),
      cursor: Number(cursor.rows[0].cursor),
    };
  }

  async setSalt(body: unknown) {
    const salt = (body as { salt?: unknown } | null)?.salt;
    if (typeof salt !== 'string' || Buffer.from(salt, 'base64').length !== 16) fail('salt must be 16 bytes');
    await this.db.pool.query("INSERT INTO library_meta (key, value) VALUES ('kdf_salt', $1) ON CONFLICT (key) DO NOTHING", [salt]);
    const { rows } = await this.db.pool.query<{ value: string }>("SELECT value FROM library_meta WHERE key = 'kdf_salt'", []);
    return { salt: rows[0].value };
  }

  async openBlob(body: unknown) {
    const item = (body ?? {}) as Record<string, unknown>;
    const contentHash = item.contentHash;
    const size = item.size;
    const chunkSize = item.chunkSize;
    const chunkCount = item.chunkCount;
    if (typeof contentHash !== 'string' || !HASH.test(contentHash)) fail('contentHash must be sha256 hex');
    if (typeof size !== 'number' || !Number.isSafeInteger(size) || size < 1 || size > this.config.quotaBytes) fail('invalid size');
    if (typeof chunkSize !== 'number' || !Number.isSafeInteger(chunkSize) || chunkSize < 1 || chunkSize > 256 * 1024) fail('invalid chunkSize');
    if (typeof chunkCount !== 'number' || !Number.isSafeInteger(chunkCount) || chunkCount < 1 || chunkCount > 200_000) fail('invalid chunkCount');
    if (chunkCount !== Math.ceil(size / chunkSize)) fail('chunkCount does not match size');
    return this.db.transaction(async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('library-quota'))");
      const existing = await client.query<{ id: string; size: string; chunk_count: number; chunk_size: number }>(
        'SELECT id, size::text, chunk_count, chunk_size FROM library_blob WHERE content_hash = $1', [contentHash]);
      if (existing.rows[0]) {
        if (Number(existing.rows[0].size) !== size || existing.rows[0].chunk_size !== chunkSize || existing.rows[0].chunk_count !== chunkCount) fail('blob dimensions do not match');
        const received = await client.query<{ idx: number }>('SELECT idx FROM library_chunk WHERE blob_id = $1 ORDER BY idx', [existing.rows[0].id]);
        const count = Number(existing.rows[0].chunk_count);
        return { blobId: existing.rows[0].id, contentHash, received: received.rows.map((row) => row.idx), complete: received.rows.length === count };
      }
      const used = await client.query<{ used: string }>('SELECT COALESCE(SUM(size + chunk_count::bigint * 29), 0)::text AS used FROM library_blob');
      if (Number(used.rows[0].used) + size + chunkCount * ENVELOPE_OVERHEAD > this.config.quotaBytes) throw new PayloadTooLargeException('quota');
      const id = randomUUID();
      await client.query(
        'INSERT INTO library_blob (id, content_hash, size, chunk_size, chunk_count) VALUES ($1, $2, $3, $4, $5)',
        [id, contentHash, size, chunkSize, chunkCount]);
      return { blobId: id, contentHash, received: [] as number[], complete: false };
    });
  }

  async putChunk(blobId: string, indexRaw: string, body: unknown) {
    const id = idOfBlob(blobId);
    const index = Number(indexRaw);
    if (!Number.isSafeInteger(index) || index < 0) fail('invalid chunk');
    const item = (body ?? {}) as Record<string, unknown>;
    if (typeof item.sha256 !== 'string' || !HASH.test(item.sha256)) fail('sha256 required');
    if (typeof item.bytes !== 'string') fail('bytes required');
    const bytes = Buffer.from(item.bytes, 'base64');
    if (bytes.length < 29 || bytes.length > MAX_CHUNK) fail('chunk size');
    if (sha256(bytes) !== item.sha256) fail('chunk hash mismatch');
    return this.db.transaction(async (client) => {
      const blob = await client.query<{ chunk_count: number; chunk_size: number; size: string }>('SELECT chunk_count, chunk_size, size::text FROM library_blob WHERE id = $1 FOR UPDATE', [id]);
      if (!blob.rows[0]) fail('unknown blob');
      if (index >= blob.rows[0].chunk_count) fail('chunk out of range');
      const plainSize = Math.min(blob.rows[0].chunk_size, Number(blob.rows[0].size) - index * blob.rows[0].chunk_size);
      if (bytes.length !== plainSize + ENVELOPE_OVERHEAD) fail('chunk size does not match blob');
      const prior = await client.query<{ sha256: string }>('SELECT sha256 FROM library_chunk WHERE blob_id = $1 AND idx = $2', [id, index]);
      if (prior.rows[0] && prior.rows[0].sha256 !== item.sha256) fail('chunk already stored with a different hash');
      if (!prior.rows[0]) {
        await client.query('INSERT INTO library_chunk (blob_id, idx, sha256, bytes) VALUES ($1, $2, $3, $4)', [id, index, item.sha256, bytes]);
      }
      const received = await client.query<{ n: string }>('SELECT count(*)::text AS n FROM library_chunk WHERE blob_id = $1', [id]);
      const complete = Number(received.rows[0].n) === blob.rows[0].chunk_count;
      return { sha256: item.sha256, received: Number(received.rows[0].n), complete };
    });
  }

  async blob(blobId: string) {
    const id = idOfBlob(blobId);
    const { rows } = await this.db.pool.query<{ content_hash: string; size: string; chunk_count: number }>(
      'SELECT content_hash, size::text, chunk_count FROM library_blob WHERE id = $1', [id]);
    if (!rows[0]) fail('unknown blob');
    const received = await this.db.pool.query<{ idx: number }>('SELECT idx FROM library_chunk WHERE blob_id = $1 ORDER BY idx', [id]);
    return { blobId: id, contentHash: rows[0].content_hash, size: Number(rows[0].size), received: received.rows.map((row) => row.idx), complete: received.rows.length === rows[0].chunk_count };
  }

  async chunk(blobId: string, indexRaw: string) {
    const id = idOfBlob(blobId);
    const index = Number(indexRaw);
    if (!Number.isSafeInteger(index) || index < 0) fail('invalid chunk');
    const { rows } = await this.db.pool.query<{ sha256: string; bytes: Buffer }>('SELECT sha256, bytes FROM library_chunk WHERE blob_id = $1 AND idx = $2', [id, index]);
    if (!rows[0]) fail('missing chunk');
    return { sha256: rows[0].sha256, bytes: Buffer.from(rows[0].bytes).toString('base64') };
  }

  async saveRecords(body: unknown) {
    const records = parseRecords(body);
    return this.db.transaction(async (client) => {
      // Serialize writers so change cursors follow commit order, including first inserts.
      await client.query("SELECT pg_advisory_xact_lock(hashtext('library-records'))");
      const opt = await client.query<{ value: string }>("SELECT value FROM library_meta WHERE key = 'search_plaintext'");
      const searchOn = opt.rows[0]?.value === '1';
      const accepted: { entityType: string; entityId: string; version: number }[] = [];
      const conflicts: Record<string, unknown>[] = [];
      for (const row of records) {
        if (row.searchText && !searchOn) fail('plaintext search is off');
        const current = await client.query<{ version: string; deleted: boolean; updated_at: string; device_id: string; envelope: unknown }>(
          'SELECT version::text, deleted, updated_at::text, device_id, envelope FROM library_record WHERE entity_type = $1 AND entity_id = $2 FOR UPDATE',
          [row.entityType, row.entityId]);
        const existing = current.rows[0];
        if (!existing && row.baseVersion !== 0) fail('missing base record');
        if (existing && Number(existing.version) !== row.baseVersion) {
          conflicts.push({ entityType: row.entityType, entityId: row.entityId, version: Number(existing.version), deleted: existing.deleted, updatedAt: Number(existing.updated_at), deviceId: existing.device_id, envelope: existing.envelope });
          continue;
        }
        const version = (existing ? Number(existing.version) : 0) + 1;
        if (!existing) {
          await client.query(
            `INSERT INTO library_record (entity_type, entity_id, version, deleted, updated_at, device_id, envelope)
             VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)`,
            [row.entityType, row.entityId, version, row.deleted, row.updatedAt, row.deviceId, JSON.stringify(row.envelope)]);
        } else {
          await client.query(
            `UPDATE library_record SET version = $3, deleted = $4, updated_at = $5, device_id = $6, envelope = $7::jsonb, change_seq = nextval('library_change_seq')
             WHERE entity_type = $1 AND entity_id = $2`,
            [row.entityType, row.entityId, version, row.deleted, row.updatedAt, row.deviceId, JSON.stringify(row.envelope)]);
        }
        if (searchOn && (row.deleted || row.searchText !== undefined)) {
          if (row.deleted || row.searchText === '') {
            await client.query('DELETE FROM library_search WHERE entity_type = $1 AND entity_id = $2', [row.entityType, row.entityId]);
          } else {
            await client.query(
              `INSERT INTO library_search (entity_type, entity_id, text) VALUES ($1, $2, $3)
               ON CONFLICT (entity_type, entity_id) DO UPDATE SET text = excluded.text`,
              [row.entityType, row.entityId, row.searchText]);
          }
        }
        accepted.push({ entityType: row.entityType, entityId: row.entityId, version });
      }
      return { accepted, conflicts };
    });
  }

  async changes(afterRaw: string | undefined, limitRaw: string | undefined) {
    const after = afterRaw === undefined ? 0 : Number(afterRaw);
    const limit = limitRaw === undefined ? 100 : Number(limitRaw);
    if (!Number.isSafeInteger(after) || after < 0) fail('invalid after');
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) fail('invalid limit');
    const { rows } = await this.db.pool.query(
      `SELECT change_seq::text AS "changeSeq", entity_type AS "entityType", entity_id AS "entityId", version::text,
              deleted, updated_at::text AS "updatedAt", device_id AS "deviceId", envelope
       FROM library_record WHERE change_seq > $1 ORDER BY change_seq LIMIT $2`, [after, limit]);
    return { records: rows.map((row) => ({ ...row, changeSeq: Number(row.changeSeq), version: Number(row.version), updatedAt: Number(row.updatedAt) })) };
  }

  async setSearch(body: unknown) {
    const enabled = (body as { enabled?: unknown } | null)?.enabled;
    if (typeof enabled !== 'boolean') fail('enabled must be boolean');
    await this.db.transaction(async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('library-records'))");
      await client.query(
        `INSERT INTO library_meta (key, value) VALUES ('search_plaintext', $1)
         ON CONFLICT (key) DO UPDATE SET value = excluded.value`, [enabled ? '1' : '0']);
      if (!enabled) await client.query('DELETE FROM library_search');
    });
    return { searchPlaintext: enabled };
  }

  async search(q: string | undefined) {
    const flag = await this.db.pool.query<{ value: string }>("SELECT value FROM library_meta WHERE key = 'search_plaintext'");
    if (flag.rows[0]?.value !== '1') throw new ForbiddenException('plaintext search is off');
    if (!q || q.length > 200) fail('q is required');
    const { rows } = await this.db.pool.query(
      `SELECT entity_type AS "entityType", entity_id AS "entityId", text FROM library_search
       WHERE text ILIKE $1 ESCAPE '\\' ORDER BY entity_type, entity_id LIMIT 50`, [`%${q.replace(/[\\%_]/g, '\\$&')}%`]);
    return { rows };
  }
}

function idOfBlob(value: string): string {
  if (!ID.test(value)) fail('invalid blob');
  return value;
}

@Controller('api/library')
@UseGuards(ClientGuard)
@Throttle({ default: { limit: 300, ttl: 60_000 } })
export class LibraryController {
  constructor(private readonly library: LibraryService) {}

  @Get('state') state() { return this.library.state(); }
  @Post('salt') @HttpCode(200) salt(@Body() body: unknown) { return this.library.setSalt(body); }
  @Post('blobs') @HttpCode(200) blobs(@Body() body: unknown) { return this.library.openBlob(body); }
  @Post('blobs/:id/chunks/:index') @HttpCode(200) putChunk(@Param('id') id: string, @Param('index') index: string, @Body() body: unknown) {
    return this.library.putChunk(id, index, body);
  }
  @Get('blobs/:id') blob(@Param('id') id: string) { return this.library.blob(id); }
  @Get('blobs/:id/chunks/:index') chunk(@Param('id') id: string, @Param('index') index: string) { return this.library.chunk(id, index); }
  @Post('records') @HttpCode(200) records(@Body() body: unknown) { return this.library.saveRecords(body); }
  @Get('changes') changes(@Query('after') after?: string, @Query('limit') limit?: string) { return this.library.changes(after, limit); }
  @Post('search-opt-in') @HttpCode(200) @Throttle({ default: { limit: 10, ttl: 60_000 } }) optIn(@Body() body: unknown) { return this.library.setSearch(body); }
  @Get('search') search(@Query('q') q?: string) { return this.library.search(q); }
}
