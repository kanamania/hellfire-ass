// Runs against a real, disposable Postgres: TEST_DATABASE_URL must point at an empty database.
import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { after, before, beforeEach, describe, it } from 'node:test';
import pg from 'pg';
import { createApp } from '../src/app.js';
import { hashPassword, issueSession, validSession, verifyPassword } from '../src/auth.js';
import type { Config } from '../src/config.js';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (!databaseUrl) throw new Error('Set TEST_DATABASE_URL to a disposable Postgres database');

const config: Config = {
  databaseUrl,
  syncToken: 'sync-token-for-tests-only-0123456789',
  sessionSecret: 'session-secret-for-tests-only-012345',
  ownerPasswordHash: hashPassword('correct horse battery'),
  port: 0,
  host: '127.0.0.1',
  quotaBytes: 1000,
};

let base = '';
let close: () => Promise<void>;
let session = '';

async function call(path: string, init: { method?: string; token?: string; body?: unknown } = {}) {
  const response = await fetch(base + path, {
    method: init.method ?? (init.body === undefined ? 'GET' : 'POST'),
    headers: { 'content-type': 'application/json', ...(init.token ? { authorization: `Bearer ${init.token}` } : {}) },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}
const phone = (path: string, body?: unknown) => call(path, { token: config.syncToken, body });
const owner = (path: string) => call(path, { token: session });

before(async () => {
  const admin = new pg.Client({ connectionString: databaseUrl });
  await admin.connect();
  await admin.query('DROP TABLE IF EXISTS library_chunk, library_blob, library_record, library_search, library_meta, record, meta CASCADE');
  await admin.query('DROP SEQUENCE IF EXISTS library_change_seq');
  await admin.end();
  const app = await createApp(config);
  await app.listen(0, '127.0.0.1');
  base = await app.getUrl();
  close = () => app.close();
  session = issueSession(config.sessionSecret).token;
});
after(() => close());

describe('passwords and sessions', () => {
  it('verifies only the right password', () => {
    assert.equal(verifyPassword('correct horse battery', config.ownerPasswordHash), true);
    assert.equal(verifyPassword('wrong horse battery', config.ownerPasswordHash), false);
    assert.equal(verifyPassword('anything', 'plain-text'), false);
  });
  it('rejects expired, forged and malformed sessions', () => {
    const { token, expiresAt } = issueSession(config.sessionSecret, 1_000);
    assert.equal(validSession(config.sessionSecret, token, expiresAt - 1), true);
    assert.equal(validSession(config.sessionSecret, token, expiresAt), false);
    assert.equal(validSession('another-secret-another-secret-0000', token, 2_000), false);
    assert.equal(validSession(config.sessionSecret, `${Date.now() + 99_999_999}.forged`), false);
    assert.equal(validSession(config.sessionSecret, 'garbage'), false);
  });
});

describe('access control', () => {
  it('requires the sync token for sync routes', async () => {
    assert.equal((await call('/api/sync/state')).status, 401);
    assert.equal((await call('/api/sync/state', { token: 'wrong' })).status, 401);
    assert.equal((await call('/api/sync/state', { token: session })).status, 401);
    assert.equal((await phone('/api/sync/state')).status, 200);
  });
  it('requires an owner session for read routes', async () => {
    assert.equal((await call('/api/overview')).status, 401);
    assert.equal((await call('/api/records', { token: config.syncToken })).status, 401);
    assert.equal((await owner('/api/overview')).status, 200);
  });
});

describe('sync', () => {
  let dataset = '';
  beforeEach(async () => { dataset = (await phone('/api/sync/reset', {})).body.dataset; });

  const ledger = (id: string, at: number, extra: Record<string, unknown> = {}) =>
    ({ id, at, data: { id, accountId: 'cash', kind: 'EXPENSE', amount: -1500, category: 'Food', description: `Lunch ${id}`, occurredAt: at, ...extra } });

  it('upserts, updates and deletes rows, then lists them newest first', async () => {
    let result = await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1000), ledger('b', 2000), ledger('c', 3000)] });
    assert.deepEqual(result.body, { upserted: 3, deleted: 0 });
    result = await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1000, { category: 'Transport' })], deletes: ['c'] });
    assert.deepEqual(result.body, { upserted: 1, deleted: 1 });
    const listed = await owner('/api/records?kinds=ledger');
    assert.deepEqual(listed.body.rows.map((r: { id: string }) => r.id), ['b', 'a']);
    assert.equal(listed.body.rows[1].data.category, 'Transport');
    assert.equal(listed.body.next, null);
  });

  it('accepts a full 500-row batch of about 1.5 MB', async () => {
    const upserts = Array.from({ length: 500 }, (_, i) => ({ id: `t${i}`, at: i, data: { mediaId: 'm', text: 'neno '.repeat(600) } }));
    const result = await phone('/api/sync/batch', { dataset, kind: 'transcript', upserts });
    assert.deepEqual(result.body, { upserted: 500, deleted: 0 });
    assert.equal((await phone('/api/sync/batch', { dataset, kind: 'transcript', upserts: [...upserts, ledger('x', 1)] })).status, 400);
  });

  it('filters, searches literally and pages', async () => {
    await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1000), ledger('b', 2000, { category: 'Rent' }), ledger('c', 3000, { description: '100% juice' })] });
    assert.deepEqual((await owner('/api/records?kinds=ledger&category=Rent')).body.rows.map((r: { id: string }) => r.id), ['b']);
    assert.deepEqual((await owner('/api/records?kinds=ledger&category=Rent&category=Food')).body.rows.map((r: { id: string }) => r.id), ['c', 'b', 'a']);
    assert.deepEqual((await owner('/api/records?kinds=ledger&q=100%25')).body.rows.map((r: { id: string }) => r.id), ['c']);
    assert.deepEqual((await owner('/api/records?kinds=ledger&q=_')).body.rows, []);
    const first = await owner('/api/records?kinds=ledger&limit=2');
    assert.deepEqual(first.body.rows.map((r: { id: string }) => r.id), ['c', 'b']);
    const { afterAt, afterId } = first.body.next;
    const second = await owner(`/api/records?kinds=ledger&limit=2&afterAt=${afterAt}&afterId=${afterId}`);
    assert.deepEqual(second.body.rows.map((r: { id: string }) => r.id), ['a']);
    assert.deepEqual((await owner('/api/records?kinds=ledger&from=1500&to=3000')).body.rows.map((r: { id: string }) => r.id), ['b']);
  });

  it('rejects unknown kinds, unlisted fields and bad batches', async () => {
    assert.equal((await owner('/api/records?kinds=secrets')).status, 400);
    assert.equal((await owner('/api/records?kinds=summary')).status, 400);
    assert.equal((await phone('/api/sync/batch', { dataset, kind: 'secrets', upserts: [] })).status, 400);
    assert.equal((await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1), ledger('a', 2)] })).status, 400);
    assert.equal((await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [{ id: 'a', at: 1.5, data: {} }] })).status, 400);
    // A field outside the allow-list is ignored, never interpolated.
    await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1)] });
    assert.equal((await owner("/api/records?kinds=ledger&description'%20OR%201=1--=x")).body.rows.length, 1);
  });

  it('refuses batches for an old dataset after a reset', async () => {
    await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('a', 1)] });
    const fresh = (await phone('/api/sync/reset', {})).body.dataset;
    assert.notEqual(fresh, dataset);
    assert.equal((await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [ledger('b', 2)] })).status, 409);
    assert.equal((await phone('/api/sync/finish', { dataset })).status, 409);
    assert.deepEqual((await owner('/api/records?kinds=ledger')).body.rows, []);
  });

  it('builds the overview from synced rows and records the finish time', async () => {
    const now = Date.now();
    await phone('/api/sync/batch', { dataset, kind: 'summary', upserts: [{ id: 'current', at: 0, data: { month: '2026-11', income: 5000, expenses: 1500 } }] });
    await phone('/api/sync/batch', { dataset, kind: 'candidate', upserts: [{ id: 'k', at: now, data: { amount: 9 } }] });
    await phone('/api/sync/batch', { dataset, kind: 'task', upserts: [
      { id: 'soon', at: now + 3_600_000, data: { title: 'Pay rent', state: 'ACTIVE' } },
      { id: 'later', at: now + 10 * 86_400_000, data: { title: 'Renew', state: 'ACTIVE' } },
      { id: 'review', at: now, data: { title: 'Maybe', state: 'REVIEW' } },
    ] });
    assert.equal((await phone('/api/sync/finish', { dataset })).status, 200);
    const overview = (await owner('/api/overview')).body;
    assert.equal(overview.summary.income, 5000);
    assert.equal(overview.toConfirm, 1);
    assert.equal(overview.tasksToReview, 1);
    assert.deepEqual(overview.dueTasks.map((t: { id: string }) => t.id), ['soon']);
    assert.ok(overview.lastSyncAt >= now);
    assert.equal((await owner('/api/record/task/soon')).body.data.title, 'Pay rent');
    assert.equal((await owner('/api/record/task/missing')).status, 404);
  });
});

describe('encrypted library', () => {
  const chunk = (n: number) => {
    const bytes = Buffer.alloc(40, n);
    return { sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.toString('base64') };
  };
  const record = (id: string, baseVersion: number, deleted = false) => ({
    entityType: 'media_bookmark', entityId: id, baseVersion, deleted, updatedAt: 10, deviceId: 'phone-1',
    envelope: { v: 1, salt: 'c2FsdC1pcy1zaXg=', ct: 'Y2lwaGVydGV4dA==' },
  });

  it('requires a sync token or owner session', async () => {
    assert.equal((await call('/api/library/state')).status, 401);
    assert.equal((await phone('/api/library/state')).status, 200);
    assert.equal((await owner('/api/library/state')).status, 200);
  });

  it('uploads chunks, resumes, verifies hashes and rejects a second copy of the same hash', async () => {
    const opened = await phone('/api/library/blobs', { contentHash: 'a'.repeat(64), size: 20, chunkSize: 8, chunkCount: 3 });
    assert.equal(opened.status, 200);
    assert.deepEqual(opened.body.received, []);
    const first = await phone(`/api/library/blobs/${opened.body.blobId}/chunks/1`, chunk(1));
    assert.equal(first.body.complete, false);
    const again = await phone('/api/library/blobs', { contentHash: 'a'.repeat(64), size: 20, chunkSize: 8, chunkCount: 3 });
    assert.deepEqual(again.body.received, [1]);
    assert.equal(again.body.blobId, opened.body.blobId);
    const bad = chunk(2); bad.sha256 = 'b'.repeat(64);
    assert.equal((await phone(`/api/library/blobs/${opened.body.blobId}/chunks/0`, bad)).status, 400);
    await phone(`/api/library/blobs/${opened.body.blobId}/chunks/0`, chunk(2));
    const done = await phone(`/api/library/blobs/${opened.body.blobId}/chunks/2`, chunk(3));
    assert.equal(done.body.complete, true);
    const downloaded = await owner(`/api/library/blobs/${opened.body.blobId}/chunks/1`);
    assert.equal(downloaded.body.sha256, chunk(1).sha256);
  });

  it('refuses a blob that would pass the quota', async () => {
    assert.equal((await phone('/api/library/blobs', { contentHash: 'c'.repeat(64), size: 600, chunkSize: 256, chunkCount: 3 })).status, 200);
    assert.equal((await phone('/api/library/blobs', { contentHash: 'd'.repeat(64), size: 500, chunkSize: 256, chunkCount: 2 })).status, 413);
  });

  it('publishes a change feed, tombstones and version conflicts without storing plaintext search', async () => {
    assert.equal((await owner('/api/library/search?q=secret')).status, 403);
    assert.equal((await phone('/api/library/records', { records: [{ ...record('b1', 0), searchText: 'secret note' }] })).status, 400);
    const saved = await phone('/api/library/records', { records: [record('b1', 0)] });
    assert.equal(saved.body.accepted[0].version, 1);
    const conflict = await phone('/api/library/records', { records: [record('b1', 0)] });
    assert.equal(conflict.body.conflicts[0].version, 1);
    assert.equal(conflict.body.accepted.length, 0);
    const removed = await phone('/api/library/records', { records: [record('b1', 1, true)] });
    assert.equal(removed.body.accepted[0].version, 2);
    const feed = await owner('/api/library/changes?after=0');
    assert.equal(feed.body.records.at(-1).deleted, true);
    assert.equal(feed.body.records.at(-1).envelope.ct, 'Y2lwaGVydGV4dA==');
    assert.equal((await phone('/api/library/records', { records: [{ ...record('nope', 0), entityType: 'contact' }] })).status, 400);
    await ownerPost('/api/library/search-opt-in', { enabled: true });
    await phone('/api/library/records', { records: [{ ...record('b2', 0), searchText: 'harbour note' }] });
    const found = await owner('/api/library/search?q=harbour');
    assert.deepEqual(found.body.rows.map((row: { entityId: string }) => row.entityId), ['b2']);
    await ownerPost('/api/library/search-opt-in', { enabled: false });
    assert.equal((await owner('/api/library/search?q=harbour')).status, 403);
  });

  it('matches the Android passphrase envelope', async () => {
    const password = 'correct horse';
    const salt = new Uint8Array(16).fill(0x0b);
    const iv = new Uint8Array(12).fill(1);
    const plain = new TextEncoder().encode('hello library');
    const base = await webcrypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
    const key = await webcrypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: 600_000, hash: 'SHA-256' }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt']);
    const extra = new TextEncoder().encode('library-record-v1:media_item:item-1');
    const ct = new Uint8Array(await webcrypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: extra }, key, plain));
    const out = Buffer.concat([Buffer.from([1]), Buffer.from(iv), Buffer.from(ct)]);
    assert.equal(out.toString('hex'), '0101010101010101010101010125292b7958ae59fdb3115a6fabac8d5abbb6beb49657e7b10946f8f799');
  });
});

function ownerPost(path: string, body: unknown) {
  return call(path, { token: session, body });
}

describe('login', () => {
  it('issues a usable session for the right password and throttles guessing', async () => {
    assert.equal((await call('/api/auth/login', { body: { password: 'nope' } })).status, 401);
    const ok = await call('/api/auth/login', { body: { password: 'correct horse battery' } });
    assert.equal(ok.status, 200);
    assert.equal((await call('/api/overview', { token: ok.body.token })).status, 200);
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await call('/api/auth/login', { body: { password: 'nope' } })).status);
    assert.ok(statuses.includes(429));
  });
});
