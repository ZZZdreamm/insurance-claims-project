'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Stat, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { Claim, LedgerSummary } from '../types';

function FailedRow({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (error: unknown) => void }) {
  const [amount, setAmount] = useState<string>(claim.approvedAmount?.toString() ?? '');
  return (
    <tr>
      <td><Link href={`/claims/${claim.id}`}><strong>{claim.claimNumber}</strong></Link><br /><span className="muted small">{claim.policyNumber}</span></td>
      <td className="badge bad">{claim.payoutFailureReason}</td>
      <td className="num">{formatMoney(claim.approvedAmount)}</td>
      <td><div className="actions"><input className="inline-input" type="number" min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} />
        <button className="btn sm primary" onClick={() => api.retryPayout(claim.id, Number(amount) || undefined).then(onChange).catch(onError)}>Retry payout</button></div></td>
    </tr>
  );
}

export default function Finance() {
  const [failed, setFailed] = useState<Claim[]>([]);
  const [approved, setApproved] = useState<Claim[]>([]);
  const [paid, setPaid] = useState<Claim[]>([]);
  const [ledger, setLedger] = useState<LedgerSummary | null>(null);
  const [ledgerNote, setLedgerNote] = useState<string | null>(null);
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => {
    try {
      const [failedPage, approvedPage, paidPage] = await Promise.all([api.claims('PAYOUT_FAILED'), api.claims('APPROVED'), api.claims('PAID')]);
      setFailed(failedPage.content); setApproved(approvedPage.content); setPaid(paidPage.content); clearError();
      api.ledger().then((summary) => { setLedger(summary); setLedgerNote(null); }).catch((candidate: Error) => setLedgerNote(candidate.message));
    } catch (candidate) { setError(candidate); }
  }, [setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  return (
    <RequireRole roles={['FINANCE', 'ADMIN']}>
      <Shell title="Payouts" subtitle="Failed payouts to retry, payouts in progress and the payment ledger">
        <div className="grid cols-4" style={{ marginBottom: '1rem' }}>
          <Stat label="Failed payouts" value={failed.length} hint="need a decision" />
          <Stat label="Payout in progress" value={approved.length} hint={formatMoney(approved.reduce((sum, claim) => sum + (claim.approvedAmount ?? 0), 0))} />
          <Stat label="Paid (claims)" value={paid.length} hint={formatMoney(paid.reduce((sum, claim) => sum + (claim.approvedAmount ?? 0), 0))} />
          <Stat label="Ledger: transfers" value={ledger ? ledger.payoutsIssued : '—'} hint={ledger ? `${formatMoney(ledger.totalIssued)} · ${ledger.payoutsFailed} failed · ${ledger.payoutsReversed} reversed` : ledgerNote ?? '…'} />
        </div>
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap">
          <h2>Failed payouts</h2>
          <table>
            <thead><tr><th>Claim</th><th>Reason</th><th className="num">Amount</th><th>Retry</th></tr></thead>
            <tbody>
              {failed.map((claim) => <FailedRow key={claim.id} claim={claim} onChange={refresh} onError={setError} />)}
              {failed.length === 0 && <tr><td colSpan={4} className="empty">No failed payouts.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="card table-wrap">
          <h2>Payment ledger (payout-service)</h2>
          {ledgerNote && <Alert kind="info">{ledgerNote}</Alert>}
          {ledger && (
            <table>
              <thead><tr><th>Claim</th><th className="num">Reservation</th><th>Reservation state</th><th className="num">Transfer</th><th>Transfer state</th><th>Reference</th><th>Updated</th></tr></thead>
              <tbody>
                {ledger.entries.map((entry) => (
                  <tr key={entry.claimId}>
                    <td><Link href={`/claims/${entry.claimId}`} className="mono small">{entry.claimId.slice(0, 8)}…</Link></td>
                    <td className="num">{formatMoney(entry.reservedAmount)}</td>
                    <td><span className={`badge ${entry.reservationStatus === 'SETTLED' ? 'ok' : entry.reservationStatus === 'RELEASED' ? '' : 'info'}`}>{entry.reservationStatus}</span></td>
                    <td className="num">{formatMoney(entry.payoutAmount)}</td>
                    <td>{entry.payoutStatus ? <span className={`badge ${entry.payoutStatus === 'ISSUED' ? 'ok' : entry.payoutStatus === 'FAILED' ? 'bad' : 'info'}`}>{entry.payoutStatus}</span> : '—'}{entry.reason && <div className="muted small">{entry.reason}</div>}</td>
                    <td className="mono small">{entry.reference ?? '—'}</td>
                    <td className="nowrap">{formatDateTime(entry.updatedAt)}</td>
                  </tr>
                ))}
                {ledger.entries.length === 0 && <tr><td colSpan={7} className="empty">The ledger is empty.</td></tr>}
              </tbody>
            </table>
          )}
        </div>
        <div className="card table-wrap">
          <h2>Paid</h2>
          <table>
            <thead><tr><th>Claim</th><th>Policy</th><th className="num">Amount</th><th>Reference</th><th>Date</th><th>Status</th></tr></thead>
            <tbody>
              {paid.map((claim) => <tr key={claim.id}><td><Link href={`/claims/${claim.id}`}>{claim.claimNumber}</Link></td><td>{claim.policyNumber}</td><td className="num">{formatMoney(claim.approvedAmount)}</td><td className="mono small">{claim.payoutReference ?? '—'}</td><td className="nowrap">{formatDateTime(claim.paidAt ?? claim.updatedAt)}</td><td><StatusBadge status={claim.status} /></td></tr>)}
              {paid.length === 0 && <tr><td colSpan={6} className="empty">None.</td></tr>}
            </tbody>
          </table>
        </div>
      </Shell>
    </RequireRole>
  );
}
