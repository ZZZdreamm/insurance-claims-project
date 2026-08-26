'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole, useAuth } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Photos, SeverityBadge, Stat, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { Claim, Page, ReviewQueueSummary, ReviewScope, Severity } from '../types';

const PAGE_SIZE = 20;

function ReviewRow({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (error: unknown) => void }) {
  const { session, has } = useAuth();
  const mine = claim.reviewAssignee === session?.user.username;
  const canDecide = mine || has('ADMIN');
  const [amount, setAmount] = useState<string>(claim.estimatedAmount?.toString() ?? '');
  const [reason, setReason] = useState('');
  const run = (action: () => Promise<unknown>) => action().then(onChange).catch(onError);
  const overdue = claim.reviewDueAt ? new Date(claim.reviewDueAt).getTime() < Date.now() : false;
  return (
    <tr>
      <td><Link href={`/claims/${claim.id}`}><strong>{claim.claimNumber}</strong></Link><br /><span className="muted small">{claim.plateNumber} · {claim.policyNumber}</span></td>
      <td>{claim.description}<Photos claim={claim} /></td>
      <td><SeverityBadge severity={claim.severity} /><div className="muted small">szac. {formatMoney(claim.estimatedAmount)}</div>
        {claim.assessmentExplanation && <div className="faint small" title={claim.assessmentExplanation}>{claim.assessmentExplanation.split(', ').slice(0, 3).join(' · ')}</div>}</td>
      <td className="nowrap"><span className={overdue ? 'badge bad' : ''}>{formatDateTime(claim.reviewDueAt)}</span>{claim.escalated && <div className="badge bad">eskalacja</div>}</td>
      <td>{claim.reviewAssignee ? (mine ? <span className="badge info">ja</span> : claim.reviewAssignee) : <span className="faint">nieprzypisana</span>}</td>
      <td>
        {!claim.reviewAssignee && <button className="btn sm primary" onClick={() => run(() => api.claimReview(claim.id))}>Przejmij</button>}
        {claim.reviewAssignee && canDecide && (
          <div className="actions">
            <input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
            <button className="btn sm primary" onClick={() => run(() => api.approve(claim.id, Number(amount)))}>Zatwierdź</button>
            <input className="inline-input" style={{ width: 160 }} placeholder="powód odmowy" value={reason} onChange={(event) => setReason(event.target.value)} />
            <button className="btn sm danger" onClick={() => run(() => api.reject(claim.id, reason))}>Odrzuć</button>
            <button className="btn sm" onClick={() => run(() => api.unclaimReview(claim.id))}>Oddaj</button>
            <button className="btn sm" onClick={() => run(() => api.withdraw(claim.id))}>Wycofaj</button>
          </div>
        )}
        {claim.reviewAssignee && !canDecide && <span className="muted small">prowadzi {claim.reviewAssignee}</span>}
      </td>
    </tr>
  );
}

export default function Reviews() {
  const [page, setPage] = useState<Page<Claim> | null>(null);
  const [summary, setSummary] = useState<ReviewQueueSummary | null>(null);
  const [scope, setScope] = useState<ReviewScope>('UNASSIGNED');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [escalatedOnly, setEscalatedOnly] = useState(false);
  const [pageNumber, setPageNumber] = useState(0);
  const [error, setError, clearError] = useErrorState();

  const refresh = useCallback(async () => {
    try {
      const [nextPage, nextSummary] = await Promise.all([api.reviews({ scope, severity, escalatedOnly, page: pageNumber, size: PAGE_SIZE }), api.reviewSummary()]);
      setPage(nextPage); setSummary(nextSummary); clearError();
    } catch (candidate) { setError(candidate); }
  }, [scope, severity, escalatedOnly, pageNumber, setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 10000); return () => clearInterval(timer); }, [refresh]);
  useEffect(() => { setPageNumber(0); }, [scope, severity, escalatedOnly]);

  const totalPages = page?.totalPages ?? 0;
  return (
    <RequireRole roles={['ADJUSTER', 'ADMIN']}>
      <Shell title="Kolejka ocen" subtitle="Szkody po ocenie automatycznej, czekające na decyzję likwidatora — najstarszy termin SLA pierwszy">
        <div className="grid cols-4" style={{ marginBottom: '1rem' }}>
          <Stat label="Otwarte" value={summary?.open ?? '—'} hint={`${summary?.unassigned ?? '—'} nieprzypisanych`} />
          <Stat label="Moje sprawy" value={summary?.mine ?? '—'} />
          <Stat label="Po terminie SLA" value={summary?.escalated ?? '—'} />
          <Stat label="Poważne (SEVERE)" value={summary?.severe ?? '—'} />
        </div>
        <div className="card toolbar">
          <div className="field">Zakres
            <div className="actions">
              {([['UNASSIGNED', 'Do wzięcia'], ['MINE', 'Moje'], ['ALL', 'Wszystkie']] as [ReviewScope, string][]).map(([value, label]) => (
                <button key={value} className={`btn sm ${scope === value ? 'primary' : ''}`} onClick={() => setScope(value)}>{label}</button>))}
            </div>
          </div>
          <label className="field">Powaga<select value={severity} onChange={(event) => setSeverity(event.target.value as Severity | '')}><option value="">dowolna</option><option value="MINOR">MINOR</option><option value="MODERATE">MODERATE</option><option value="SEVERE">SEVERE</option></select></label>
          <label className="field">&nbsp;<span className="actions"><input type="checkbox" checked={escalatedOnly} onChange={(event) => setEscalatedOnly(event.target.checked)} /> tylko po SLA</span></label>
          <span className="muted small" style={{ marginLeft: 'auto' }}>{page ? `${page.totalElements} spraw · strona ${page.number + 1} z ${Math.max(totalPages, 1)}` : ''}</span>
        </div>
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap">
          <table>
            <thead><tr><th>Szkoda</th><th>Opis</th><th>Ocena ML</th><th>Termin</th><th>Prowadzi</th><th>Decyzja</th></tr></thead>
            <tbody>
              {page?.content.map((claim) => <ReviewRow key={claim.id} claim={claim} onChange={refresh} onError={setError} />)}
              {page && page.content.length === 0 && <tr><td colSpan={6} className="empty">Brak spraw w tym widoku.</td></tr>}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="actions" style={{ marginTop: '0.8rem', justifyContent: 'flex-end' }}>
              <button className="btn sm" disabled={pageNumber === 0} onClick={() => setPageNumber(pageNumber - 1)}>‹ Poprzednia</button>
              <span className="muted small">{pageNumber + 1} / {totalPages}</span>
              <button className="btn sm" disabled={pageNumber + 1 >= totalPages} onClick={() => setPageNumber(pageNumber + 1)}>Następna ›</button>
            </div>
          )}
        </div>
      </Shell>
    </RequireRole>
  );
}
