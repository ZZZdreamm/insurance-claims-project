'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { ClaimStatus, SearchResult } from '../types';

const STATUSES: ClaimStatus[] = ['SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'PAID', 'PAYOUT_FAILED', 'WITHDRAWN'];

export default function Search() {
  const [queryText, setQueryText] = useState('');
  const [status, setStatus] = useState('');
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError, clearError] = useErrorState();
  const [busy, setBusy] = useState(false);
  const run = async (event?: FormEvent) => {
    event?.preventDefault(); setBusy(true);
    try { setResult(await api.search(queryText, status || undefined)); clearError(); } catch (candidate) { setError(candidate); } finally { setBusy(false); }
  };
  return (
    <RequireRole roles={['ADJUSTER', 'FINANCE', 'ADMIN']}>
      <Shell title="Wyszukiwarka" subtitle="Elasticsearch: rozmyte dopasowanie po tablicy, numerze polisy, numerze szkody i opisie (literówki dozwolone)">
        <form className="card toolbar" onSubmit={run}>
          <label className="field" style={{ flex: 1, minWidth: 240 }}>Fraza<input value={queryText} onChange={(event) => setQueryText(event.target.value)} placeholder="np. WA12354, POL-2026, zderzak" /></label>
          <label className="field">Status<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">dowolny</option>{STATUSES.map((candidate) => <option key={candidate} value={candidate}>{candidate}</option>)}</select></label>
          <button className="btn primary" type="submit" disabled={busy}>Szukaj</button>
        </form>
        {error && <Alert kind="error">{error}</Alert>}
        {result && (
          <div className="card table-wrap">
            <h2>{result.total} wyników</h2>
            <table>
              <thead><tr><th>Szkoda</th><th>Tablica / polisa</th><th>Opis</th><th>Status</th><th className="num">Kwota</th><th>Ostatnie zdarzenie</th></tr></thead>
              <tbody>
                {result.items.map((document) => (
                  <tr key={document.claimId}>
                    <td><Link href={`/claims/${document.claimId}`}><strong>{document.claimNumber}</strong></Link></td>
                    <td>{document.plateNumber}<br /><span className="muted small">{document.policyNumber}</span></td>
                    <td>{document.description}</td>
                    <td><StatusBadge status={document.status} /></td>
                    <td className="num">{formatMoney(document.approvedAmount ?? document.estimatedAmount)}</td>
                    <td className="nowrap"><span className="mono small">{document.lastEventType}</span><br /><span className="muted small">{formatDateTime(document.lastEventAt)}</span></td>
                  </tr>
                ))}
                {result.items.length === 0 && <tr><td colSpan={6} className="empty">Nic nie znaleziono.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </Shell>
    </RequireRole>
  );
}
