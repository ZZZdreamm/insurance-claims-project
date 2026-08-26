'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { RequireRole } from '../auth';
import { Shell } from '../components/Shell';
import { Alert, Photos, SeverityBadge, StatusBadge, formatDateTime, formatMoney, useErrorState } from '../components/ui';
import type { Claim } from '../types';

function SubmitForm({ onDone, onError }: { onDone: () => void; onError: (error: unknown) => void }) {
  const [policy, setPolicy] = useState('POL-2026-0001');
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
      setDone(`Zgłoszenie ${claim.claimNumber} przyjęte — ocena automatyczna trwa kilka sekund.`);
      onDone();
    } catch (error) { onError(error); } finally { setBusy(false); }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>Zgłoś nową szkodę</h2>
      <div className="form-grid">
        <label className="field">Numer polisy<input value={policy} onChange={(event) => setPolicy(event.target.value)} required /></label>
        <label className="field">Tablica rejestracyjna<input value={plate} onChange={(event) => setPlate(event.target.value)} required /></label>
        <label className="field">Data zdarzenia<input type="date" value={date} onChange={(event) => setDate(event.target.value)} required /></label>
        <label className="field">Szacunkowa kwota (PLN)<input type="number" min="0" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
      </div>
      <label className="field" style={{ marginTop: '0.9rem' }}>Opis uszkodzeń
        <textarea value={description} onChange={(event) => setDescription(event.target.value)} minLength={10} required rows={3} placeholder="Co się stało i co jest uszkodzone (min. 10 znaków)" />
      </label>
      <label className="field" style={{ marginTop: '0.9rem' }}>Zdjęcia (JPEG/PNG/WebP, do 8 MB)
        <input type="file" accept="image/*" multiple onChange={(event) => setPhotos(Array.from(event.target.files ?? []))} />
      </label>
      <div className="actions" style={{ marginTop: '1rem' }}>
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Wysyłanie…' : 'Wyślij zgłoszenie'}</button>
        {photos.length > 0 && <span className="muted small">{photos.length} zdjęć</span>}
      </div>
      {done && <Alert kind="ok">{done}</Alert>}
    </form>
  );
}

export default function MyClaims() {
  const [claims, setClaims] = useState<Claim[]>([]);
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => { try { setClaims((await api.claims()).content); clearError(); } catch (candidate) { setError(candidate); } }, [setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 5000); return () => clearInterval(timer); }, [refresh]);

  const active = claims.filter((claim) => !['PAID', 'REJECTED', 'WITHDRAWN'].includes(claim.status));
  const paid = claims.filter((claim) => claim.status === 'PAID').reduce((sum, claim) => sum + (claim.approvedAmount ?? 0), 0);

  return (
    <RequireRole roles={['POLICYHOLDER', 'ADMIN']}>
      <Shell title="Moje szkody" subtitle="Zgłoszenia, ich status i wypłaty">
        <div className="grid cols-3" style={{ marginBottom: '1rem' }}>
          <div className="card stat"><div className="label">Wszystkie zgłoszenia</div><div className="value">{claims.length}</div></div>
          <div className="card stat"><div className="label">W toku</div><div className="value">{active.length}</div></div>
          <div className="card stat"><div className="label">Wypłacono łącznie</div><div className="value">{formatMoney(paid)}</div></div>
        </div>
        <SubmitForm onDone={() => void refresh()} onError={setError} />
        {error && <Alert kind="error">{error}</Alert>}
        <div className="card table-wrap" style={{ marginTop: '1rem' }}>
          <h2>Zgłoszenia</h2>
          <table>
            <thead><tr><th>Szkoda</th><th>Opis</th><th>Status</th><th>Ocena</th><th className="num">Kwota</th><th>Zgłoszono</th><th></th></tr></thead>
            <tbody>
              {claims.map((claim) => (
                <tr key={claim.id}>
                  <td><Link href={`/claims/${claim.id}`}><strong>{claim.claimNumber}</strong></Link><br /><span className="muted small">{claim.plateNumber} · {claim.policyNumber}</span></td>
                  <td>{claim.description}<Photos claim={claim} /></td>
                  <td><StatusBadge status={claim.status} />
                    {claim.rejectionReason && <div className="muted small">{claim.rejectionReason}</div>}
                    {claim.payoutFailureReason && <div className="muted small">wypłata: {claim.payoutFailureReason}</div>}
                    {claim.paidAt && <div className="muted small">wypłacono {formatDateTime(claim.paidAt)}{claim.payoutReference && <> · ref. <span className="mono">{claim.payoutReference}</span></>}</div>}</td>
                  <td><SeverityBadge severity={claim.severity} /></td>
                  <td className="num">{formatMoney(claim.approvedAmount ?? claim.estimatedAmount)}</td>
                  <td className="nowrap">{formatDateTime(claim.createdAt)}</td>
                  <td>{!['PAID', 'REJECTED', 'WITHDRAWN'].includes(claim.status) && (
                    <button className="btn sm danger" onClick={() => api.withdraw(claim.id).then(refresh).catch(setError)}>Wycofaj</button>)}</td>
                </tr>
              ))}
              {claims.length === 0 && <tr><td colSpan={7} className="empty">Brak zgłoszeń — użyj formularza powyżej.</td></tr>}
            </tbody>
          </table>
        </div>
      </Shell>
    </RequireRole>
  );
}
