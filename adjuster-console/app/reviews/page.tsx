'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole, useAuth } from '../auth';
import { Shell } from '../components/Shell';
import { Photos } from '../components/Photos';
import type { Claim } from '../types';

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

function ReviewRow({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (m: string) => void }) {
  const { session, has } = useAuth();
  const me = session?.user.username;
  const mine = claim.reviewAssignee === me || has('ADMIN');
  const [amount, setAmount] = useState<string>(claim.estimatedAmount?.toString() ?? '');
  const [reason, setReason] = useState('');
  const run = async (fn: () => Promise<unknown>) => {
    try { await fn(); await onChange(); } catch (e) { onError((e as Error).message); }
  };
  return (
    <tr>
      <td><strong>{claim.claimNumber}</strong><br /><span className="muted">{claim.plateNumber} · {claim.policyNumber}</span></td>
      <td>{claim.description}<Photos claim={claim} /></td>
      <td><span className={`badge ${claim.severity ?? ''}`}>{claim.severity ?? '?'}</span><br />
        <span className="muted">szac. {claim.estimatedAmount ?? '—'}</span><br /><span className="muted small">{claim.assessmentProvider}</span></td>
      <td>{fmt(claim.reviewDueAt)}{claim.escalated && <div className="escalated">SLA przekroczone</div>}</td>
      <td>{claim.reviewAssignee ?? <span className="muted">nieprzypisana</span>}</td>
      <td>
        {!claim.reviewAssignee && <button onClick={() => run(() => api.claimReview(claim.id))}>Przejmij</button>}
        {claim.reviewAssignee && mine && (
          <div className="actions">
            <input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 110 }} />
            <button onClick={() => run(() => api.approve(claim.id, Number(amount)))}>Zatwierdź</button>
            <input placeholder="powód odmowy" value={reason} onChange={(e) => setReason(e.target.value)} />
            <button onClick={() => run(() => api.reject(claim.id, reason))}>Odrzuć</button>
            <button onClick={() => run(() => api.unclaimReview(claim.id))}>Oddaj</button>
          </div>
        )}
        {claim.reviewAssignee && !mine && <span className="muted small">prowadzi {claim.reviewAssignee}</span>}
      </td>
    </tr>
  );
}

export default function Reviews() {
  const [reviews, setReviews] = useState<Claim[]>([]);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => {
    try { setReviews(await api.reviews()); setError(null); } catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => {
    void refresh();
    const id = setInterval(() => void refresh(), 5000);
    return () => clearInterval(id);
  }, [refresh]);

  return (
    <RequireRole roles={['ADJUSTER', 'ADMIN']}>
      <Shell title={`Kolejka review — otwarte: ${reviews.length}`}>
        {error && <p className="error">{error}</p>}
        <table>
          <thead><tr><th>Szkoda</th><th>Opis</th><th>Triage</th><th>Termin</th><th>Prowadzi</th><th>Decyzja</th></tr></thead>
          <tbody>
            {reviews.map((c) => <ReviewRow key={c.id} claim={c} onChange={refresh} onError={setError} />)}
            {reviews.length === 0 && <tr><td colSpan={6} className="muted">Brak spraw do oceny.</td></tr>}
          </tbody>
        </table>
      </Shell>
    </RequireRole>
  );
}
