'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../api';
import { RequireRole, useAuth } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Pager, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { ClaimStatus, SearchResult } from '../types';

const STATUSES: ClaimStatus[] = ['SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'PAID', 'PAYOUT_FAILED', 'WITHDRAWN'];

export default function Search() {
  const [queryText, setQueryText] = useState('');
  const [status, setStatus] = useState('');
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError, clearError] = useErrorState();
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(0);
  const { has } = useAuth();
  const router = useRouter();
  const takeAndReview = async (claimId: string) => {
    try { await api.claimReview(claimId); router.push(`/claims/${claimId}`); } catch (candidate) { setError(candidate); }
  };
  const run = async (event?: FormEvent, nextPage = 0) => {
    event?.preventDefault(); setBusy(true); setPage(nextPage);
    try { setResult(await api.search(queryText, status || undefined, nextPage)); clearError(); } catch (candidate) { setError(candidate); } finally { setBusy(false); }
  };
  return (
    <RequireRole roles={['ADJUSTER', 'FINANCE', 'ADMIN']}>
      <Shell title="Search" subtitle="Elasticsearch: fuzzy matching on plate, policy, claim number and description. Results can lag the claim state by a few seconds — actions run on live data.">
        <form className="card toolbar" onSubmit={run}>
          <label className="field" style={{ flex: 1, minWidth: 240 }}>Query<input value={queryText} onChange={(event) => setQueryText(event.target.value)} placeholder="e.g. WA12354, POL-2026, bumper" /></label>
          <label className="field">Status<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">any</option>{STATUSES.map((candidate) => <option key={candidate} value={candidate}>{candidate}</option>)}</select></label>
          <button className="btn primary" type="submit" disabled={busy}>Search</button>
        </form>
        {error && <Alert kind="error">{error}</Alert>}
        {result && (
          <div className="card table-wrap">
            <h2>{result.total} results</h2>
            <table>
              <thead><tr><th>Claim</th><th>Plate / policy</th><th>Description</th><th>Status</th><th className="num">Amount</th><th>Last event</th><th>Action</th></tr></thead>
              <tbody>
                {result.items.map((document) => (
                  <tr key={document.claimId}>
                    <td><Link href={`/claims/${document.claimId}`}><strong>{document.claimNumber}</strong></Link></td>
                    <td>{document.plateNumber}<br /><span className="muted small">{document.policyNumber}</span></td>
                    <td>{document.description}</td>
                    <td><StatusBadge status={document.status} /></td>
                    <td className="num">{formatMoney(document.approvedAmount ?? document.estimatedAmount)}</td>
                    <td className="nowrap"><span className="mono small">{document.lastEventType}</span><br /><span className="muted small">{formatDateTime(document.lastEventAt)}</span></td>
                    <td>
                      <div className="actions">
                        {document.status === 'PENDING_REVIEW' && has('ADJUSTER', 'ADMIN') && (
                          <button className="btn sm primary" onClick={() => takeAndReview(document.claimId)}>Take and review</button>)}
                        {document.status === 'PAYOUT_FAILED' && has('FINANCE', 'ADMIN') && (
                          <Link className="btn sm primary" href={`/claims/${document.claimId}`}>Retry payout</Link>)}
                        <Link className="btn sm" href={`/claims/${document.claimId}`}>Details</Link>
                      </div>
                    </td>
                  </tr>
                ))}
                {result.items.length === 0 && <tr><td colSpan={7} className="empty">Nothing found.</td></tr>}
              </tbody>
            </table>
            <Pager page={page} totalPages={Math.ceil(result.total / result.size)} onPage={(next) => void run(undefined, next)} />
          </div>
        )}
      </Shell>
    </RequireRole>
  );
}
