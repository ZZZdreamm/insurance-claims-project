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
      <td><SeverityBadge severity={claim.severity} /><div className="muted small">est. {formatMoney(claim.estimatedAmount)}</div>
        {claim.assessmentExplanation && <div className="faint small" title={claim.assessmentExplanation}>{claim.assessmentExplanation.split(', ').slice(0, 3).join(' · ')}</div>}</td>
      <td className="nowrap"><span className={overdue ? 'badge bad' : ''}>{formatDateTime(claim.reviewDueAt)}</span>{claim.escalated && <div className="badge bad">escalated</div>}</td>
      <td>{claim.reviewAssignee ? (mine ? <span className="badge info">me</span> : claim.reviewAssignee) : <span className="faint">unassigned</span>}</td>
      <td>
        {!claim.reviewAssignee && <button className="btn sm primary" onClick={() => run(() => api.claimReview(claim.id))}>Take</button>}
        {claim.reviewAssignee && canDecide && (
          <div className="actions">
            <input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
            <button className="btn sm primary" onClick={() => run(() => api.approve(claim.id, Number(amount)))}>Approve</button>
            <input className="inline-input" style={{ width: 160 }} placeholder="rejection reason" value={reason} onChange={(event) => setReason(event.target.value)} />
            <button className="btn sm danger" onClick={() => run(() => api.reject(claim.id, reason))}>Reject</button>
            <button className="btn sm" onClick={() => run(() => api.unclaimReview(claim.id))}>Release</button>
            <button className="btn sm" onClick={() => run(() => api.withdraw(claim.id))}>Withdraw</button>
          </div>
        )}
        {claim.reviewAssignee && !canDecide && <span className="muted small">held by {claim.reviewAssignee}</span>}
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
      <Shell title="Review queue" subtitle="Claims after automated assessment, waiting for an adjuster's decision — earliest SLA first">
        <div className="grid cols-4" style={{ marginBottom: '1rem' }}>
          <Stat label="Open" value={summary?.open ?? '—'} hint={`${summary?.unassigned ?? '—'} unassigned`} />
          <Stat label="Mine" value={summary?.mine ?? '—'} />
          <Stat label="Past SLA" value={summary?.escalated ?? '—'} />
          <Stat label="Severe" value={summary?.severe ?? '—'} />
        </div>
        <div className="card toolbar">
          <div className="field">Scope
            <div className="actions">
              {([['UNASSIGNED', 'Unassigned'], ['MINE', 'Mine'], ['ALL', 'All']] as [ReviewScope, string][]).map(([value, label]) => (
                <button key={value} className={`btn sm ${scope === value ? 'primary' : ''}`} onClick={() => setScope(value)}>{label}</button>))}
            </div>
          </div>
          <label className="field">Severity<select value={severity} onChange={(event) => setSeverity(event.target.value as Severity | '')}><option value="">any</option><option value="MINOR">MINOR</option><option value="MODERATE">MODERATE</option><option value="SEVERE">SEVERE</option></select></label>
          <label className="field">&nbsp;<span className="actions"><input type="checkbox" checked={escalatedOnly} onChange={(event) => setEscalatedOnly(event.target.checked)} /> past SLA only</span></label>
          <span className="muted small" style={{ marginLeft: 'auto' }}>{page ? `${page.totalElements} claims · page ${page.number + 1} of ${Math.max(totalPages, 1)}` : ''}</span>
        </div>
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap">
          <table>
            <thead><tr><th>Claim</th><th>Description</th><th>ML assessment</th><th>Due</th><th>Held by</th><th>Decision</th></tr></thead>
            <tbody>
              {page?.content.map((claim) => <ReviewRow key={claim.id} claim={claim} onChange={refresh} onError={setError} />)}
              {page && page.content.length === 0 && <tr><td colSpan={6} className="empty">No claims in this view.</td></tr>}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="actions" style={{ marginTop: '0.8rem', justifyContent: 'flex-end' }}>
              <button className="btn sm" disabled={pageNumber === 0} onClick={() => setPageNumber(pageNumber - 1)}>‹ Previous</button>
              <span className="muted small">{pageNumber + 1} / {totalPages}</span>
              <button className="btn sm" disabled={pageNumber + 1 >= totalPages} onClick={() => setPageNumber(pageNumber + 1)}>Next ›</button>
            </div>
          )}
        </div>
      </Shell>
    </RequireRole>
  );
}
