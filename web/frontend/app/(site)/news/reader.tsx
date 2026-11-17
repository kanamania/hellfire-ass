'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { when } from '@/lib/format';
import { CLASSIFICATION, labelName, labelsOf, safeUrl, summaryOf, type NewsState, type Story } from '@/lib/news';
import { newsAction } from './actions';

type Flag = 'bookmarked' | 'readLater' | 'followed';
const SECONDS = [10, 20, 30, 60];
const RATES = [0.8, 1, 1.2, 1.5];

export function LevelPill({ level }: { level?: string }) {
  return level ? <span className={`pill level ${level.toLowerCase()}`}>{level.charAt(0) + level.slice(1).toLowerCase()}</span> : null;
}

/** Latest material version above the item's starting point, for "What Changed". */
const latestVersion = (item: Story) => item.versions.at(-1);

/** What "Listen" reads: only the latest developments for a changed story, else the story itself. */
function spokenText(item: Story): string {
  const summary = summaryOf(item.event?.summary);
  const latest = latestVersion(item);
  if (item.section === 'CHANGED' && latest)
    return [summary.headline || item.event?.headline, `What's new: ${latest.whatsNew ?? ''}`, latest.whyItMatters ? `Why it matters: ${latest.whyItMatters}` : '']
      .filter(Boolean).join('. ');
  return [summary.headline || item.event?.headline, ...summary.quick, summary.whyItMatters].filter(Boolean).join('. ');
}

/**
 * One story at a time, with keyboard control, autoplay and speech. Choices go to the phone through the
 * newsAction server action; [briefingId] (null on a story page) also receives the reading position.
 */
export function Reader({ briefingId, items, start, fullTimeline = false }: { briefingId: string | null; items: Story[]; start: number; fullTimeline?: boolean }) {
  const [index, setIndex] = useState(Math.min(Math.max(start, 0), Math.max(items.length - 1, 0)));
  const [states, setStates] = useState<Record<string, NewsState>>(() => Object.fromEntries(items.map((item) => [item.eventId, item.state])));
  const [autoplay, setAutoplay] = useState(false);
  const [seconds, setSeconds] = useState(20);
  const [listening, setListening] = useState(false);
  const [paused, setPaused] = useState(false);
  const [rate, setRate] = useState(1);
  const [error, setError] = useState('');
  const [canSpeak, setCanSpeak] = useState(false);
  const firstIndex = useRef(index);
  const utteranceId = useRef(0);
  const item = items[index];
  const total = items.length;

  useEffect(() => setCanSpeak(typeof window !== 'undefined' && 'speechSynthesis' in window), []);

  const go = useCallback((next: number) => setIndex((current) => Math.min(Math.max(next === -1 ? current - 1 : next === -2 ? current + 1 : next, 0), total - 1)), [total]);

  // Remember the position (not the one we started at).
  useEffect(() => {
    if (!briefingId || index === firstIndex.current) return;
    firstIndex.current = -1;
    void newsAction(briefingId, 'position', { position: index, audioMs: 0 });
  }, [briefingId, index]);

  // Shown for 3 seconds counts as read.
  useEffect(() => {
    const event = item?.event;
    if (!event || (states[item.eventId]?.seenVersion ?? 0) >= event.version) return;
    const timer = setTimeout(() => {
      setStates((all) => ({ ...all, [item.eventId]: { ...all[item.eventId], seenVersion: event.version } }));
      void newsAction(item.eventId, 'seen', event.version);
    }, 3000);
    return () => clearTimeout(timer);
  }, [item, states]);

  // Autoplay without speech: advance every few seconds, stopping at the end.
  useEffect(() => {
    if (!autoplay || listening) return;
    if (index >= total - 1) { setAutoplay(false); return; }
    const timer = setTimeout(() => go(-2), seconds * 1000);
    return () => clearTimeout(timer);
  }, [autoplay, listening, index, total, seconds, go]);

  // Speech: reads the current story; with autoplay on, moves on when it finishes.
  const autoplayRef = useRef(autoplay);
  autoplayRef.current = autoplay;
  useEffect(() => {
    if (!listening || !item || !canSpeak) return;
    const id = ++utteranceId.current;
    const utterance = new SpeechSynthesisUtterance(spokenText(item));
    utterance.rate = rate;
    utterance.lang = summaryOf(item.event?.summary).language === 'sw' ? 'sw-TZ' : 'en-GB';
    utterance.onend = () => {
      if (id !== utteranceId.current) return;
      if (autoplayRef.current && index < total - 1) go(-2);
      else setListening(false);
    };
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
    setPaused(false);
    return () => { utteranceId.current++; window.speechSynthesis.cancel(); };
  }, [listening, item, index, total, rate, canSpeak, go]);

  const toggle = useCallback(async (field: Flag) => {
    if (!item) return;
    const value = !states[item.eventId]?.[field];
    setStates((all) => ({ ...all, [item.eventId]: { ...all[item.eventId], [field]: value } }));
    if (!(await newsAction(item.eventId, field, value))) {
      setStates((all) => ({ ...all, [item.eventId]: { ...all[item.eventId], [field]: !value } }));
      setError('Could not save that choice. Check that you are still signed in.');
    } else setError('');
  }, [item, states]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (event.ctrlKey || event.metaKey || event.altKey || target?.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA'].includes(target?.tagName ?? '')) return;
      const key = event.key;
      if (key === 'ArrowRight' || key === 'j') go(-2);
      else if (key === 'ArrowLeft' || key === 'k') go(-1);
      else if (key === ' ') setAutoplay((on) => !on);
      else if (key === 'b') void toggle('bookmarked');
      else if (key === 's') void toggle('readLater');
      else return;
      event.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [go, toggle]);

  if (!item) return <p className="muted">This briefing has no stories.</p>;
  const state = states[item.eventId];

  return (
    <div className="reader">
      <div className="reader-bar card">
        <div className="reader-nav">
          <button className="quiet" onClick={() => go(-1)} disabled={index === 0}>Previous</button>
          <span className="count">{index + 1} of {total}</span>
          <button onClick={() => go(-2)} disabled={index === total - 1}>Next</button>
        </div>
        <div className="reader-tools">
          <label><input type="checkbox" checked={autoplay} onChange={(e) => setAutoplay(e.target.checked)} /> Autoplay</label>
          <label>every <select value={seconds} onChange={(e) => setSeconds(Number(e.target.value))}>{SECONDS.map((s) => <option key={s} value={s}>{s} s</option>)}</select></label>
          {canSpeak && <>
            {!listening ? <button className="quiet" onClick={() => setListening(true)}>Listen</button> : <>
              <button className="quiet" onClick={() => { if (paused) window.speechSynthesis.resume(); else window.speechSynthesis.pause(); setPaused(!paused); }}>{paused ? 'Resume' : 'Pause'}</button>
              <button className="quiet" onClick={() => setListening(false)}>Stop</button>
            </>}
            <label>speed <select value={rate} onChange={(e) => setRate(Number(e.target.value))}>{RATES.map((r) => <option key={r} value={r}>{r}×</option>)}</select></label>
          </>}
        </div>
      </div>
      {error && <p className="banner">{error}</p>}
      <StoryCard item={item} state={state} onToggle={toggle} fullTimeline={fullTimeline} key={item.eventId} />
      <p className="muted small">Keys: ← → or j k to move · space starts or stops autoplay · b bookmark · s save for later</p>
    </div>
  );
}

function StoryCard({ item, state, onToggle, fullTimeline }: { item: Story; state: NewsState; onToggle: (field: Flag) => void; fullTimeline: boolean }) {
  const [timeline, setTimeline] = useState(fullTimeline);
  // Versions after what the owner had read when this page loaded are the new ones.
  const seenAtLoad = useRef(item.state.seenVersion);
  const event = item.event;
  const articles = item.articles;
  const byId = useMemo(() => new Map(articles.map((article) => [String(article.id), article])), [articles]);
  if (!event) return <article className="card story"><p className="muted">This story is not in the web copy (it may be older than 90 days).</p></article>;

  const summary = summaryOf(event.summary);
  const labels = labelsOf(event, summary, articles, item.classification);
  const latest = latestVersion(item);
  const changed = item.section === 'CHANGED' && latest && latest.version > 1;
  const images = articles.flatMap((article) => (Array.isArray(article.media) ? article.media : [])
    .filter((media: Record<string, any>) => media?.type === 'IMAGE' && safeUrl(media.url))
    .map((media: Record<string, any>) => ({ url: safeUrl(media.url)!, credit: String(media.credit || article.publisher || '') }))).slice(0, 2);
  const sources = summary.sources.length ? summary.sources : articles.map((article) => ({
    articleId: String(article.id), publisher: String(article.publisher ?? ''), title: String(article.title ?? ''), url: safeUrl(article.url),
    publishedAt: Number(article.publishedAt) || 0, contentType: String(article.contentType ?? ''), agency: String(article.agency ?? ''),
  }));
  const sourceName = (ref: string) => byId.get(ref)?.publisher ?? ref;
  const button = (field: Flag, on: string, off: string) => (
    <button className={state[field] ? '' : 'quiet'} aria-pressed={state[field]} onClick={() => onToggle(field)}>{state[field] ? on : off}</button>
  );

  return (
    <article className="card story">
      <div className="story-meta">
        <LevelPill level={event.level} />
        {item.section === 'TOP' && <span className="pill accent">Top story</span>}
        {item.section === 'CHANGED' && <span className="pill accent">What changed</span>}
        {labels.map((label) => <span key={label} className={`pill${['CONFLICTING', 'UNVERIFIED_SOCIAL', 'CORRECTION'].includes(label) ? ' warn' : ''}`}>{labelName(label)}</span>)}
      </div>
      <h2 className="headline">{summary.headline || event.headline}</h2>
      <p className="muted small">
        {event.articleCount ?? articles.length} report{event.articleCount === 1 ? '' : 's'} · {event.independentSources ?? 1} independent source{event.independentSources === 1 ? '' : 's'}
        {' · '}updated {when(event.updatedAt)}{event.place ? ` · ${event.place}` : ''}{state.seenVersion >= event.version ? ' · read' : ''}{state.pending ? ' · waiting for the phone' : ''}
      </p>

      {images.length > 0 && <div className="story-images">{images.map((image) => (
        <figure key={image.url}>
          {/* Hotlinked from the publisher, never copied; no referrer is sent. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={image.url} alt="" loading="lazy" referrerPolicy="no-referrer" />
          {image.credit && <figcaption className="muted small">Image: {image.credit}</figcaption>}
        </figure>
      ))}</div>}

      {changed && (
        <section className="changed">
          <h3>What changed</h3>
          {latest.previously && <p><strong>Previously:</strong> {latest.previously}</p>}
          {latest.whatsNew && <p><strong>What’s new:</strong> {latest.whatsNew}</p>}
          {latest.whyItMatters && <p><strong>Why it matters:</strong> {latest.whyItMatters}</p>}
          {Array.isArray(latest.evidence) && latest.evidence.length > 0 && <p><strong>Source evidence:</strong>{' '}
            {latest.evidence.map((id: string, i: number) => {
              const article = byId.get(String(id));
              const url = safeUrl(article?.url);
              return <span key={String(id)}>{i ? ', ' : ''}{url ? <a href={url} target="_blank" rel="noopener noreferrer">{article?.publisher || article?.title}</a> : (article?.publisher ?? id)}</span>;
            })}</p>}
          <p className="muted small">Updated {when(latest.at)} · {CLASSIFICATION[latest.classification] ?? latest.classification}</p>
        </section>
      )}

      {summary.quick.length > 0 ? <ul className="quick">{summary.quick.map((line, i) => <li key={i}>{line}</li>)}</ul>
        : <p className="muted">No summary yet; read the sources below.</p>}

      {summary.details.length > 0 && <details><summary>Details</summary><ul>{summary.details.map((line, i) => (
        <li key={i}>{line.text}{line.sources.length > 0 && <span className="muted small"> — {line.sources.map(sourceName).join(', ')}</span>}</li>
      ))}</ul></details>}
      {summary.whyItMatters && <details><summary>Why it matters</summary><p>{summary.whyItMatters}</p></details>}
      {summary.localImpact && <details><summary>Local impact</summary><p>{summary.localImpact}</p></details>}
      {summary.background && <details><summary>Background</summary><p>{summary.background}</p></details>}
      {summary.whatsNext.length > 0 && <details><summary>What’s next (expected, not confirmed)</summary><ul>{summary.whatsNext.map((line, i) => <li key={i}>{line}</li>)}</ul></details>}
      {summary.conflicts.length > 0 && <details open><summary>Conflicting reports</summary><ul>{summary.conflicts.map((line, i) => <li key={i}>{line}</li>)}</ul></details>}

      <section className="sources">
        <h3>Sources</h3>
        {sources.length ? <ul className="list">{sources.map((source) => (
          <li key={source.articleId || source.title}>
            <div className="main">
              <div className="title">{source.url ? <a href={source.url} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a> : source.title}</div>
              <div className="muted small">{source.publisher}{source.publishedAt ? ` · ${when(source.publishedAt)}` : ''}
                {source.contentType && source.contentType !== 'NEWS' ? ` · ${labelName(source.contentType)}` : ''}{source.agency ? ` · via ${source.agency}` : ''}</div>
            </div>
          </li>
        ))}</ul> : <p className="muted">No sources synced.</p>}
        {summary.generatedBy && <p className="muted small">Summary {summary.generatedBy === 'EXTRACTIVE' ? 'quoted from the sources' : `written by ${summary.generatedBy.replace(/^AI:/, '')} from the sources`} on the phone.</p>}
      </section>

      <div className="story-actions">
        {button('bookmarked', 'Bookmarked', 'Bookmark')}
        {button('readLater', 'Saved for later', 'Save for later')}
        {button('followed', 'Following', 'Follow')}
        {item.versions.length > 0 && <button className="quiet" aria-expanded={timeline} onClick={() => setTimeline(!timeline)}>{timeline ? 'Hide' : 'Show'} what changed over time</button>}
        {!fullTimeline && <Link className="button quiet" href={`/news/story/${encodeURIComponent(item.eventId)}`}>Story page</Link>}
      </div>

      {timeline && <ol className="timeline">{[...item.versions].reverse().map((version) => (
        <li key={version.version} className={version.version > seenAtLoad.current ? 'new' : ''}>
          <div className="muted small">
            {when(version.at)} · version {version.version}{' '}
            <span className={`pill${version.classification === 'CORRECTION' ? ' warn' : ''}`}>{CLASSIFICATION[version.classification] ?? version.classification}</span>
            {version.version > seenAtLoad.current && <span className="pill accent">New to you</span>}
          </div>
          {version.whatsNew && <div>{version.whatsNew}</div>}
          {version.whyItMatters && <div className="muted small">Why it matters: {version.whyItMatters}</div>}
        </li>
      ))}</ol>}
    </article>
  );
}
