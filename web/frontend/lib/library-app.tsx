'use client';

import { useEffect, useMemo, useState, useRef } from 'react';
import { CHUNK, aad, b64, deriveKeys, keyedId, verifyKey, type Keys, open, playable, seal, sha256, unb64 } from './library-crypto';

type Field = { v: string; t: number };
type Doc = { id?: string; updatedAt?: number; deleted?: boolean; fields: Record<string, Field> };
type Row = { changeSeq: number; entityType: string; entityId: string; version: number; deleted: boolean; updatedAt: number; deviceId: string; envelope: { v: number; ct: string }; doc?: Doc };
type State = { salt: string | null; keyCheck: string | null; used: number; quota: number; items: number; cursor: number };

const tabs = ['Inbox', 'Gallery', 'Documents', 'Locations', 'Notes', 'Analysis', 'Categories', 'Collections', 'Bookmarks', 'Search', 'Upload', 'Status'] as const;

async function call(path: string, body?: unknown) {
  for (let attempt = 0; ; attempt++) {
    const response = await fetch(`/api/library/${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 401) window.location.href = '/login';
    if (response.status === 429 && attempt < 3) {
      const delay = Math.min(30, Math.max(1, Number(response.headers.get('retry-after')) || 2 ** attempt));
      await response.body?.cancel();
      await new Promise((resolve) => setTimeout(resolve, delay * 1000));
      continue;
    }
    const text = await response.text();
    const parsed = text ? JSON.parse(text) : null;
    if (!response.ok) throw new Error(parsed?.message ?? `Request failed (${response.status})`);
    return parsed;
  }
}

const localId = (row: Row) => row.doc?.id ?? row.entityId;
const values = (row: Row) => Object.fromEntries(Object.entries(row.doc?.fields ?? {}).map(([name, field]) => [name, field.v]));
const MAX_BROWSER_FILE = 256 * 1024 * 1024;


function deviceId() {
  const key = 'hf-library-device';
  const saved = localStorage.getItem(key);
  if (saved) return saved;
  const id = crypto.randomUUID();
  localStorage.setItem(key, id);
  return id;
}

function textOf(doc: Doc | undefined) {
  if (!doc) return '';
  return Object.values(doc.fields).map((field) => field.v).join(' ');
}

export function LibraryApp() {
  const [pass, setPass] = useState('');
  const [key, setKey] = useState<Keys | null>(null);
  const [state, setState] = useState<State | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [tab, setTab] = useState<(typeof tabs)[number]>('Inbox');
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [mediaUrl, setMediaUrl] = useState<string | null>(null);
  const player = useRef<HTMLMediaElement | null>(null);
  const seekTo = useRef(0);
  const request = useRef(0);
  const [busy, setBusy] = useState(false);

  async function unlock(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const current = await call('state') as State;
      const salt = current.salt;
      if (!salt || !current.keyCheck) throw new Error('Sync the library from the phone first to establish its encryption key.');
      const derived = await deriveKeys(pass, unb64(salt));
      if (!await verifyKey(derived, current.keyCheck)) throw new Error('The passphrase does not match this library.');
      const loaded: Row[] = [];
      let after = 0;
      for (;;) {
        const page = await call(`changes?after=${after}&limit=100`) as { records: Row[] };
        loaded.push(...page.records);
        if (page.records.length < 100) break;
        after = page.records.at(-1)!.changeSeq;
      }
      for (const row of loaded) {
        if (row.deleted) continue;
        try {
          const plain = await open(derived.cipher, unb64(row.envelope.ct), aad('record', `${row.entityType}:${row.entityId}`));
          const doc: Doc = JSON.parse(new TextDecoder().decode(plain));
          if (row.envelope.v === 2 && (!doc.id || await keyedId(derived, `record:${row.entityType}:${doc.id}`) !== row.entityId || doc.deleted !== row.deleted || doc.updatedAt !== row.updatedAt)) throw new Error('Record identity check failed');
          row.doc = doc;
        } catch { row.doc = undefined; }
      }
      const encrypted = loaded.filter((row) => !row.deleted);
      if (encrypted.length && !encrypted.some((row) => row.doc)) throw new Error('Could not decrypt the library. Check the passphrase.');
      if (encrypted.some((row) => !row.doc)) setError('Some records could not be decrypted.');
      current.salt = salt;
      setState(current);
      setRows(loaded);
      setKey(derived);
      setPass('');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not unlock'); }
  }

  const live = useMemo(() => rows.filter((row) => !row.deleted && row.doc), [rows]);
  const byType = (type: string) => live.filter((row) => row.entityType === type);
  const item = live.find((row) => localId(row) === selected && row.entityType === 'media_item');
  const matches = query.trim() ? live.filter((row) => textOf(row.doc).toLowerCase().includes(query.trim().toLowerCase())) : [];

  useEffect(() => () => { if (mediaUrl) URL.revokeObjectURL(mediaUrl); }, [mediaUrl]);

  async function original(row: Row) {
    if (!key || !row.doc) return null;
    const blobId = row.doc.fields.blobId?.v;
    const size = Number(row.doc.fields.size?.v ?? 0);
    if (!blobId || !size) return null;
    if (!Number.isSafeInteger(size) || size > MAX_BROWSER_FILE) throw new Error('Use the phone for originals larger than 256 MiB.');
    const count = Math.ceil(size / CHUNK);
    const all = new Uint8Array(size);
    let next = 0;
    await Promise.all(Array.from({ length: Math.min(3, count) }, async () => {
      while (next < count) {
        const index = next++;
        const chunk = await call(`blobs/${blobId}/chunks/${index}`) as { sha256: string; bytes: string };
        const bytes = unb64(chunk.bytes);
        if (await sha256(bytes) !== chunk.sha256) throw new Error('Downloaded chunk failed its hash check');
        const plain = await open(key.cipher, bytes, aad('chunk', `${row.doc!.fields.contentHash?.v}:${index}`));
        if (plain.length !== Math.min(CHUNK, size - index * CHUNK)) throw new Error('Downloaded chunk has an unexpected size');
        all.set(plain, index * CHUNK);
        plain.fill(0);
      }
    }));
    if (await sha256(all) !== row.doc.fields.contentHash?.v) throw new Error('Original failed its integrity check');
    return new Blob([all], { type: row.doc.fields.mime?.v || 'application/octet-stream' });
  }

  async function show(row: Row, startMs = 0) {
    const current = ++request.current;
    seekTo.current = startMs / 1000;
    setSelected(localId(row));
    setError('');
    if (mediaUrl) URL.revokeObjectURL(mediaUrl);
    setMediaUrl(null);
    try {
      const blob = await original(row);
      if (blob && current === request.current) setMediaUrl(URL.createObjectURL(blob));
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not decrypt the original'); }
  }

  async function saveRecord(entityType: string, entityId: string, fields: Record<string, string>, baseVersion: number) {
    if (!key || !state) return;
    try {
      const stamped: Record<string, Field> = {};
      const now = Date.now();
      const previous = rows.find((row) => row.entityType === entityType && localId(row) === entityId);
      for (const [name, value] of Object.entries(fields)) stamped[name] = previous?.doc?.fields[name]?.v === value ? previous.doc.fields[name] : { v: value, t: now };
      if (previous && previous.envelope.v < 2) throw new Error('Sync this item from the phone to upgrade it before editing on the website.');
      const remoteId = await keyedId(key, `record:${entityType}:${entityId}`);
      const body = JSON.stringify({ id: entityId, deleted: false, updatedAt: now, fields: stamped });
      const sealed = await seal(key.cipher, new TextEncoder().encode(body), aad('record', `${entityType}:${remoteId}`));
      const salt = state.salt ?? (await call('state') as State).salt;
      if (!salt) throw new Error('Missing encryption salt');
      const result = await call('records', {
        records: [{ entityType, entityId: remoteId, baseVersion, deleted: false, updatedAt: now, deviceId: deviceId(),
          envelope: { v: 2, salt, ct: b64(sealed) }, ...(fields.blobId ? { blobId: fields.blobId } : {}) }],
      });
      const accepted = result.accepted?.find((row: { entityType: string; entityId: string }) => row.entityType === entityType && row.entityId === remoteId);
      if (!accepted) throw new Error('This item changed on another device. Unlock the library again before saving.');
      const saved: Row = { changeSeq: previous?.changeSeq ?? 0, entityType, entityId: remoteId, version: accepted.version, deleted: false,
        updatedAt: now, deviceId: deviceId(), envelope: { v: 2, ct: b64(sealed) }, doc: { id: entityId, deleted: false, updatedAt: now, fields: stamped } };
      setRows((old) => [...old.filter((row) => row.entityType !== entityType || localId(row) !== entityId), saved]);
      setError('Saved. The phone will pull this change on its next sync.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save'); }
  }

  async function upload(file: File) {
    if (!key || !state?.salt) { setError('Unlock the library first.'); return; }
    if (busy) return;
    if (!file.size || file.size > MAX_BROWSER_FILE) throw new Error('Choose a file between 1 byte and 256 MiB. Use the phone for larger originals.');
    setBusy(true);
    try {
    const id = crypto.randomUUID();
    const bytes = new Uint8Array(await file.arrayBuffer());
    const contentHash = await sha256(bytes);
    const count = Math.ceil(bytes.length / CHUNK);
    const opened = await call('blobs', { contentHash: await keyedId(key, `blob:${contentHash}`), size: bytes.length, chunkSize: CHUNK, chunkCount: count }) as { blobId: string; received: number[] };
    const have = new Set(opened.received);
    for (let index = 0; index < count; index++) {
      if (have.has(index)) continue;
      const slice = bytes.subarray(index * CHUNK, Math.min(bytes.length, (index + 1) * CHUNK));
      const sealed = await seal(key.cipher, slice, aad('chunk', `${contentHash}:${index}`));
      await call(`blobs/${opened.blobId}/chunks/${index}`, { sha256: await sha256(sealed), bytes: b64(sealed) });
    }
    await saveRecord('media_item', id, {
      name: file.name, mime: file.type || 'application/octet-stream', contentKind: file.type.startsWith('image/') ? 'IMAGE' : file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : 'DOCUMENT',
      size: String(bytes.length), contentHash, origin: 'SYNC', blobId: opened.blobId, needsAnalysis: '1', summary: '',
    }, 0);
    } finally { setBusy(false); }
  }

  if (!key) return (
    <>
      <h1>Library</h1>
      <p className="muted">Originals and analysis are encrypted before they leave a device. Enter the same passphrase saved on the phone. It is not sent to the server. Search stays in this browser.</p>
      <form className="stack" onSubmit={unlock}>
        <label>Library passphrase<input type="password" value={pass} onChange={(e) => setPass(e.target.value)} placeholder="Library passphrase" autoComplete="current-password" /></label>
        <button type="submit">Unlock</button>
        {error && <p className="error">{error}</p>}
      </form>
    </>
  );

  const mime = item?.doc?.fields.mime?.v ?? '';
  const selectedInbox = byType('inbox_item').find((row) => localId(row) === selected);
  const members = byType('inbox_member').filter((row) => row.doc?.fields.inboxId?.v === selected);
  const transcripts = live.filter((row) => row.entityType === 'transcript_segment' && row.doc?.fields.mediaId?.v === selected);
  const bookmarks = live.filter((row) => row.entityType === 'media_bookmark' && row.doc?.fields.mediaId?.v === selected);
  const analysis = live.filter((row) => row.entityType === 'media_analysis' && row.doc?.fields.mediaId?.v === selected);

  return (
    <>
      <h1>Library</h1>
      <p className="muted">Decrypted in this browser. AI models do not run here; synced analysis is shown when a device has produced it, and uploads are queued for that device.</p>
      {error && <p className="error">{error}</p>}
      <div className="tabs">{tabs.map((name) => <button key={name} type="button" aria-current={tab === name ? 'page' : undefined} onClick={() => setTab(name)}>{name}</button>)}</div>
      {tab === 'Inbox' && <List rows={byType('inbox_item')} onOpen={(row) => setSelected(localId(row))} title={(row) => row.doc?.fields.title?.v || 'Capture'} detail={(row) => `${row.doc?.fields.summary?.v ?? ''} · synced to server · phone backup ${row.doc?.fields.backupState?.v ?? 'not backed up'}`} />}
      {tab === 'Gallery' && <Gallery rows={byType('media_item').filter((row) => (row.doc?.fields.contentKind?.v ?? '').match(/IMAGE|VIDEO/))} onOpen={(row) => show(row)} />}
      {tab === 'Documents' && <List rows={byType('media_item').filter((row) => !['IMAGE', 'VIDEO', 'AUDIO'].includes(row.doc?.fields.contentKind?.v ?? ''))} onOpen={(row) => show(row)} title={(row) => row.doc?.fields.name?.v || 'Document'} detail={(row) => row.doc?.fields.mime?.v ?? ''} />}
      {tab === 'Locations' && <List rows={byType('saved_location')} title={(row) => row.doc?.fields.label?.v || 'Place'} detail={(row) => [row.doc?.fields.address?.v, row.doc?.fields.note?.v].filter(Boolean).join(' · ')} />}
      {tab === 'Notes' && <Editor rows={byType('inbox_item')} names={['title', 'notes']} onSave={saveRecord} />}
      {tab === 'Analysis' && <List rows={byType('media_analysis')} title={(row) => row.doc?.fields.analyzer?.v || 'Analysis'} detail={(row) => row.doc?.fields.text?.v ?? ''} />}
      {tab === 'Categories' && <Categories rows={byType('media_category')} onSave={saveRecord} />}
      {tab === 'Collections' && <Editor rows={byType('media_collection')} names={['name', 'description']} onSave={saveRecord} />}
      {tab === 'Bookmarks' && <Bookmarks rows={byType('media_bookmark')} onSave={saveRecord} onSeek={(mark) => {
        const media = byType('media_item').find((row) => localId(row) === mark.doc?.fields.mediaId?.v);
        if (media) show(media, Number(mark.doc?.fields.startMs?.v ?? 0));
      }} />}
      {tab === 'Search' && (
        <section className="card">
          <form className="filters" onSubmit={(e) => e.preventDefault()}>
            <label>Search decrypted library<input value={query} onChange={(e) => setQuery(e.target.value)} /></label>
          </form>
          <p className="muted small">Search uses only text decrypted in this browser.</p>
          <List rows={matches} title={(row) => `${row.entityType} ${row.doc?.fields.name?.v || row.doc?.fields.title?.v || row.entityId}`} detail={(row) => textOf(row.doc).slice(0, 180)} />
        </section>
      )}
      {tab === 'Upload' && (
        <section className="card stack">
          <p>Files are encrypted in this browser, then uploaded as ciphertext. The browser supports files up to 256 MiB. The phone pulls them and queues local analysis. This browser does not transcribe or classify them.</p>
          <label>Choose file<input type="file" disabled={busy} onChange={(e) => { const file = e.target.files?.[0]; if (file) upload(file).catch((err) => setError(err.message)); }} /></label>
          {busy && <p role="status">Encrypting and uploading…</p>}
        </section>
      )}
      {tab === 'Status' && (
        <section className="card">
          <p>{state?.items ?? 0} encrypted items. Storage used {state?.used ?? 0} of {state?.quota ?? 0} bytes.</p>
          <p className="muted">Sync is not a backup. Backup status on each capture is the phone’s own backup state, shown in the inbox. This site cannot verify a backup.</p>
        </section>
      )}
      {selectedInbox && <section className="card">
        <h2>{selectedInbox.doc?.fields.title?.v || 'Capture'}</h2>
        <p>{selectedInbox.doc?.fields.summary?.v}</p><p>{selectedInbox.doc?.fields.notes?.v}</p>
        <List rows={byType('media_item').filter((row) => members.some((member) => member.doc?.fields.itemId?.v === localId(row)))} title={(row) => row.doc?.fields.name?.v || 'Original'} detail={(row) => row.doc?.fields.mime?.v ?? ''} onOpen={(row) => show(row)} />
      </section>}
      {item && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>{item.doc?.fields.name?.v}</h2>
          <p>{item.doc?.fields.summary?.v}</p>
          {!mediaUrl && <p className="muted">Original unavailable or downloading. Thumbnails are not synced.</p>}
          {mediaUrl && playable(mime) && mime.startsWith('image/') && <img className="player" alt="" src={mediaUrl} />}
          {mediaUrl && playable(mime) && mime.startsWith('video/') && <video ref={(element) => { player.current = element; }} onLoadedMetadata={() => { if (player.current) player.current.currentTime = seekTo.current; }} className="player" controls src={mediaUrl} />}
          {mediaUrl && playable(mime) && mime.startsWith('audio/') && <audio ref={(element) => { player.current = element; }} onLoadedMetadata={() => { if (player.current) player.current.currentTime = seekTo.current; }} className="player" controls src={mediaUrl} />}
          {mediaUrl && !playable(mime) && <p>Not playable in browser — download.</p>}
          {mediaUrl && mime === 'application/pdf' && <iframe className="player" title="Document" src={mediaUrl} />}
          {mediaUrl && <p><a className="button" href={mediaUrl} download={item.doc?.fields.name?.v || 'original'}>Download original</a></p>}
          {analysis.map((row) => <p key={row.entityId}>{row.doc?.fields.text?.v}</p>)}
          <div className="transcript">{transcripts.map((row) => (
            <p key={row.entityId}><button type="button" className="quiet" onClick={() => {
              if (player.current) player.current.currentTime = Number(row.doc?.fields.startMs?.v ?? 0) / 1000;
            }}><span className="t">{row.doc?.fields.startMs?.v} ms</span>{row.doc?.fields.text?.v}</button></p>
          ))}</div>
          {bookmarks.map((row) => <p key={row.entityId}><button type="button" className="quiet" onClick={() => { if (player.current) player.current.currentTime = Number(row.doc?.fields.startMs?.v ?? 0) / 1000; }}>{row.doc?.fields.title?.v} · {row.doc?.fields.note?.v}</button></p>)}
        </section>
      )}
    </>
  );
}

function List({ rows, title, detail, onOpen }: { rows: Row[]; title: (row: Row) => string; detail: (row: Row) => string; onOpen?: (row: Row) => void }) {
  if (!rows.length) return <p className="muted">Nothing synced in this section yet.</p>;
  return <ul className="list">{rows.map((row) => (
    <li key={row.entityId}><div className="main"><>{onOpen ? <button type="button" className="quiet title" onClick={() => onOpen(row)}>{title(row)}</button> : <strong>{title(row)}</strong>}</><div className="body">{detail(row)}</div></div></li>
  ))}</ul>;
}

function Gallery({ rows, onOpen }: { rows: Row[]; onOpen: (row: Row) => void }) {
  if (!rows.length) return <p className="muted">No pictures or videos synced yet.</p>;
  return <div className="gallery">{rows.map((row) => <button key={row.entityId} type="button" onClick={() => onOpen(row)}>{row.doc?.fields.name?.v || 'Item'}<span className="muted small"> · Thumbnail not synced</span></button>)}</div>;
}

type Save = (type: string, id: string, fields: Record<string, string>, version: number) => Promise<void>;

function Editor({ rows, names, onSave }: { rows: Row[]; names: string[]; onSave: Save }) {
  const [id, setId] = useState('');
  const [draft, setDraft] = useState<Record<string, string>>({});
  const row = rows.find((item) => localId(item) === id);
  return <section className="card stack">
    <List rows={rows} title={(item) => item.doc?.fields.title?.v || item.doc?.fields.name?.v || item.entityType} detail={(item) => names.map((name) => item.doc?.fields[name]?.v).filter(Boolean).join(' · ')} onOpen={(item) => { setId(localId(item)); setDraft(values(item)); }} />
    {row && <form className="stack" onSubmit={(event) => { event.preventDefault(); onSave(row.entityType, id, draft, row.version); }}>
      {names.map((name) => <label key={name}>{name}<textarea value={draft[name] ?? ''} onChange={(event) => setDraft({ ...draft, [name]: event.target.value })} /></label>)}
      <button type="submit">Save changes</button>
    </form>}
  </section>;
}

function Categories({ rows, onSave }: { rows: Row[]; onSave: Save }) {
  return <Editor rows={rows} names={['name']} onSave={onSave} />;
}

function Bookmarks({ rows, onSave, onSeek }: { rows: Row[]; onSave: Save; onSeek: (row: Row) => void }) {
  return <>
    <List rows={rows} title={(row) => `Play ${row.doc?.fields.title?.v || 'bookmark'} at ${row.doc?.fields.startMs?.v ?? 0} ms`} detail={(row) => row.doc?.fields.note?.v ?? ''} onOpen={onSeek} />
    <Editor rows={rows} names={['title', 'note']} onSave={onSave} />
  </>;
}
