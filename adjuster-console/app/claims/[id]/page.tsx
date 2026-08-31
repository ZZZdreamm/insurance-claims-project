'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '../../api';
import { RequireRole, useAuth } from '../../auth';
import { Shell } from '../../components/Shell';
import { ClaimActions } from '../../components/ClaimActions';
import { Alert, FraudBadges, Photos, SeverityBadge, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../../components/ui';
import type { Claim, ClaimEventLogEntry, ClaimPayment, CustomerCommunication, LedgerEntry, SubrogationCase } from '../../types';

const EVENT_LABEL: Record<string, string> = {
  CLAIM_SUBMITTED: 'Claim submitted', ASSESSMENT_COMPLETED: 'Automated assessment completed', REVIEW_CLAIMED: 'Adjuster took the review',
  REVIEW_UNCLAIMED: 'Adjuster released the review', REVIEW_SLA_BREACHED: 'Review SLA breached', CLAIM_APPROVED: 'Approved', CLAIM_REJECTED: 'Rejected',
  CLAIM_PAID: 'Paid', PAYOUT_FAILED: 'Payout failed', PAYOUT_UNACCEPTED: 'Payout not accepted by the claims system', CLAIM_WITHDRAWN: 'Withdrawn',
  SECOND_APPROVAL_REQUESTED: 'Parked for second approval (four-eyes)', CLAIM_PARTIALLY_PAID: 'Advance paid',
  SUBROGATION_OPENED: 'Recovery case opened (subrogation)', SUBROGATION_RECOVERY_RECORDED: 'Recovery payment received', SUBROGATION_CLOSED: 'Recovery case closed',
};

export default function ClaimDetail() {
  const { id } = useParams<{ id: string }>();
  const { has } = useAuth();
  const [claim, setClaim] = useState<Claim | null>(null);
  const [timeline, setTimeline] = useState<ClaimEventLogEntry[] | null>(null);
  const [ledger, setLedger] = useState<LedgerEntry | null>(null);
  const [payments, setPayments] = useState<ClaimPayment[]>([]);
  const [communications, setCommunications] = useState<CustomerCommunication[]>([]);
  const [subrogation, setSubrogation] = useState<SubrogationCase | null>(null);
  const [openComm, setOpenComm] = useState<string | null>(null);
  const [error, setError, clearError] = useErrorState();
  const [note, setNote] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setClaim(await api.claim(id)); clearError();
      api.payments(id).then(setPayments).catch(() => setPayments([]));
      api.communications(id).then(setCommunications).catch(() => setCommunications([]));
      if (has('ADJUSTER', 'FINANCE', 'ADMIN')) api.subrogationOf(id).then(setSubrogation).catch(() => setSubrogation(null));
      if (has('ADJUSTER', 'FINANCE', 'ADMIN')) api.timeline(id).then(setTimeline).catch(() => setNote('The timeline needs search-service running (profile search).'));
      if (has('FINANCE', 'ADMIN')) api.ledgerEntry(id).then(setLedger).catch(() => setLedger(null));
    } catch (candidate) { setError(candidate); }
  }, [id, has, setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADJUSTER', 'FINANCE', 'ADMIN']}>
      <Shell title={claim ? `Claim ${claim.claimNumber}` : 'Claim'} subtitle={claim && <>{claim.plateNumber} · policy {claim.policyNumber} · incident {claim.incidentDate}</>}
             actions={claim && <><StatusBadge status={claim.status} /><SeverityBadge severity={claim.severity} /><FraudBadges flags={claim.fraudFlags} /></>}>
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
                  {claim.grossApprovedAmount != null && (
                    <>
                      <dt>Awarded (gross)</dt><dd>{formatMoney(claim.grossApprovedAmount)}</dd>
                      <dt>Deductible</dt><dd>{claim.deductibleApplied && claim.deductibleApplied > 0 ? <>− {formatMoney(claim.deductibleApplied)}</> : <span className="faint">none</span>}</dd>
                      <dt>Payable</dt><dd><strong>{formatMoney(claim.payableAmount)}</strong>{claim.grossApprovedAmount !== claim.payableAmount && claim.deductibleApplied != null && claim.grossApprovedAmount - claim.deductibleApplied !== claim.payableAmount && <span className="muted small"> (capped at the sum insured)</span>}</dd>
                      <dt>Paid so far</dt><dd>{formatMoney(claim.paidAmount)}</dd>
                    </>
                  )}
                  <dt>Current payout cycle</dt><dd>{formatMoney(claim.approvedAmount)}</dd>
                  {claim.firstApprover && <><dt>First approver</dt><dd>{claim.firstApprover}</dd></>}
                  {claim.rejectionReason && <><dt>Rejection reason</dt><dd>{claim.rejectionReason}</dd></>}
                  {claim.payoutFailureReason && <><dt>Payout failed</dt><dd className="badge bad">{claim.payoutFailureReason}</dd></>}
                  <dt>Paid</dt><dd>{claim.paidAt ? <>{formatDateTime(claim.paidAt)} · ref <span className="mono">{claim.payoutReference ?? '—'}</span></> : '—'}</dd>
                </dl>
                {payments.length > 0 && (
                  <>
                    <h3 style={{ marginTop: '1rem' }}>Payments to the policyholder</h3>
                    <table>
                      <thead><tr><th>Type</th><th className="num">Amount</th><th>Reference</th><th>Issued</th></tr></thead>
                      <tbody>
                        {payments.map((payment) => (
                          <tr key={payment.id}>
                            <td><span className={`badge ${payment.paymentType === 'FINAL' ? 'ok' : 'info'}`}>{payment.paymentType}</span></td>
                            <td className="num">{formatMoney(payment.amount)}</td>
                            <td className="mono small">{payment.reference ?? '—'}</td>
                            <td className="nowrap">{formatDateTime(payment.issuedAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </>
                )}
                {['APPROVED', 'PENDING_SECOND_APPROVAL', 'PARTIALLY_PAID', 'PAID', 'PAYOUT_FAILED', 'REJECTED'].includes(claim.status) && (
                  <div className="actions" style={{ marginTop: '0.8rem' }}>
                    <button className="btn" onClick={() => api.decisionDocumentBlob(claim.id).then((url) => window.open(url, '_blank')).catch(setError)}>
                      📄 Decision letter (PDF)
                    </button>
                  </div>
                )}
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
              {has('ADJUSTER', 'FINANCE', 'ADMIN') && ['PAID', 'PARTIALLY_PAID'].includes(claim.status) && !subrogation && (
                <SubrogationOpenCard claim={claim} onChange={refresh} onError={setError} />
              )}
              {subrogation && <SubrogationCard subrogation={subrogation} onChange={refresh} onError={setError} />}
              <div className="card">
                <h2>Communications to the customer</h2>
                {communications.length === 0 ? <p className="muted">Nothing sent yet.</p> : (
                  <ul className="timeline">
                    {communications.map((message) => (
                      <li key={message.id}>
                        <div className="when">{formatDateTime(message.sentAt)}</div>
                        <div>
                          <button className="btn sm" style={{ marginRight: 6 }} onClick={() => setOpenComm(openComm === message.id ? null : message.id)}>
                            {openComm === message.id ? '▾' : '▸'}
                          </button>
                          <strong>{message.subject}</strong>
                        </div>
                        {openComm === message.id && <pre className="small" style={{ whiteSpace: 'pre-wrap', marginTop: 6 }}>{message.body}</pre>}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
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

function SubrogationOpenCard({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (error: unknown) => void }) {
  const [liableParty, setLiableParty] = useState('');
  const [expected, setExpected] = useState(String(claim.paidAmount || ''));
  return (
    <div className="card">
      <h2>Recovery (subrogation)</h2>
      <p className="muted small">A third party is liable? Open a recovery case against their insurer for what we paid.</p>
      <div className="actions">
        <input className="inline-input" style={{ width: 200 }} placeholder="liable party / their insurer" value={liableParty} onChange={(event) => setLiableParty(event.target.value)} />
        <input className="inline-input" type="number" min="0.01" step="0.01" value={expected} onChange={(event) => setExpected(event.target.value)} />
        <button className="btn primary" disabled={!liableParty || !Number(expected)} onClick={() => api.openSubrogation(claim.id, liableParty, Number(expected)).then(onChange).catch(onError)}>Open recovery case</button>
      </div>
    </div>
  );
}

function SubrogationCard({ subrogation, onChange, onError }: { subrogation: SubrogationCase; onChange: () => Promise<void>; onError: (error: unknown) => void }) {
  const { has } = useAuth();
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  return (
    <div className="card">
      <h2>Recovery (subrogation)</h2>
      <dl className="kv">
        <dt>Liable party</dt><dd>{subrogation.liableParty}</dd>
        <dt>Status</dt><dd><span className={`badge ${subrogation.status === 'RECOVERED' ? 'ok' : subrogation.status === 'WRITTEN_OFF' ? 'bad' : 'info'}`}>{subrogation.status}</span>{subrogation.writeOffReason && <span className="muted small"> — {subrogation.writeOffReason}</span>}</dd>
        <dt>Recovered</dt><dd>{formatMoney(subrogation.recoveredAmount)} of {formatMoney(subrogation.expectedAmount)}</dd>
        <dt>Opened</dt><dd>{formatDateTime(subrogation.openedAt)} by {subrogation.openedBy}</dd>
      </dl>
      {subrogation.status === 'OPEN' && has('FINANCE', 'ADMIN') && (
        <div className="actions" style={{ marginTop: '0.6rem' }}>
          <input className="inline-input" type="number" min="0.01" step="0.01" placeholder="amount" value={amount} onChange={(event) => setAmount(event.target.value)} />
          <button className="btn sm primary" disabled={!Number(amount)} onClick={() => api.recordRecovery(subrogation.id, Number(amount)).then(onChange).catch(onError)}>Record recovery</button>
          <input className="inline-input" style={{ width: 160 }} placeholder="write-off reason" value={reason} onChange={(event) => setReason(event.target.value)} />
          <button className="btn sm danger" disabled={!reason} onClick={() => api.writeOffSubrogation(subrogation.id, reason).then(onChange).catch(onError)}>Write off</button>
        </div>
      )}
    </div>
  );
}
