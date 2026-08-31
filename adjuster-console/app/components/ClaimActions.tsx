'use client';

import { useState } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';
import { Alert } from './ui';
import type { Claim } from '../types';

/** Every action a role can take on a claim in its current state; the APIs enforce the same rules. */
export function ClaimActions({ claim, onChange }: { claim: Claim; onChange: () => Promise<void> }) {
  const { session, has } = useAuth();
  const [amount, setAmount] = useState<string>((claim.approvedAmount ?? claim.estimatedAmount)?.toString() ?? '');
  const [advance, setAdvance] = useState<string>('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const me = session?.user.username;
  const isAdjuster = has('ADJUSTER', 'ADMIN');
  const holdsReview = claim.reviewAssignee === me || has('ADMIN');
  const isOwner = claim.ownerId != null && has('POLICYHOLDER');
  const terminal = ['PAID', 'REJECTED', 'WITHDRAWN'].includes(claim.status);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(null);
    try { await action(); await onChange(); } catch (candidate) { setError((candidate as Error).message); } finally { setBusy(false); }
  };

  const buttons: React.ReactNode[] = [];
  if (claim.status === 'PENDING_REVIEW' && isAdjuster) {
    if (!claim.reviewAssignee) {
      buttons.push(<button key="take" className="btn primary" disabled={busy} onClick={() => run(() => api.claimReview(claim.id))}>Take the review</button>);
    } else if (holdsReview) {
      buttons.push(
        <span key="approve" className="actions">
          <input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
          <select className="inline-input" value={advance} onChange={(event) => setAdvance(event.target.value)} title="Pay only a share now; finance releases the remainder">
            <option value="">full payout</option><option value="25">25% advance</option><option value="50">50% advance</option>
          </select>
          <button className="btn primary" disabled={busy} onClick={() => run(() => api.approve(claim.id, Number(amount), advance ? Number(advance) : undefined))}>Approve amount</button>
        </span>,
        <span key="reject" className="actions">
          <input className="inline-input" style={{ width: 200 }} placeholder="rejection reason" value={reason} onChange={(event) => setReason(event.target.value)} />
          <button className="btn danger" disabled={busy || !reason} onClick={() => run(() => api.reject(claim.id, reason))}>Reject</button>
        </span>,
        <button key="unclaim" className="btn" disabled={busy} onClick={() => run(() => api.unclaimReview(claim.id))}>Release the review</button>,
      );
    } else {
      buttons.push(<span key="held" className="muted">Review held by <strong>{claim.reviewAssignee}</strong></span>);
    }
  }
  if (claim.status === 'PENDING_SECOND_APPROVAL' && isAdjuster) {
    const wasFirstApprover = claim.firstApprover === me && !has('ADMIN');
    buttons.push(
      <span key="second" className="actions">
        <span className="muted small">payable {claim.payableAmount} · first approval by <strong>{claim.firstApprover}</strong></span>
        {wasFirstApprover
          ? <span className="muted">Four-eyes: a different adjuster must confirm.</span>
          : <button className="btn primary" disabled={busy} onClick={() => run(() => api.secondApprove(claim.id))}>Confirm (second approval)</button>}
      </span>,
    );
  }
  if (claim.status === 'PARTIALLY_PAID' && has('FINANCE', 'ADMIN')) {
    buttons.push(
      <button key="remainder" className="btn primary" disabled={busy} onClick={() => run(() => api.payRemainder(claim.id))}>
        Pay remainder ({((claim.payableAmount ?? 0) - claim.paidAmount).toFixed(2)})
      </button>,
    );
  }
  if (claim.status === 'PAYOUT_FAILED' && has('FINANCE', 'ADMIN')) {
    buttons.push(
      <span key="retry" className="actions">
        <input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
        <button className="btn primary" disabled={busy} onClick={() => run(() => api.retryPayout(claim.id, Number(amount) || undefined))}>Retry payout</button>
      </span>,
    );
  }
  if (!terminal && (isOwner || has('ADJUSTER', 'ADMIN'))) {
    buttons.push(<button key="withdraw" className="btn danger" disabled={busy} onClick={() => { if (window.confirm('Withdraw this claim? This cannot be undone.')) void run(() => api.withdraw(claim.id)); }}>Withdraw claim</button>);
  }
  if (buttons.length === 0) return null;
  return (
    <div className="card">
      <h2>Actions</h2>
      <div className="actions" style={{ gap: '0.8rem' }}>{buttons}</div>
      {error && <Alert kind="error">{error}</Alert>}
    </div>
  );
}
