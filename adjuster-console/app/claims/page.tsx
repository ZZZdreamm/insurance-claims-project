'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Pager, Photos, SeverityBadge, StatusBadge, formatDateTime, formatMoney, useDebounced, useErrorState } from '../components/ui';
import type { Claim, Page as PageOf, Policy } from '../types';

function SubmitForm({ onDone, onError }: { onDone: () => void; onError: (error: unknown) => void }) {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [policy, setPolicy] = useState('');
  useEffect(() => {
    api.myPolicies().then((mine) => {
      setPolicies(mine);
      const firstActive = mine.find((candidate) => candidate.active) ?? mine[0];
      if (firstActive) setPolicy((current) => current || firstActive.policyNumber);
    }).catch(() => {});
  }, []);
  const selected = policies.find((candidate) => candidate.policyNumber === policy);
  const [plate, setPlate] = useState('WA 12345');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [photos, setPhotos] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setDone(null);
    try {
      const claim = await api.submit({ policyNumber: policy, plateNumber: plate, incidentDate: date, description, estimatedAmount: amount ? Number(amount) : null }, photos);
      setDescription(''); setAmount(''); setPhotos([]);
      setDone(`Claim ${claim.claimNumber} accepted — automated assessment takes a few seconds.`);
      onDone();
    } catch (error) { onError(error); } finally { setBusy(false); }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>Report a new claim</h2>
      <div className="form-grid">
        <label className="field">Policy
          {policies.length > 0 ? (
            <select value={policy} onChange={(event) => setPolicy(event.target.value)} required>
              {policies.map((candidate) => (
                <option key={candidate.policyNumber} value={candidate.policyNumber} disabled={!candidate.active}>
                  {candidate.policyNumber} ({candidate.coverageType}{candidate.active ? '' : ', expired'})
                </option>
              ))}
            </select>
          ) : (
            <input value={policy} onChange={(event) => setPolicy(event.target.value)} required placeholder="policy number" />
          )}
          {selected && <span className="muted small">sum insured {selected.sumInsured.toLocaleString()} · deductible {selected.deductible.toLocaleString()}</span>}
        </label>
        <label className="field">Plate number<input value={plate} onChange={(event) => setPlate(event.target.value)} required /></label>
        <label className="field">Incident date<input type="date" value={date} onChange={(event) => setDate(event.target.value)} required /></label>
        <label className="field">Estimated amount (PLN)<input type="number" min="0" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
      </div>
      <label className="field" style={{ marginTop: '0.9rem' }}>Damage description
        <textarea value={description} onChange={(event) => setDescription(event.target.value)} minLength={10} required rows={3} placeholder="What happened and what is damaged (min. 10 characters)" />
      </label>
      <label className="field" style={{ marginTop: '0.9rem' }}>Photos (JPEG/PNG/WebP, up to 8 MB)
        <input type="file" accept="image/*" multiple onChange={(event) => setPhotos(Array.from(event.target.files ?? []))} />
      </label>
      <div className="actions" style={{ marginTop: '1rem' }}>
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Submitting…' : 'Submit claim'}</button>
        {photos.length > 0 && <span className="muted small">{photos.length} photo(s)</span>}
      </div>
      {done && <Alert kind="ok">{done}</Alert>}
    </form>
  );
}

export default function MyClaims() {
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const q = useDebounced(query);
  const [claimsPage, setClaimsPage] = useState<PageOf<Claim> | null>(null);
  const [totals, setTotals] = useState<{ all: number; paid: number; closed: number } | null>(null);
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => {
    try {
      // one-row probes give the true totals without downloading the lists
      const [current, paidProbe, rejectedProbe, withdrawnProbe] = await Promise.all([
        api.claims(undefined, page, 25, q), api.claims('PAID', 0, 1), api.claims('REJECTED', 0, 1), api.claims('WITHDRAWN', 0, 1),
      ]);
      setClaimsPage(current);
      setTotals({
        all: current.totalElements,
        paid: paidProbe.totalElements,
        closed: paidProbe.totalElements + rejectedProbe.totalElements + withdrawnProbe.totalElements,
      });
      clearError();
    } catch (candidate) { setError(candidate); }
  }, [page, q, setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  const claims = claimsPage?.content ?? [];
  useEffect(() => { setPage(0); }, [q]);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADMIN']}>
      <Shell title="My claims" subtitle="Your claims, their status and payouts">
        <div className="grid cols-3" style={{ marginBottom: '1rem' }}>
          <div className="card stat"><div className="label">All claims</div><div className="value">{totals?.all ?? '—'}</div></div>
          <div className="card stat"><div className="label">In progress</div><div className="value">{totals ? totals.all - totals.closed : '—'}</div></div>
          <div className="card stat"><div className="label">Paid claims</div><div className="value">{totals?.paid ?? '—'}</div></div>
        </div>
        <SubmitForm onDone={() => void refresh()} onError={setError} />
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap" style={{ marginTop: '1rem' }}>
          <h2>Claims</h2>
          <div className="toolbar">
            <input className="inline-input" style={{ width: 280 }} placeholder="🔍 claim no., plate, policy, description…" value={query} onChange={(event) => setQuery(event.target.value)} />
            {q && <span className="muted small">{claimsPage?.totalElements ?? 0} matching</span>}
          </div>
          <table>
            <thead><tr><th>Claim</th><th>Description</th><th>Status</th><th>Assessment</th><th className="num">Amount</th><th>Submitted</th><th></th></tr></thead>
            <tbody>
              {claims.map((claim) => (
                <tr key={claim.id}>
                  <td><Link href={`/claims/${claim.id}`}><strong>{claim.claimNumber}</strong></Link><br /><span className="muted small">{claim.plateNumber} · {claim.policyNumber}</span></td>
                  <td>{claim.description}<Photos claim={claim} /></td>
                  <td><StatusBadge status={claim.status} />
                    {claim.rejectionReason && <div className="muted small">{claim.rejectionReason}</div>}
                    {claim.payoutFailureReason && <div className="muted small">payout: {claim.payoutFailureReason}</div>}
                    {claim.paidAt && <div className="muted small">paid {formatDateTime(claim.paidAt)}{claim.payoutReference && <> · ref <span className="mono">{claim.payoutReference}</span></>}</div>}</td>
                  <td><SeverityBadge severity={claim.severity} /></td>
                  <td className="num">{formatMoney(claim.approvedAmount ?? claim.estimatedAmount)}</td>
                  <td className="nowrap">{formatDateTime(claim.createdAt)}</td>
                  <td>{!['PAID', 'REJECTED', 'WITHDRAWN'].includes(claim.status) && (
                    <button className="btn sm danger" onClick={() => api.withdraw(claim.id).then(refresh).catch(setError)}>Withdraw</button>)}</td>
                </tr>
              ))}
              {claims.length === 0 && <tr><td colSpan={7} className="empty">No claims yet — use the form above.</td></tr>}
            </tbody>
          </table>
          <Pager page={page} totalPages={claimsPage?.totalPages ?? 0} onPage={setPage} />
        </div>
      </Shell>
    </RequireRole>
  );
}
