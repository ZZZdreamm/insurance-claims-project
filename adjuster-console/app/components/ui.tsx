'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { api } from '../api';
import type { Claim, ClaimStatus, Severity } from '../types';

export const formatDateTime = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString('en-GB') : '—');
export const formatMoney = (value: number | null | undefined) =>
  value == null ? '—' : new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'PLN', maximumFractionDigits: 2 }).format(value);
export const formatDuration = (seconds: number | null | undefined) => {
  if (seconds == null) return '—';
  if (seconds < 90) return `${Math.round(seconds)} s`;
  if (seconds < 5400) return `${Math.round(seconds / 60)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
};
export const formatBytes = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(0)} MB`;

const STATUS_LABEL: Record<ClaimStatus, [string, string]> = {
  SUBMITTED: ['submitted', 'info'], PENDING_REVIEW: ['pending review', 'warn'],
  PENDING_SECOND_APPROVAL: ['awaiting 2nd approval', 'warn'], APPROVED: ['approved', 'info'],
  PARTIALLY_PAID: ['advance paid', 'info'], REJECTED: ['rejected', 'bad'], PAID: ['paid', 'ok'],
  PAYOUT_FAILED: ['payout failed', 'bad'], WITHDRAWN: ['withdrawn', ''],
};

const FRAUD_FLAG_LABEL: Record<string, string> = {
  DUPLICATE_CLAIM: 'another claim for this vehicle within 14 days',
  EARLY_POLICY_CLAIM: 'incident within 30 days of the policy start',
  REUSED_PHOTO: 'a photo from this claim appears on another claim',
  HIGH_CLAIM_FREQUENCY: '3+ claims from this policyholder in 12 months',
};
export function FraudBadges({ flags }: { flags: string[] }) {
  if (!flags || flags.length === 0) return null;
  return (
    <span>
      {flags.map((flag) => (
        <span key={flag} className="badge bad" style={{ marginRight: 4 }} title={FRAUD_FLAG_LABEL[flag] ?? flag}>
          🚩 {flag.replaceAll('_', ' ').toLowerCase()}
        </span>
      ))}
    </span>
  );
}
export function StatusBadge({ status }: { status: ClaimStatus }) {
  const [label, tone] = STATUS_LABEL[status] ?? [status, ''];
  return <span className={`badge ${tone}`}>{label}</span>;
}
export function SeverityBadge({ severity }: { severity: Severity | null | undefined }) {
  return severity ? <span className={`badge ${severity}`}>{severity}</span> : <span className="badge">not assessed</span>;
}

/** Debounce for search-as-you-type table filters: one request per pause, not per keystroke. */
export function useDebounced<T>(value: T, delayMs = 350): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/** Shared pagination control; renders nothing when everything fits on one page. */
export function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (next: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <div className="actions" style={{ marginTop: '0.8rem', justifyContent: 'flex-end' }}>
      <button className="btn sm" disabled={page === 0} onClick={() => onPage(page - 1)}>‹ Previous</button>
      <span className="muted small tabular">{page + 1} / {totalPages}</span>
      <button className="btn sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next ›</button>
    </div>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="card stat"><div className="label">{label}</div><div className="value">{value}</div>{hint && <div className="hint">{hint}</div>}</div>
  );
}

export function Alert({ kind, children }: { kind: 'error' | 'ok' | 'info'; children: ReactNode }) {
  return <div className={`alert ${kind}`}>{children}</div>;
}

/**
 * Photos sit behind bearer auth, so they are fetched with the token and rendered from object URLs.
 * Object URLs are cached per photo for the lifetime of the page: lists re-render every few seconds
 * with fresh claim objects, and re-fetching (and revoking the previous blob) on every render made the
 * <img> point at a dead blob: URL for a moment — hence the flicker and ERR_FILE_NOT_FOUND.
 */
const photoUrlCache = new Map<string, Promise<string>>();

export function photoObjectUrl(claimId: string, photoId: string): Promise<string> {
  const key = `${claimId}/${photoId}`;
  let pending = photoUrlCache.get(key);
  if (!pending) {
    pending = api.photoBlob(claimId, photoId).catch((error: unknown) => {
      photoUrlCache.delete(key); // let a later render retry after a transient failure
      throw error;
    });
    photoUrlCache.set(key, pending);
  }
  return pending;
}

export function Photos({ claim, large }: { claim: Claim; large?: boolean }) {
  const [urls, setUrls] = useState<string[]>([]);
  const photoKey = claim.photoIds.join(','); // stable across re-renders with equal content
  useEffect(() => {
    let alive = true;
    if (!photoKey) { setUrls([]); return; }
    Promise.all(photoKey.split(',').map((photoId) => photoObjectUrl(claim.id, photoId)))
      .then((resolved) => { if (alive) setUrls(resolved); })
      .catch(() => {});
    return () => { alive = false; }; // cached URLs stay valid; nothing to revoke here
  }, [claim.id, photoKey]);
  if (claim.photoIds.length === 0) return <span className="faint small">no photos</span>;
  return (
    <div className={`photos ${large ? 'large' : ''}`}>
      {urls.map((url, index) => <a key={url} href={url} target="_blank" rel="noreferrer"><img src={url} alt={`damage ${index + 1}`} /></a>)}
    </div>
  );
}

/**
 * The setters MUST be referentially stable: every page puts them in the deps of its
 * polling `refresh` callback. Fresh lambdas per render meant a new `refresh` per render,
 * which re-ran the effect, which fetched, which set state, which rendered — an infinite
 * request loop that eventually starved the browser (ERR_INSUFFICIENT_RESOURCES).
 */
export function useErrorState(): [string | null, (error: unknown) => void, () => void] {
  const [error, setError] = useState<string | null>(null);
  const report = useCallback(
    (candidate: unknown) => setError(candidate instanceof Error ? candidate.message : String(candidate)),
    [],
  );
  const clear = useCallback(() => setError(null), []);
  return [error, report, clear];
}
