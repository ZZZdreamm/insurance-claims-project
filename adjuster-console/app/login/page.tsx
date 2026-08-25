'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { homeFor, useAuth } from '../auth';

const DEMO = [
  ['anna', 'ubezpieczona (POLICYHOLDER)'],
  ['alice', 'likwidatorka (ADJUSTER)'],
  ['finance', 'dział wypłat (FINANCE)'],
  ['admin', 'administrator (ADMIN)'],
];

export default function Login() {
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState('anna');
  const [password, setPassword] = useState('anna');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = await login(username, password);
      router.replace(homeFor(s.user.roles));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login">
      <h1>Claims console</h1>
      <form onSubmit={submit} className="card">
        <label>Użytkownik <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" /></label>
        <label>Hasło <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
        <button type="submit" disabled={busy}>{busy ? 'Logowanie…' : 'Zaloguj'}</button>
        {error && <p className="error">{error}</p>}
      </form>
      <p className="muted small">Konta demo (hasło = login): {DEMO.map(([u, d]) => `${u} — ${d}`).join(' · ')}</p>
    </main>
  );
}
