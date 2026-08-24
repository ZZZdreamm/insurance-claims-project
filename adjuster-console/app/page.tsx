'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from './api';
import type { Claim } from './types';

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

interface RowProps {
  claim: Claim;
  me: string;
  onChange: () => Promise<void>;
  onError: (msg: string) => void;
}

function Photos({ claim }: { claim: Claim }) {
  if (claim.photoIds.length === 0) return <span className="muted">no photos</span>;
  return (
    <div className="photos">
      {claim.photoIds.map((p) => (
        <a key={p} href={api.photoUrl(claim.id, p)} target="_blank" rel="noreferrer">
          <img src={api.photoUrl(claim.id, p)} alt="damage" />
        </a>
      ))}
    </div>
  );
}

function ReviewRow({ claim, me, onChange, onError }: RowProps) {
  const [amount, setAmount] = useState<string>(claim.estimatedAmount?.toString() ?? '');
  const [reason, setReason] = useState('');
  const mine = claim.reviewAssignee === me;

  const run = async (fn: () => Promise<unknown>) => {
    try {
      await fn();
      await onChange();
    } catch (e) {
      onError((e as Error).message);
    }
  };

  return (
    <tr>
      <td>
        <strong>{claim.claimNumber}</strong>
        <br />
        <span className="muted">{claim.plateNumber}</span>
      </td>
      <td>
        {claim.description}
        <Photos claim={claim} />
      </td>
      <td>
        <span className={`badge ${claim.severity ?? ''}`}>{claim.severity ?? '?'}</span>
        <br />
        <span className="muted">est. {claim.estimatedAmount ?? '—'}</span>
        <br />
        <span className="muted small">{claim.assessmentProvider}</span>
      </td>
      <td>
        {fmt(claim.reviewDueAt)}
        {claim.escalated && <div className="escalated">SLA breached</div>}
      </td>
      <td>{claim.reviewAssignee ?? <span className="muted">unassigned</span>}</td>
      <td>
        {!claim.reviewAssignee && <button onClick={() => run(() => api.claimReview(claim.id, me))}>Claim</button>}
        {mine && (
          <div className="actions">
            <input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 100 }} />
            <button onClick={() => run(() => api.approve(claim.id, Number(amount)))}>Approve</button>
            <input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            <button onClick={() => run(() => api.reject(claim.id, reason))}>Reject</button>
            <button onClick={() => run(() => api.unclaimReview(claim.id))}>Unclaim</button>
          </div>
        )}
      </td>
    </tr>
  );
}

function FailedPayoutRow({ claim, onChange, onError }: Omit<RowProps, 'me'>) {
  const [amount, setAmount] = useState<string>(claim.approvedAmount?.toString() ?? '');
  const retry = async () => {
    try {
      await api.retryPayout(claim.id, Number(amount) || undefined);
      await onChange();
    } catch (e) {
      onError((e as Error).message);
    }
  };
  return (
    <tr>
      <td><strong>{claim.claimNumber}</strong></td>
      <td className="escalated">{claim.payoutFailureReason}</td>
      <td>
        <div className="actions">
          <input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 100 }} />
          <button onClick={retry}>Retry payout</button>
        </div>
      </td>
    </tr>
  );
}

function SubmitDemoClaim({ onDone, onError }: { onDone: () => void; onError: (m: string) => void }) {
  const [desc, setDesc] = useState('Rear-ended at a red light, bumper and tail light damaged');
  const [amount, setAmount] = useState('2500');
  const [photos, setPhotos] = useState<File[]>([]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api.submit(
        {
          policyNumber: 'POL-' + Math.floor(Math.random() * 9000 + 1000),
          plateNumber: 'WA ' + Math.floor(Math.random() * 90000 + 10000),
          incidentDate: new Date().toISOString().slice(0, 10),
          description: desc,
          estimatedAmount: Number(amount),
        },
        photos,
      );
      setTimeout(onDone, 2000); // assessment-service reacts over Kafka; give it a moment
    } catch (err) {
      onError((err as Error).message);
    }
  };

  return (
    <details style={{ margin: '1rem 0' }}>
      <summary>Submit a demo claim</summary>
      <form className="actions" style={{ marginTop: '0.5rem' }} onSubmit={submit}>
        <input value={desc} onChange={(e) => setDesc(e.target.value)} style={{ flex: 1, minWidth: 300 }} />
        <input type="number" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 100 }} />
        <input type="file" accept="image/*" multiple onChange={(e) => setPhotos(Array.from(e.target.files ?? []))} />
        <button type="submit">Submit</button>
      </form>
    </details>
  );
}

export default function Page() {
  const [reviews, setReviews] = useState<Claim[]>([]);
  const [failed, setFailed] = useState<Claim[]>([]);
  const [me, setMe] = useState('alice');
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [r, f] = await Promise.all([api.reviews(), api.claimsByStatus('PAYOUT_FAILED')]);
      setReviews(r);
      setFailed(f.content);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const id = setInterval(() => void refresh(), 5000);
    return () => clearInterval(id);
  }, [refresh]);

  return (
    <main>
      <h1>
        Adjuster console <span className="muted">— open reviews: {reviews.length}</span>
      </h1>
      <div className="actions">
        <label>
          Acting as <input value={me} onChange={(e) => setMe(e.target.value)} style={{ width: 120 }} />
        </label>
        <button onClick={() => void refresh()}>Refresh</button>
      </div>
      <SubmitDemoClaim onDone={() => void refresh()} onError={setError} />
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr><th>Claim</th><th>Description</th><th>Triage</th><th>Due</th><th>Assignee</th><th>Actions</th></tr>
        </thead>
        <tbody>
          {reviews.map((c) => <ReviewRow key={c.id} claim={c} me={me} onChange={refresh} onError={setError} />)}
          {reviews.length === 0 && <tr><td colSpan={6} className="muted">No open reviews.</td></tr>}
        </tbody>
      </table>
      {failed.length > 0 && (
        <>
          <h2>Failed payouts</h2>
          <table>
            <thead><tr><th>Claim</th><th>Reason</th><th>Retry</th></tr></thead>
            <tbody>
              {failed.map((c) => <FailedPayoutRow key={c.id} claim={c} onChange={refresh} onError={setError} />)}
            </tbody>
          </table>
        </>
      )}
    </main>
  );
}
