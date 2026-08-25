'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import type { Claim } from '../types';

function FailedRow({ claim, onChange, onError }: { claim: Claim; onChange: () => Promise<void>; onError: (m: string) => void }) {
  const [amount, setAmount] = useState<string>(claim.approvedAmount?.toString() ?? '');
  const retry = () => api.retryPayout(claim.id, Number(amount) || undefined).then(onChange).catch((e) => onError((e as Error).message));
  return (
    <tr>
      <td><strong>{claim.claimNumber}</strong><br /><span className="muted">{claim.policyNumber}</span></td>
      <td className="escalated">{claim.payoutFailureReason}</td>
      <td>{claim.approvedAmount}</td>
      <td><div className="actions"><input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 110 }} /><button onClick={retry}>Ponów wypłatę</button></div></td>
    </tr>
  );
}

export default function Finance() {
  const [failed, setFailed] = useState<Claim[]>([]);
  const [paid, setPaid] = useState<Claim[]>([]);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => {
    try {
      const [f, p] = await Promise.all([api.claimsByStatus('PAYOUT_FAILED'), api.claimsByStatus('PAID')]);
      setFailed(f.content); setPaid(p.content); setError(null);
    } catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => {
    void refresh();
    const id = setInterval(() => void refresh(), 5000);
    return () => clearInterval(id);
  }, [refresh]);

  return (
    <RequireRole roles={['FINANCE', 'ADMIN']}>
      <Shell title="Wypłaty">
        {error && <p className="error">{error}</p>}
        <h2>Nieudane wypłaty ({failed.length})</h2>
        <table>
          <thead><tr><th>Szkoda</th><th>Powód</th><th>Kwota</th><th>Ponów</th></tr></thead>
          <tbody>
            {failed.map((c) => <FailedRow key={c.id} claim={c} onChange={refresh} onError={setError} />)}
            {failed.length === 0 && <tr><td colSpan={4} className="muted">Brak.</td></tr>}
          </tbody>
        </table>
        <h2>Wypłacone ({paid.length})</h2>
        <table>
          <thead><tr><th>Szkoda</th><th>Polisa</th><th>Kwota</th><th>Data</th></tr></thead>
          <tbody>
            {paid.map((c) => <tr key={c.id}><td>{c.claimNumber}</td><td>{c.policyNumber}</td><td>{c.approvedAmount}</td><td>{new Date(c.updatedAt).toLocaleString()}</td></tr>)}
            {paid.length === 0 && <tr><td colSpan={4} className="muted">Brak.</td></tr>}
          </tbody>
        </table>
      </Shell>
    </RequireRole>
  );
}
