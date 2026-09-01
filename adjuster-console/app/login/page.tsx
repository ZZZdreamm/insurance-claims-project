'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { homeFor, useAuth } from '../auth';
import { Alert } from '../components/ui';

const DEMO: [string, string][] = [
  ['anna', 'policyholder'],
  ['alice', 'adjuster'],
  ['finance', 'finance desk'],
  ['admin', 'administrator'],
];

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
      <aside className="login-hero">
        <div className="brand"><span className="logo">C</span> Claims Platform</div>
        <div>
          <h1 className="headline">Motor claims,<br />from crash to cash.</h1>
          <p className="sub">
            Submit a claim with photos, let the vision model triage it, and follow it live through review,
            approval and payout — every step event-driven, audited and reversible.
          </p>
          <ul>
            <li>ML damage triage in seconds, human decision always</li>
            <li>Policy-aware settlements: sum insured, deductible, advances</li>
            <li>Four-eyes approvals, fraud screening, subrogation</li>
            <li>Full communication trail and PDF decision letters</li>
          </ul>
        </div>
        <div className="foot">Event-driven claims platform · Spring Boot · Kafka · Elasticsearch · ONNX</div>
      </aside>
      <div className="login-form-pane">
        <form className="card login-card" onSubmit={submit}>
          <div>
            <h1>Sign in</h1>
            <p className="muted small" style={{ margin: '0.2rem 0 0' }}>Use one of the demo accounts below.</p>
          </div>
          <label className="field">Username<input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" /></label>
          <label className="field">Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" /></label>
          <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          {error && <Alert kind="error">{error}</Alert>}
          <div className="small muted">Demo accounts (password = username)</div>
          <div className="demo-chips">
            {DEMO.map(([user, role]) => (
              <button key={user} type="button" className="btn" onClick={() => { setUsername(user); setPassword(user); }}>
                <span className="who">{user}</span><span className="role">{role}</span>
              </button>
            ))}
          </div>
        </form>
      </div>
    </div>
  );
}
