'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { api } from '../../api';
import { RequireRole, useAuth } from '../../auth';
import { Shell } from '../../components/Shell';
import { Alert, Photos, SeverityBadge, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../../components/ui';
import type { Claim, ClaimEventLogEntry, LedgerEntry } from '../../types';

const EVENT_LABEL: Record<string, string> = {
  CLAIM_SUBMITTED: 'Zgłoszenie przyjęte', ASSESSMENT_COMPLETED: 'Ocena automatyczna zakończona', REVIEW_CLAIMED: 'Likwidator przejął sprawę',
  REVIEW_UNCLAIMED: 'Likwidator oddał sprawę', REVIEW_SLA_BREACHED: 'Przekroczono SLA oceny', CLAIM_APPROVED: 'Zatwierdzono', CLAIM_REJECTED: 'Odrzucono',
  CLAIM_PAID: 'Wypłacono', PAYOUT_FAILED: 'Wypłata nieudana', PAYOUT_UNACCEPTED: 'Wypłata odrzucona przez system szkód', CLAIM_WITHDRAWN: 'Wycofano',
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
      if (has('ADJUSTER', 'FINANCE', 'ADMIN')) api.timeline(id).then(setTimeline).catch(() => setNote('Oś czasu wymaga uruchomionego search-service (profil search).'));
      if (has('FINANCE', 'ADMIN')) api.ledgerEntry(id).then(setLedger).catch(() => setLedger(null));
    } catch (candidate) { setError(candidate); }
  }, [id, has, setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADJUSTER', 'FINANCE', 'ADMIN']}>
      <Shell title={claim ? `Szkoda ${claim.claimNumber}` : 'Szkoda'} subtitle={claim && <>{claim.plateNumber} · polisa {claim.policyNumber} · zdarzenie {claim.incidentDate}</>}
             actions={claim && <><StatusBadge status={claim.status} /><SeverityBadge severity={claim.severity} /></>}>
        {error && <Alert kind="error">{error}</Alert>}
        {claim && (
          <div className="grid cols-2">
            <div>
              <div className="card">
                <h2>Opis i zdjęcia</h2>
                <p>{claim.description}</p>
                <Photos claim={claim} large />
              </div>
              <div className="card">
                <h2>Ocena automatyczna</h2>
                {claim.severity ? (
                  <dl className="kv">
                    <dt>Powaga</dt><dd><SeverityBadge severity={claim.severity} /> {claim.assessmentScore != null && <span className="muted small">score {claim.assessmentScore}</span>}</dd>
                    <dt>Kwota po ocenie</dt><dd>{formatMoney(claim.estimatedAmount)}</dd>
                    <dt>Model</dt><dd className="mono">{claim.assessmentProvider}</dd>
                    <dt>Kiedy</dt><dd>{formatDateTime(claim.assessedAt)}</dd>
                    <dt>Dlaczego</dt><dd>{claim.assessmentExplanation ? claim.assessmentExplanation.split(', ').map((reason) => <span key={reason} className="badge" style={{ marginRight: 4, marginBottom: 4 }}>{reason}</span>) : <span className="faint">brak szczegółów (ocena awaryjna)</span>}</dd>
                  </dl>
                ) : <p className="muted">Ocena w toku…</p>}
              </div>
              <div className="card">
                <h2>Decyzja i wypłata</h2>
                <dl className="kv">
                  <dt>Likwidator</dt><dd>{claim.reviewAssignee ?? '—'}{claim.escalated && <span className="badge bad" style={{ marginLeft: 6 }}>SLA przekroczone</span>}</dd>
                  <dt>Termin oceny</dt><dd>{formatDateTime(claim.reviewDueAt)}</dd>
                  <dt>Zatwierdzona kwota</dt><dd>{formatMoney(claim.approvedAmount)}</dd>
                  {claim.rejectionReason && <><dt>Powód odrzucenia</dt><dd>{claim.rejectionReason}</dd></>}
                  {claim.payoutFailureReason && <><dt>Wypłata nieudana</dt><dd className="badge bad">{claim.payoutFailureReason}</dd></>}
                  <dt>Wypłacono</dt><dd>{claim.paidAt ? <>{formatDateTime(claim.paidAt)} · ref. <span className="mono">{claim.payoutReference ?? '—'}</span></> : '—'}</dd>
                </dl>
                {ledger && (
                  <>
                    <h3 style={{ marginTop: '1rem' }}>Księga płatności (payout-service)</h3>
                    <dl className="kv">
                      <dt>Rezerwacja</dt><dd>{formatMoney(ledger.reservedAmount)} · <span className="badge info">{ledger.reservationStatus}</span></dd>
                      <dt>Przelew</dt><dd>{ledger.payoutStatus ? <>{formatMoney(ledger.payoutAmount)} · <span className={`badge ${ledger.payoutStatus === 'ISSUED' ? 'ok' : ledger.payoutStatus === 'FAILED' ? 'bad' : 'info'}`}>{ledger.payoutStatus}</span></> : '—'}</dd>
                      {ledger.reference && <><dt>Referencja</dt><dd className="mono">{ledger.reference}</dd></>}
                      {ledger.reason && <><dt>Powód</dt><dd>{ledger.reason}</dd></>}
                    </dl>
                  </>
                )}
              </div>
            </div>
            <div>
              <div className="card">
                <h2>Oś czasu</h2>
                {note && <Alert kind="info">{note}</Alert>}
                {timeline ? (
                  <ul className="timeline">
                    {timeline.map((entry) => (
                      <li key={entry.eventId}>
                        <div className="when">{formatDateTime(entry['@timestamp'])} · #{entry.sequence}</div>
                        <div><strong>{EVENT_LABEL[entry.eventType] ?? entry.eventType}</strong> <span className="faint small mono">{entry.eventType}</span></div>
                        <div className="muted small">
                          {entry.status && <>status {entry.status}</>}{entry.severity && <> · powaga {entry.severity}</>}{entry.reviewAssignee && <> · {entry.reviewAssignee}</>}{entry.approvedAmount != null && <> · {formatMoney(entry.approvedAmount)}</>}
                        </div>
                      </li>
                    ))}
                    {timeline.length === 0 && <li className="muted">Brak zdarzeń w indeksie (jeszcze).</li>}
                  </ul>
                ) : !has('ADJUSTER', 'FINANCE', 'ADMIN') ? (
                  <dl className="kv">
                    <dt>Zgłoszono</dt><dd>{formatDateTime(claim.createdAt)}</dd>
                    <dt>Oceniono</dt><dd>{formatDateTime(claim.assessedAt)}</dd>
                    <dt>Ostatnia zmiana</dt><dd>{formatDateTime(claim.updatedAt)}</dd>
                    <dt>Wypłacono</dt><dd>{formatDateTime(claim.paidAt)}</dd>
                  </dl>
                ) : <p className="muted">Ładowanie…</p>}
              </div>
            </div>
          </div>
        )}
      </Shell>
    </RequireRole>
  );
}
