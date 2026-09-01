'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, formatMoney, useErrorState } from '../components/ui';
import type { Policy } from '../types';

export default function MyPolicies() {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [filter, setFilter] = useState('');
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => {
    try { setPolicies(await api.myPolicies()); clearError(); } catch (candidate) { setError(candidate); }
  }, [setError, clearError]);
  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADMIN']}>
      <Shell title="My policies" subtitle="Coverage you can claim against — sums insured, deductibles and validity">
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap">
          <div className="toolbar">
            <input className="inline-input" style={{ width: 240 }} placeholder="🔍 policy number, coverage…" value={filter} onChange={(event) => setFilter(event.target.value)} />
          </div>
          <table>
            <thead><tr><th>Policy</th><th>Coverage</th><th>Valid</th><th className="num">Sum insured</th><th className="num">Deductible</th><th>Status</th></tr></thead>
            <tbody>
              {policies.filter((policy) => (policy.policyNumber + ' ' + policy.coverageType).toLowerCase().includes(filter.toLowerCase())).map((policy) => (
                <tr key={policy.policyNumber}>
                  <td><strong className="mono">{policy.policyNumber}</strong></td>
                  <td><span className="badge info">{policy.coverageType}</span> <span className="muted small">{policy.coverageType === 'AC' ? 'comprehensive' : 'third-party liability'}</span></td>
                  <td className="nowrap">{policy.validFrom} → {policy.validTo}</td>
                  <td className="num">{formatMoney(policy.sumInsured)}</td>
                  <td className="num">{policy.deductible > 0 ? formatMoney(policy.deductible) : <span className="faint">none</span>}</td>
                  <td>{policy.active ? <span className="badge ok">active</span> : <span className="badge bad">expired</span>}</td>
                </tr>
              ))}
              {policies.length === 0 && <tr><td colSpan={6} className="empty">No policies are registered to your account.</td></tr>}
            </tbody>
          </table>
          <p className="muted small" style={{ marginTop: '0.6rem' }}>
            A claim must name one of these policies, the incident date must fall inside the validity window, and the payout
            is capped at the sum insured less the deductible.
          </p>
        </div>
      </Shell>
    </RequireRole>
  );
}
