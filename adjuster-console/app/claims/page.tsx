'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Photos } from '../components/Photos';
import type { Claim } from '../types';

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

function SubmitForm({ onDone, onError }: { onDone: () => void; onError: (m: string) => void }) {
  const [policy, setPolicy] = useState('POL-2026-0001');
  const [plate, setPlate] = useState('WA 12345');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [desc, setDesc] = useState('');
  const [amount, setAmount] = useState('');
  const [photos, setPhotos] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.submit({ policyNumber: policy, plateNumber: plate, incidentDate: date, description: desc, estimatedAmount: amount ? Number(amount) : null }, photos);
      setDesc(''); setAmount(''); setPhotos([]);
      onDone();
    } catch (err) {
      onError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>Zgłoś szkodę</h2>
      <div className="grid2">
        <label>Numer polisy <input value={policy} onChange={(e) => setPolicy(e.target.value)} required /></label>
        <label>Tablica rejestracyjna <input value={plate} onChange={(e) => setPlate(e.target.value)} required /></label>
        <label>Data zdarzenia <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required /></label>
        <label>Szacunkowa kwota (PLN) <input type="number" min="0" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
      </div>
      <label>Opis uszkodzeń <textarea value={desc} onChange={(e) => setDesc(e.target.value)} minLength={10} required rows={3} placeholder="Co się stało i co jest uszkodzone (min. 10 znaków)" /></label>
      <label>Zdjęcia <input type="file" accept="image/*" multiple onChange={(e) => setPhotos(Array.from(e.target.files ?? []))} /></label>
      <button type="submit" disabled={busy}>{busy ? 'Wysyłanie…' : 'Wyślij zgłoszenie'}</button>
    </form>
  );
}

function StatusChip({ c }: { c: Claim }) {
  const cls = c.status === 'PAID' ? 'ok' : c.status === 'PAYOUT_FAILED' || c.status === 'REJECTED' ? 'bad' : c.status === 'PENDING_REVIEW' ? 'warn' : '';
  return <span className={`badge ${cls}`}>{c.status}</span>;
}

export default function MyClaims() {
  const [claims, setClaims] = useState<Claim[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setClaims((await api.myClaims()).content);
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
    <RequireRole roles={['POLICYHOLDER', 'ADMIN']}>
      <Shell title="Moje szkody">
        <SubmitForm onDone={() => void refresh()} onError={setError} />
        {error && <p className="error">{error}</p>}
        <table>
          <thead><tr><th>Szkoda</th><th>Opis</th><th>Status</th><th>Kwota</th><th>Zgłoszono</th><th></th></tr></thead>
          <tbody>
            {claims.map((c) => (
              <tr key={c.id}>
                <td><strong>{c.claimNumber}</strong><br /><span className="muted">{c.plateNumber}</span></td>
                <td>{c.description}<Photos claim={c} /></td>
                <td><StatusChip c={c} />{c.severity && <><br /><span className={`badge ${c.severity}`}>{c.severity}</span></>}
                  {c.rejectionReason && <div className="muted small">{c.rejectionReason}</div>}
                  {c.payoutFailureReason && <div className="muted small">wypłata: {c.payoutFailureReason}</div>}</td>
                <td>{c.approvedAmount ?? c.estimatedAmount ?? '—'}</td>
                <td>{fmt(c.createdAt)}</td>
                <td>{!['PAID', 'REJECTED', 'WITHDRAWN'].includes(c.status) && (
                  <button onClick={() => api.withdraw(c.id).then(refresh).catch((e) => setError((e as Error).message))}>Wycofaj</button>)}</td>
              </tr>
            ))}
            {claims.length === 0 && <tr><td colSpan={6} className="muted">Brak zgłoszeń.</td></tr>}
          </tbody>
        </table>
      </Shell>
    </RequireRole>
  );
}
