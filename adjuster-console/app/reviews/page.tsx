'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole, useAuth } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Photos, SeverityBadge, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { Claim } from '../types';

function ReviewRow({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (error: unknown) => void }) {
  const { session, has } = useAuth();
  const mine = claim.reviewAssignee === session?.user.username || has('ADMIN');
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
      <td>{claim.reviewAssignee ?? <span className="faint">nieprzypisana</span>}</td>
      <td>
        {!claim.reviewAssignee && <button className="btn sm primary" onClick={() => run(() => api.claimReview(claim.id))}>Przejmij</button>}
        {claim.reviewAssignee && mine && (
          <div className="actions">
            <input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
            <button className="btn sm primary" onClick={() => run(() => api.approve(claim.id, Number(amount)))}>Zatwierdź</button>
            <input className="inline-input" style={{ width: 160 }} placeholder="powód odmowy" value={reason} onChange={(event) => setReason(event.target.value)} />
            <button className="btn sm danger" onClick={() => run(() => api.reject(claim.id, reason))}>Odrzuć</button>
            <button className="btn sm" onClick={() => run(() => api.unclaimReview(claim.id))}>Oddaj</button>
            <button className="btn sm" onClick={() => run(() => api.withdraw(claim.id))}>Wycofaj</button>
          </div>
        )}
        {claim.reviewAssignee && !mine && <span className="muted small">prowadzi {claim.reviewAssignee}</span>}
      </td>
    </tr>
  );
}

export default function Reviews() {
  const [reviews, setReviews] = useState<Claim[]>([]);
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => { try { setReviews(await api.reviews()); clearError(); } catch (candidate) { setError(candidate); } }, [setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);
  const escalated = reviews.filter((claim) => claim.escalated).length;
  const severe = reviews.filter((claim) => claim.severity === 'SEVERE').length;
  return (
    <RequireRole roles={['ADJUSTER', 'ADMIN']}>
      <Shell title="Kolejka ocen" subtitle="Szkody po ocenie automatycznej, czekające na decyzję likwidatora">
        <div className="grid cols-3" style={{ marginBottom: '1rem' }}>
          <div className="card stat"><div className="label">Otwarte</div><div className="value">{reviews.length}</div></div>
          <div className="card stat"><div className="label">Po terminie SLA</div><div className="value">{escalated}</div></div>
          <div className="card stat"><div className="label">Poważne (SEVERE)</div><div className="value">{severe}</div></div>
        </div>
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap">
          <table>
            <thead><tr><th>Szkoda</th><th>Opis</th><th>Ocena ML</th><th>Termin</th><th>Prowadzi</th><th>Decyzja</th></tr></thead>
            <tbody>
              {reviews.map((claim) => <ReviewRow key={claim.id} claim={claim} onChange={refresh} onError={setError} />)}
              {reviews.length === 0 && <tr><td colSpan={6} className="empty">Brak spraw do oceny.</td></tr>}
            </tbody>
          </table>
        </div>
      </Shell>
    </RequireRole>
  );
}
