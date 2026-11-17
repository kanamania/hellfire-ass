// News shapes from web/api/src/news.ts. Summaries and scores are the phone's JSON, read defensively (camelCase or snake_case).

type Data = Record<string, any>;

export interface NewsState {
  eventId: string; deliveredVersion: number; seenVersion: number; readAt: number | null;
  bookmarked: boolean; readLater: boolean; hidden: boolean; followed: boolean; updatedAt: number; pending: boolean;
}
export interface NewsProgress { briefingId: string; position: number; audioMs: number; updatedAt: number; pending: boolean }
export interface Story {
  eventId: string; version: number; section: string; classification: string | null;
  event: Data | null; articles: Data[]; versions: Data[]; state: NewsState;
}
export interface Listed { event: Data; state: NewsState }
export interface BriefingSummary {
  id: string; slot: string; title: string; generatedAt: number; scheduledFor: number;
  counts: { stories: number; top: number; changed: number; unread: number };
}

export interface SourceRef { articleId: string; publisher: string; title: string; url: string | null; publishedAt: number; contentType: string; agency: string }
export interface Summary {
  headline: string; quick: string[]; details: { text: string; sources: string[] }[];
  whyItMatters: string; localImpact: string; background: string; whatsNext: string[]; conflicts: string[];
  labels: string[]; sources: SourceRef[]; language: string; generatedBy: string;
}

const pick = (o: Data, ...keys: string[]) => keys.map((key) => o[key]).find((value) => value !== undefined && value !== null);
const str = (value: unknown) => (typeof value === 'string' ? value : '');
const strs = (value: unknown) => (Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && !!item) : []);
const num = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : 0);
const obj = (value: unknown): Data => (value && typeof value === 'object' && !Array.isArray(value) ? (value as Data) : {});

/** Only http(s) links leave this site; anything else from a feed is dropped. */
export function safeUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch { return null; }
}

export function summaryOf(raw: unknown): Summary {
  const o = obj(raw);
  const details = Array.isArray(o.details) ? o.details.flatMap((d: unknown) => {
    if (typeof d === 'string') return d ? [{ text: d, sources: [] }] : [];
    const line = obj(d);
    return typeof line.text === 'string' && line.text ? [{ text: line.text, sources: strs(line.sources) }] : [];
  }) : [];
  const sources = Array.isArray(o.sources) ? o.sources.map((s: unknown) => {
    const r = obj(s);
    return {
      articleId: str(pick(r, 'articleId', 'article_id')), publisher: str(r.publisher), title: str(r.title), url: safeUrl(r.url),
      publishedAt: num(pick(r, 'publishedAt', 'published_at')), contentType: str(pick(r, 'contentType', 'content_type')), agency: str(r.agency),
    };
  }) : [];
  return {
    headline: str(o.headline), quick: strs(o.quick), details,
    whyItMatters: str(pick(o, 'whyItMatters', 'why_it_matters')), localImpact: str(pick(o, 'localImpact', 'local_impact')),
    background: str(o.background), whatsNext: strs(pick(o, 'whatsNext', 'whats_next')), conflicts: strs(o.conflicts),
    labels: strs(o.labels), sources, language: str(o.language), generatedBy: str(pick(o, 'generatedBy', 'generated_by')),
  };
}

export function scoreOf(raw: unknown) {
  const o = obj(raw);
  return {
    total: num(o.total), level: str(o.level), reasons: strs(o.reasons),
    factors: Object.entries(obj(o.factors)).filter((entry): entry is [string, number] => typeof entry[1] === 'number'),
    protectedPublic: !!pick(o, 'protectedPublic', 'protected_public'),
  };
}

const LABELS: Record<string, string> = {
  DEVELOPING: 'Developing', CONFLICTING: 'Conflicting', SINGLE_SOURCE: 'Single source', UNVERIFIED_SOCIAL: 'Unverified social posts',
  OPINION: 'Opinion', ANALYSIS: 'Analysis', SATIRE: 'Satire', ADVERTISEMENT: 'Advertisement', UPDATE: 'Update', CORRECTION: 'Correction',
  RESOLVED: 'Resolved', TRANSLATED: 'Translated', SOCIAL_POST: 'Social post', OFFICIAL_STATEMENT: 'Official statement',
};
export const labelName = (key: string) => LABELS[key] ?? key.toLowerCase().replace(/_/g, ' ');

/** The summary's labels plus what the event, its articles and the briefing item say. */
export function labelsOf(event: Data, summary: Summary, articles: Data[] = [], classification?: string | null): string[] {
  const labels = new Set(summary.labels);
  if (event.status === 'DEVELOPING' || event.status === 'RESOLVED') labels.add(event.status);
  if (['CONFLICTING', 'SINGLE_SOURCE', 'UNVERIFIED_SOCIAL'].includes(event.verification)) labels.add(event.verification);
  for (const article of articles) if (['OPINION', 'ANALYSIS', 'SATIRE', 'ADVERTISEMENT'].includes(article.contentType)) labels.add(article.contentType);
  if (classification === 'MAJOR' || classification === 'MINOR') labels.add('UPDATE');
  if (classification === 'CORRECTION' || classification === 'RESOLVED') labels.add(classification);
  return [...labels];
}

export const CLASSIFICATION: Record<string, string> = { NEW: 'First report', MAJOR: 'Update', MINOR: 'Minor update', CORRECTION: 'Correction', RESOLVED: 'Resolved' };

/** yyyy-mm-dd in EAT (UTC+3) to the start of that day, or of the next day with [end]. */
export function dayStart(value: string | undefined, end = false): number | undefined {
  const match = value ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
  if (!match) return undefined;
  return Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + (end ? 1 : 0)) - 3 * 3600_000;
}
