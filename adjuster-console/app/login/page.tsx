'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { homeFor, useAuth } from '../auth';
import { Alert } from '../components/ui';

const DEMO: [string, string][] = [['anna', 'policyholder'], ['alice', 'adjuster'], ['finance', 'finance desk'], ['admin', 'administrator']];

export default function Login() {
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState('anna');
  const [password, setPassword] = useState('anna');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError(null);
    try { const session = await login(username, password); router.replace(homeFor(session.user.roles)); }
    catch (candidate) { setError((candidate as Error).message); }
    finally { setBusy(false); }
  };

  return (
    <div className="login-page">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand"><span className="logo">C</span> Claims Platform</div>
        <label className="field">Username<input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" /></label>
        <label className="field">Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" /></label>
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        {error && <Alert kind="error">{error}</Alert>}
        <div className="small muted">
          Demo accounts (password = username):
          <div className="actions" style={{ marginTop: '0.4rem' }}>
            {DEMO.map(([user, role]) => <button key={user} type="button" className="btn sm" onClick={() => { setUsername(user); setPassword(user); }}>{user} · {role}</button>)}
          </div>
        </div>
      </form>
    </div>
  );
}
