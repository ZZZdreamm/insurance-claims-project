'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '../../api';
import { RequireRole, useAuth } from '../../auth';
import { Shell } from '../../components/Shell';
import { ClaimActions } from '../../components/ClaimActions';
import { Alert, Photos, SeverityBadge, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../../components/ui';
import type { Claim, ClaimEventLogEntry, LedgerEntry } from '../../types';

const EVENT_LABEL: Record<string, string> = {
  CLAIM_SUBMITTED: 'Claim submitted', ASSESSMENT_COMPLETED: 'Automated assessment completed', REVIEW_CLAIMED: 'Adjuster took the review',
  REVIEW_UNCLAIMED: 'Adjuster released the review', REVIEW_SLA_BREACHED: 'Review SLA breached', CLAIM_APPROVED: 'Approved', CLAIM_REJECTED: 'Rejected',
  CLAIM_PAID: 'Paid', PAYOUT_FAILED: 'Payout failed', PAYOUT_UNACCEPTED: 'Payout not accepted by the claims system', CLAIM_WITHDRAWN: 'Withdrawn',
};

export default function ClaimDetail() {
  const { id } = useParams<{ id: string }>();
  const { has } = useAuth();
  const [claim, setClaim] = useState<Claim | null>(null);
  const [timeline, setTimeline] = useState<ClaimEventLogEntry[] | null>(null);
  const [ledger, setLedger] = useState<LedgerEntry | null>(null);
  const [error, setError, clearError] = useErrorState();
  const [note, setNote] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setClaim(await api.claim(id)); clearError();
      if (has('ADJUSTER', 'FINANCE', 'ADMIN')) api.timeline(id).then(setTimeline).catch(() => setNote('The timeline needs search-service running (profile search).'));
      if (has('FINANCE', 'ADMIN')) api.ledgerEntry(id).then(setLedger).catch(() => setLedger(null));
    } catch (candidate) { setError(candidate); }
  }, [id, has, setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADJUSTER', 'FINANCE', 'ADMIN']}>
      <Shell title={claim ? `Claim ${claim.claimNumber}` : 'Claim'} subtitle={claim && <>{claim.plateNumber} · policy {claim.policyNumber} · incident {claim.incidentDate}</>}
             actions={claim && <><StatusBadge status={claim.status} /><SeverityBadge severity={claim.severity} /></>}>
        {error && <Alert kind="error">{error}</Alert>}
        {claim && (
          <div className="grid cols-2">
            <div>
              <ClaimActions claim={claim} onChange={refresh} />
              <div className="card">
                <h2>Description and photos</h2>
                <p>{claim.description}</p>
                <Photos claim={claim} large />
              </div>
              <div className="card">
                <h2>Automated assessment</h2>
                {claim.severity ? (
                  <dl className="kv">
                    <dt>Severity</dt><dd><SeverityBadge severity={claim.severity} /> {claim.assessmentScore != null && <span className="muted small">score {claim.assessmentScore}</span>}</dd>
                    <dt>Assessed amount</dt><dd>{formatMoney(claim.estimatedAmount)}</dd>
                    <dt>Model</dt><dd className="mono">{claim.assessmentProvider}</dd>
                    <dt>When</dt><dd>{formatDateTime(claim.assessedAt)}</dd>
                    <dt>Why</dt><dd>{claim.assessmentExplanation ? claim.assessmentExplanation.split(', ').map((reason) => <span key={reason} className="badge" style={{ marginRight: 4, marginBottom: 4 }}>{reason}</span>) : <span className="faint">no details (fallback assessment)</span>}</dd>
                  </dl>
                ) : <p className="muted">Assessment in progress…</p>}
              </div>
              <div className="card">
                <h2>Decision and payout</h2>
                <dl className="kv">
                  <dt>Adjuster</dt><dd>{claim.reviewAssignee ?? '—'}{claim.escalated && <span className="badge bad" style={{ marginLeft: 6 }}>SLA breached</span>}</dd>
                  <dt>Review due</dt><dd>{formatDateTime(claim.reviewDueAt)}</dd>
                  <dt>Approved amount</dt><dd>{formatMoney(claim.approvedAmount)}</dd>
                  {claim.rejectionReason && <><dt>Rejection reason</dt><dd>{claim.rejectionReason}</dd></>}
                  {claim.payoutFailureReason && <><dt>Payout failed</dt><dd className="badge bad">{claim.payoutFailureReason}</dd></>}
                  <dt>Paid</dt><dd>{claim.paidAt ? <>{formatDateTime(claim.paidAt)} · ref <span className="mono">{claim.payoutReference ?? '—'}</span></> : '—'}</dd>
                </dl>
                {ledger && (
                  <>
                    <h3 style={{ marginTop: '1rem' }}>Payment ledger (payout-service)</h3>
                    <dl className="kv">
                      <dt>Reservation</dt><dd>{formatMoney(ledger.reservedAmount)} · <span className="badge info">{ledger.reservationStatus}</span></dd>
                      <dt>Transfer</dt><dd>{ledger.payoutStatus ? <>{formatMoney(ledger.payoutAmount)} · <span className={`badge ${ledger.payoutStatus === 'ISSUED' ? 'ok' : ledger.payoutStatus === 'FAILED' ? 'bad' : 'info'}`}>{ledger.payoutStatus}</span></> : '—'}</dd>
                      {ledger.reference && <><dt>Reference</dt><dd className="mono">{ledger.reference}</dd></>}
                      {ledger.reason && <><dt>Reason</dt><dd>{ledger.reason}</dd></>}
                    </dl>
                  </>
                )}
              </div>
            </div>
            <div>
              <div className="card">
                <h2>Timeline</h2>
                {note && <Alert kind="info">{note}</Alert>}
                {timeline ? (
                  <ul className="timeline">
                    {timeline.map((entry) => (
                      <li key={entry.eventId}>
                        <div className="when">{formatDateTime(entry['@timestamp'])} · #{entry.sequence}</div>
                        <div><strong>{EVENT_LABEL[entry.eventType] ?? entry.eventType}</strong> <span className="faint small mono">{entry.eventType}</span></div>
                        <div className="muted small">
                          {entry.status && <>status {entry.status}</>}{entry.severity && <> · severity {entry.severity}</>}{entry.reviewAssignee && <> · {entry.reviewAssignee}</>}{entry.approvedAmount != null && <> · {formatMoney(entry.approvedAmount)}</>}
                        </div>
                      </li>
                    ))}
                    {timeline.length === 0 && <li className="muted">No events in the index (yet).</li>}
                  </ul>
                ) : !has('ADJUSTER', 'FINANCE', 'ADMIN') ? (
                  <dl className="kv">
                    <dt>Submitted</dt><dd>{formatDateTime(claim.createdAt)}</dd>
                    <dt>Assessed</dt><dd>{formatDateTime(claim.assessedAt)}</dd>
                    <dt>Last change</dt><dd>{formatDateTime(claim.updatedAt)}</dd>
                    <dt>Paid</dt><dd>{formatDateTime(claim.paidAt)}</dd>
                  </dl>
                ) : <p className="muted">Loading…</p>}
              </div>
            </div>
          </div>
        )}
      </Shell>
    </RequireRole>
  );
}
