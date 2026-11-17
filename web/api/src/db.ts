import { Inject, Injectable, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import pg from 'pg';
import { CONFIG, type Config } from './config.js';

// The phone is the only writer. Every synced row is stored as the JSON the phone sent,
// keyed by kind and the phone's own id; `at` is the row's main time (epoch ms) for ordering.
const SCHEMA = `
CREATE TABLE IF NOT EXISTS record (
  kind text NOT NULL,
  id text NOT NULL,
  at bigint NOT NULL,
  data jsonb NOT NULL,
  synced_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (kind, id)
);
CREATE INDEX IF NOT EXISTS record_kind_at ON record (kind, at DESC, id DESC);
CREATE TABLE IF NOT EXISTS meta (key text PRIMARY KEY, value text NOT NULL);
INSERT INTO meta (key, value) VALUES ('dataset', gen_random_uuid()::text) ON CONFLICT (key) DO NOTHING;
CREATE SEQUENCE IF NOT EXISTS library_change_seq;
CREATE TABLE IF NOT EXISTS library_blob (
  id text PRIMARY KEY,
  content_hash text NOT NULL UNIQUE,
  size bigint NOT NULL,
  chunk_size integer NOT NULL,
  chunk_count integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS library_chunk (
  blob_id text NOT NULL REFERENCES library_blob(id) ON DELETE CASCADE,
  idx integer NOT NULL,
  sha256 text NOT NULL,
  bytes bytea NOT NULL,
  PRIMARY KEY (blob_id, idx)
);
CREATE TABLE IF NOT EXISTS library_record (
  entity_type text NOT NULL,
  entity_id text NOT NULL,
  version bigint NOT NULL,
  deleted boolean NOT NULL DEFAULT false,
  updated_at bigint NOT NULL,
  device_id text NOT NULL,
  envelope jsonb NOT NULL,
  change_seq bigint NOT NULL DEFAULT nextval('library_change_seq'),
  PRIMARY KEY (entity_type, entity_id)
);
ALTER TABLE library_record ADD COLUMN IF NOT EXISTS blob_id text;
ALTER TABLE library_blob ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS library_record_blob ON library_record (blob_id) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS library_record_change ON library_record (change_seq);
CREATE TABLE IF NOT EXISTS library_meta (key text PRIMARY KEY, value text NOT NULL);
INSERT INTO library_meta (key, value) VALUES ('epoch', gen_random_uuid()::text) ON CONFLICT (key) DO NOTHING;
CREATE TABLE IF NOT EXISTS library_search (
  entity_type text NOT NULL,
  entity_id text NOT NULL,
  text text NOT NULL,
  PRIMARY KEY (entity_type, entity_id)
);
DELETE FROM library_search;
DELETE FROM library_meta WHERE key = 'search_plaintext';
CREATE INDEX IF NOT EXISTS record_news_event ON record ((data->>'eventId')) WHERE kind IN ('news_article', 'news_version');
CREATE TABLE IF NOT EXISTS news_action (
  seq bigserial PRIMARY KEY,
  at bigint NOT NULL,
  target text NOT NULL,
  field text NOT NULL,
  value jsonb NOT NULL
);
CREATE INDEX IF NOT EXISTS news_action_target ON news_action (target);
`;

@Injectable()
export class Db implements OnModuleInit, OnModuleDestroy {
  readonly pool: pg.Pool;

  constructor(@Inject(CONFIG) config: Config) {
    this.pool = new pg.Pool({ connectionString: config.databaseUrl, max: 10 });
  }

  async onModuleInit() {
    await this.pool.query(SCHEMA);
  }

  async onModuleDestroy() {
    await this.pool.end();
  }

  async transaction<T>(work: (client: pg.PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await work(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }
}
