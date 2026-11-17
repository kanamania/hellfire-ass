'use client';

import { useEffect, useMemo, useState } from 'react';
import { CHUNK, aad, b64, derive, open, playable, seal, sha256, unb64 } from './library-crypto';

type Field = { v: string; t: number };
type Doc = { deleted?: boolean; fields: Record<string, Field> };
type Row = { changeSeq: number; entityType: string; entityId: string; version: number; deleted: boolean; updatedAt: number; deviceId: string; envelope: { ct: string }; doc?: Doc };
type State = { salt: string | null; searchPlaintext: boolean; used: number; quota: number; items: number; cursor: number };

const tabs = ['Inbox', 'Gallery', 'Documents', 'Locations', 'Notes', 'Analysis', 'Categories', 'Collections', 'Bookmarks', 'Search', 'Upload', 'Status'] as const;

async function call(path: string, body?: unknown) {
  const response = await fetch(`/api/library/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401) window.location.href = '/login';
  const text = await response.text();
  const parsed = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(parsed?.message ?? `Request failed (${response.status})`);
  return parsed;
}

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
  const [key, setKey] = useState<CryptoKey | null>(null);
  const [state, setState] = useState<State | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [tab, setTab] = useState<(typeof tabs)[number]>('Inbox');
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [mediaUrl, setMediaUrl] = useState<string | null>(null);

  async function unlock(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const current = await call('state') as State;
      let salt = current.salt;
      if (!salt) salt = (await call('salt', { salt: b64(crypto.getRandomValues(new Uint8Array(16))) })).salt as string;
      if (!salt) throw new Error('The server did not return an encryption salt');
      const derived = await derive(pass, unb64(salt));
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
          const plain = await open(derived, unb64(row.envelope.ct), aad('record', `${row.entityType}:${row.entityId}`));
          row.doc = JSON.parse(new TextDecoder().decode(plain));
        } catch { row.doc = undefined; }
      }
      setState(current);
      setRows(loaded);
      setKey(derived);
      setPass('');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not unlock'); }
  }

  const live = useMemo(() => rows.filter((row) => !row.deleted && row.doc), [rows]);
  const byType = (type: string) => live.filter((row) => row.entityType === type);
  const item = live.find((row) => row.entityId === selected && row.entityType === 'media_item');
  const matches = query.trim() ? live.filter((row) => textOf(row.doc).toLowerCase().includes(query.trim().toLowerCase())) : [];

  useEffect(() => () => { if (mediaUrl) URL.revokeObjectURL(mediaUrl); }, [mediaUrl]);

  async function original(row: Row) {
    if (!key || !row.doc) return null;
    const blobId = row.doc.fields.blobId?.v;
    const size = Number(row.doc.fields.size?.v ?? 0);
    if (!blobId || !size) return null;
    const parts: Uint8Array[] = [];
    for (let index = 0; index < Math.ceil(size / CHUNK); index++) {
      const chunk = await call(`blobs/${blobId}/chunks/${index}`) as { sha256: string; bytes: string };
      const bytes = unb64(chunk.bytes);
      if (await sha256(bytes) !== chunk.sha256) throw new Error('Downloaded chunk failed its hash check');
      parts.push(await open(key, bytes, aad('chunk', `${row.entityId}:${index}`)));
    }
    const all = new Uint8Array(parts.reduce((n, part) => n + part.length, 0));
    let offset = 0;
    for (const part of parts) { all.set(part, offset); offset += part.length; }
    return new Blob([all], { type: row.doc.fields.mime?.v || 'application/octet-stream' });
  }

  async function show(row: Row) {
    setSelected(row.entityId);
    setError('');
    if (mediaUrl) URL.revokeObjectURL(mediaUrl);
    setMediaUrl(null);
    try {
      const blob = await original(row);
      if (blob) setMediaUrl(URL.createObjectURL(blob));
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not decrypt the original'); }
  }

  async function saveRecord(entityType: string, entityId: string, fields: Record<string, string>, baseVersion: number, searchText?: string) {
    if (!key || !state) return;
    const stamped: Record<string, Field> = {};
    const now = Date.now();
    for (const [name, value] of Object.entries(fields)) stamped[name] = { v: value, t: now };
    const body = JSON.stringify({ deleted: false, fields: stamped });
    const sealed = await seal(key, new TextEncoder().encode(body), aad('record', `${entityType}:${entityId}`));
    const salt = state.salt ?? (await call('state') as State).salt;
    if (!salt) throw new Error('Missing encryption salt');
    await call('records', {
      records: [{ entityType, entityId, baseVersion, deleted: false, updatedAt: now, deviceId: deviceId(),
        envelope: { v: 1, salt, ct: b64(sealed) }, ...(state.searchPlaintext && searchText !== undefined ? { searchText } : {}) }],
    });
    setError('Saved. The phone will pull this change on its next sync.');
  }

  async function upload(file: File) {
    if (!key || !state?.salt) { setError('Unlock the library first.'); return; }
    const id = crypto.randomUUID();
    const bytes = new Uint8Array(await file.arrayBuffer());
    const contentHash = await sha256(bytes);
    const count = Math.ceil(bytes.length / CHUNK);
    const opened = await call('blobs', { contentHash, size: bytes.length, chunkSize: CHUNK, chunkCount: count }) as { blobId: string; received: number[] };
    const have = new Set(opened.received);
    for (let index = 0; index < count; index++) {
      if (have.has(index)) continue;
      const slice = bytes.subarray(index * CHUNK, Math.min(bytes.length, (index + 1) * CHUNK));
      const sealed = await seal(key, slice, aad('chunk', `${id}:${index}`));
      await call(`blobs/${opened.blobId}/chunks/${index}`, { sha256: await sha256(sealed), bytes: b64(sealed) });
    }
    await saveRecord('media_item', id, {
      name: file.name, mime: file.type || 'application/octet-stream', contentKind: file.type.startsWith('image/') ? 'IMAGE' : file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : 'DOCUMENT',
      size: String(bytes.length), contentHash, origin: 'SYNC', blobId: opened.blobId, needsAnalysis: '1', summary: '',
    }, 0, state.searchPlaintext ? file.name : undefined);
  }

  if (!key) return (
    <>
      <h1>Library</h1>
      <p className="muted">Originals and analysis are encrypted before they leave a device. Enter the same passphrase saved on the phone. It is not sent to the server. Search stays in this browser unless you explicitly allow a plaintext index.</p>
      <form className="stack" onSubmit={unlock}>
        <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} placeholder="Library passphrase" autoComplete="current-password" />
        <button type="submit">Unlock</button>
        {error && <p className="error">{error}</p>}
      </form>
    </>
  );

  const mime = item?.doc?.fields.mime?.v ?? '';
  const transcripts = live.filter((row) => row.entityType === 'transcript_segment' && row.doc?.fields.mediaId?.v === selected);
  const bookmarks = live.filter((row) => row.entityType === 'media_bookmark' && row.doc?.fields.mediaId?.v === selected);
  const analysis = live.filter((row) => row.entityType === 'media_analysis' && row.doc?.fields.mediaId?.v === selected);

  return (
    <>
      <h1>Library</h1>
      <p className="muted">Decrypted in this browser. AI models do not run here; synced analysis is shown when a device has produced it, and uploads are queued for that device.</p>
      {error && <p className="error">{error}</p>}
      <div className="tabs">{tabs.map((name) => <button key={name} type="button" aria-current={tab === name ? 'page' : undefined} onClick={() => setTab(name)}>{name}</button>)}</div>
      {tab === 'Inbox' && <List rows={byType('inbox_item')} onOpen={(row) => setSelected(row.entityId)} title={(row) => row.doc?.fields.title?.v || 'Capture'} detail={(row) => `${row.doc?.fields.summary?.v ?? ''} · sync ${row.doc?.fields.syncState?.v ?? 'pending'} · backup ${row.doc?.fields.backupState?.v ?? 'not backed up'}`} />}
      {tab === 'Gallery' && <Gallery rows={byType('media_item').filter((row) => (row.doc?.fields.contentKind?.v ?? '').match(/IMAGE|VIDEO/))} onOpen={show} />}
      {tab === 'Documents' && <List rows={byType('media_item').filter((row) => !'IMAGE VIDEO AUDIO'.includes(row.doc?.fields.contentKind?.v ?? ''))} onOpen={show} title={(row) => row.doc?.fields.name?.v || 'Document'} detail={(row) => row.doc?.fields.mime?.v ?? ''} />}
      {tab === 'Locations' && <List rows={byType('saved_location')} title={(row) => row.doc?.fields.label?.v || 'Place'} detail={(row) => [row.doc?.fields.address?.v, row.doc?.fields.note?.v].filter(Boolean).join(' · ')} />}
      {tab === 'Notes' && <List rows={byType('inbox_item')} title={(row) => row.doc?.fields.title?.v || 'Note'} detail={(row) => row.doc?.fields.notes?.v ?? ''} />}
      {tab === 'Analysis' && <List rows={byType('media_analysis')} title={(row) => row.doc?.fields.analyzer?.v || 'Analysis'} detail={(row) => row.doc?.fields.text?.v ?? ''} />}
      {tab === 'Categories' && <Categories rows={byType('media_category')} onSave={saveRecord} />}
      {tab === 'Collections' && <List rows={byType('media_collection')} title={(row) => row.doc?.fields.name?.v || 'Collection'} detail={(row) => row.doc?.fields.description?.v ?? ''} />}
      {tab === 'Bookmarks' && <Bookmarks rows={byType('media_bookmark')} onSave={saveRecord} />}
      {tab === 'Search' && (
        <section className="card">
          <form className="filters" onSubmit={(e) => e.preventDefault()}>
            <label>Search decrypted library<input value={query} onChange={(e) => setQuery(e.target.value)} /></label>
          </form>
          <p className="muted small">{state?.searchPlaintext ? 'A plaintext server index is on. This box still searches decrypted text in the browser.' : 'Server-side plaintext search is off. This searches only what this browser has decrypted.'}</p>
          <label className="small"><input type="checkbox" checked={!!state?.searchPlaintext} onChange={async (e) => {
            const next = await call('search-opt-in', { enabled: e.target.checked }) as { searchPlaintext: boolean };
            setState((old) => old ? { ...old, searchPlaintext: next.searchPlaintext } : old);
          }} /> Allow the server to store a plaintext search index</label>
          <List rows={matches} title={(row) => `${row.entityType} ${row.doc?.fields.name?.v || row.doc?.fields.title?.v || row.entityId}`} detail={(row) => textOf(row.doc).slice(0, 180)} />
        </section>
      )}
      {tab === 'Upload' && (
        <section className="card stack">
          <p>Files are encrypted in this browser, then uploaded as ciphertext. The phone pulls them and queues local analysis. This browser does not transcribe or classify them.</p>
          <input type="file" onChange={(e) => { const file = e.target.files?.[0]; if (file) upload(file).catch((err) => setError(err.message)); }} />
        </section>
      )}
      {tab === 'Status' && (
        <section className="card">
          <p>{state?.items ?? 0} encrypted items. Storage used {state?.used ?? 0} of {state?.quota ?? 0} bytes.</p>
          <p className="muted">Sync is not a backup. Backup status on each capture is the phone’s own backup state, shown in the inbox. This site cannot verify a backup.</p>
        </section>
      )}
      {item && (
        <section className="card" style={{ marginTop: 16 }}>
          <h2>{item.doc?.fields.name?.v}</h2>
          {mediaUrl && playable(mime) && mime.startsWith('image/') && <img className="player" alt="" src={mediaUrl} />}
          {mediaUrl && playable(mime) && mime.startsWith('video/') && <video className="player" controls src={mediaUrl} />}
          {mediaUrl && playable(mime) && mime.startsWith('audio/') && <audio className="player" controls src={mediaUrl} />}
          {mediaUrl && !playable(mime) && <p>Not playable in browser — download.</p>}
          {mediaUrl && mime === 'application/pdf' && <iframe className="player" title="Document" src={mediaUrl} />}
          {mediaUrl && <p><a className="button" href={mediaUrl} download={item.doc?.fields.name?.v || 'original'}>Download original</a></p>}
          {analysis.map((row) => <p key={row.entityId}>{row.doc?.fields.text?.v}</p>)}
          <div className="transcript">{transcripts.map((row) => (
            <p key={row.entityId}><button type="button" className="quiet" onClick={() => {
              const player = document.querySelector<HTMLMediaElement>('.player');
              if (player) player.currentTime = Number(row.doc?.fields.startMs?.v ?? 0) / 1000;
            }}><span className="t">{row.doc?.fields.startMs?.v} ms</span>{row.doc?.fields.text?.v}</button></p>
          ))}</div>
          {bookmarks.map((row) => <p key={row.entityId}>{row.doc?.fields.title?.v} · {row.doc?.fields.note?.v}</p>)}
        </section>
      )}
    </>
  );
}

function List({ rows, title, detail, onOpen }: { rows: Row[]; title: (row: Row) => string; detail: (row: Row) => string; onOpen?: (row: Row) => void }) {
  if (!rows.length) return <p className="muted">Nothing synced in this section yet.</p>;
  return <ul className="list">{rows.map((row) => (
    <li key={row.entityId}><div className="main"><button type="button" className="quiet title" onClick={() => onOpen?.(row)}>{title(row)}</button><div className="body">{detail(row)}</div></div></li>
  ))}</ul>;
}

function Gallery({ rows, onOpen }: { rows: Row[]; onOpen: (row: Row) => void }) {
  if (!rows.length) return <p className="muted">No pictures or videos synced yet.</p>;
  return <div className="gallery">{rows.map((row) => <button key={row.entityId} type="button" onClick={() => onOpen(row)}>{row.doc?.fields.name?.v || 'Item'}</button>)}</div>;
}

function Categories({ rows, onSave }: { rows: Row[]; onSave: (type: string, id: string, fields: Record<string, string>, version: number, search?: string) => Promise<void> }) {
  const [name, setName] = useState('');
  const [id, setId] = useState(rows[0]?.entityId ?? '');
  return (
    <section className="card stack">
      <List rows={rows} title={(row) => row.doc?.fields.name?.v || 'Category'} detail={() => 'Editable — a rename syncs back to the phone'} onOpen={(row) => { setId(row.entityId); setName(row.doc?.fields.name?.v ?? ''); }} />
      {id && <form className="filters" onSubmit={(e) => { e.preventDefault(); const row = rows.find((item) => item.entityId === id); onSave('media_category', id, { ...(Object.fromEntries(Object.entries(row?.doc?.fields ?? {}).map(([k, v]) => [k, v.v]))), name }, row?.version ?? 0, name); }}>
        <label>Name<input value={name} onChange={(e) => setName(e.target.value)} /></label>
        <button type="submit">Save category</button>
      </form>}
    </section>
  );
}

function Bookmarks({ rows, onSave }: { rows: Row[]; onSave: (type: string, id: string, fields: Record<string, string>, version: number) => Promise<void> }) {
  const [id, setId] = useState('');
  const [note, setNote] = useState('');
  return (
    <section className="card stack">
      <List rows={rows} title={(row) => row.doc?.fields.title?.v || 'Bookmark'} detail={(row) => `${row.doc?.fields.startMs?.v ?? 0} ms · ${row.doc?.fields.note?.v ?? ''}`} onOpen={(row) => { setId(row.entityId); setNote(row.doc?.fields.note?.v ?? ''); }} />
      {id && <form className="filters" onSubmit={(e) => { e.preventDefault(); const row = rows.find((item) => item.entityId === id); onSave('media_bookmark', id, { ...(Object.fromEntries(Object.entries(row?.doc?.fields ?? {}).map(([k, v]) => [k, v.v]))), note }, row?.version ?? 0); }}>
        <label>Note<input value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <button type="submit">Save bookmark</button>
      </form>}
    </section>
  );
}
