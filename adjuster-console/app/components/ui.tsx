'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { api } from '../api';
import type { Claim, ClaimStatus, Severity } from '../types';

export const formatDateTime = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString('pl-PL') : '—');
export const formatMoney = (value: number | null | undefined) =>
  value == null ? '—' : new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN', maximumFractionDigits: 2 }).format(value);
export const formatDuration = (seconds: number | null | undefined) => {
  if (seconds == null) return '—';
  if (seconds < 90) return `${Math.round(seconds)} s`;
  if (seconds < 5400) return `${Math.round(seconds / 60)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
};
export const formatBytes = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(0)} MB`;

const STATUS_LABEL: Record<ClaimStatus, [string, string]> = {
  SUBMITTED: ['zgłoszona', 'info'], PENDING_REVIEW: ['do oceny', 'warn'], APPROVED: ['zatwierdzona', 'info'],
  REJECTED: ['odrzucona', 'bad'], PAID: ['wypłacona', 'ok'], PAYOUT_FAILED: ['wypłata nieudana', 'bad'], WITHDRAWN: ['wycofana', ''],
};
export function StatusBadge({ status }: { status: ClaimStatus }) {
  const [label, tone] = STATUS_LABEL[status] ?? [status, ''];
  return <span className={`badge ${tone}`}>{label}</span>;
}
export function SeverityBadge({ severity }: { severity: Severity | null | undefined }) {
  return severity ? <span className={`badge ${severity}`}>{severity}</span> : <span className="badge">brak oceny</span>;
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="card stat"><div className="label">{label}</div><div className="value">{value}</div>{hint && <div className="hint">{hint}</div>}</div>
  );
}

export function Alert({ kind, children }: { kind: 'error' | 'ok' | 'info'; children: ReactNode }) {
  return <div className={`alert ${kind}`}>{children}</div>;
}

/** Photos sit behind bearer auth, so they are fetched with the token and rendered from object URLs. */
export function Photos({ claim, large }: { claim: Claim; large?: boolean }) {
  const [urls, setUrls] = useState<string[]>([]);
  useEffect(() => {
    let alive = true;
    let created: string[] = [];
    Promise.all(claim.photoIds.map((photoId) => api.photoBlob(claim.id, photoId)))
      .then((result) => { created = result; if (alive) setUrls(result); })
      .catch(() => {});
    return () => { alive = false; created.forEach((url) => URL.revokeObjectURL(url)); };
  }, [claim.id, claim.photoIds]);
  if (claim.photoIds.length === 0) return <span className="faint small">brak zdjęć</span>;
  return (
    <div className={`photos ${large ? 'large' : ''}`}>
      {urls.map((url, index) => <a key={index} href={url} target="_blank" rel="noreferrer"><img src={url} alt="uszkodzenie" /></a>)}
    </div>
  );
}

export function useErrorState(): [string | null, (error: unknown) => void, () => void] {
  const [error, setError] = useState<string | null>(null);
  return [error, (candidate: unknown) => setError(candidate instanceof Error ? candidate.message : String(candidate)), () => setError(null)];
}
