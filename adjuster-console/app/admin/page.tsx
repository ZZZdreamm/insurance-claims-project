'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api';
import { RequireRole, useAuth } from '../auth';
import { Shell } from '../components/Shell';
import { ToolLinks } from '../components/ToolLinks';
import { Alert, Stat, formatBytes, formatDuration, formatMoney, useErrorState } from '../components/ui';
import { ALL_ROLES, type Role, type Statistics, type Usage, type UserAccount } from '../types';

const STATUS_COLORS: Record<string, string> = { SUBMITTED: '#7c8a99', PENDING_REVIEW: '#e0a13a', APPROVED: '#4f7fd8', PAID: '#2e9e5b', REJECTED: '#c44a42', PAYOUT_FAILED: '#a12d26', WITHDRAWN: '#b0b8c2' };
const SEVERITY_COLORS: Record<string, string> = { MINOR: '#2e9e5b', MODERATE: '#e0a13a', SEVERE: '#c44a42' };
type Tab = 'dashboard' | 'usage' | 'users' | 'operations';

function Dashboard({ statistics }: { statistics: Statistics }) {
  const perDay = Object.entries(statistics.submittedPerDay).map(([day, count]) => ({ day: day.slice(5), count }));
  const byStatus = Object.entries(statistics.byStatus).filter(([, count]) => count > 0).map(([name, value]) => ({ name, value }));
  const bySeverity = Object.entries(statistics.bySeverity).filter(([, count]) => count > 0).map(([name, value]) => ({ name, value }));
  return (
    <>
      <div className="grid cols-4">
        <Stat label="Claims in total" value={statistics.totalClaims} hint={`${statistics.accounts} accounts`} />
        <Stat label="Pending review" value={statistics.openReviews} hint={`${statistics.escalatedReviews} past SLA`} />
        <Stat label="Paid" value={formatMoney(statistics.paidTotal)} hint={`${statistics.byStatus.PAID} claims · ${formatMoney(statistics.approvedAwaitingPayout)} in progress`} />
        <Stat label="Time to assessment / payment" value={formatDuration(statistics.averageSecondsToAssessment)} hint={`average to payment ${formatDuration(statistics.averageSecondsToPayment)}`} />
      </div>
      <div className="grid cols-2" style={{ marginTop: '1rem' }}>
        <div className="card"><h2>Submissions per day (14 days)</h2>
          <div className="chart"><ResponsiveContainer><BarChart data={perDay}><CartesianGrid strokeDasharray="3 3" stroke="var(--line)" /><XAxis dataKey="day" fontSize={11} /><YAxis allowDecimals={false} fontSize={11} /><Tooltip /><Bar dataKey="count" name="submissions" fill="var(--accent)" radius={[4, 4, 0, 0]} /></BarChart></ResponsiveContainer></div></div>
        <div className="card"><h2>Claims by status</h2>
          <div className="chart"><ResponsiveContainer><PieChart><Pie data={byStatus} dataKey="value" nameKey="name" innerRadius={55} outerRadius={95} paddingAngle={2}>{byStatus.map((entry) => <Cell key={entry.name} fill={STATUS_COLORS[entry.name] ?? '#999'} />)}</Pie><Legend /><Tooltip /></PieChart></ResponsiveContainer></div></div>
        <div className="card"><h2>Severity by ML assessment</h2>
          <div className="chart"><ResponsiveContainer><PieChart><Pie data={bySeverity} dataKey="value" nameKey="name" outerRadius={95}>{bySeverity.map((entry) => <Cell key={entry.name} fill={SEVERITY_COLORS[entry.name] ?? '#999'} />)}</Pie><Legend /><Tooltip /></PieChart></ResponsiveContainer></div></div>
        <div className="card"><h2>Statuses</h2>
          <table><tbody>{Object.entries(statistics.byStatus).map(([status, count]) => <tr key={status}><td><span className="badge" style={{ background: STATUS_COLORS[status] + '33', color: STATUS_COLORS[status] }}>{status}</span></td><td className="num">{count}</td></tr>)}</tbody></table></div>
      </div>
    </>
  );
}

function UsagePanel({ usage }: { usage: Usage }) {
  const transitions = Object.entries(usage.claimTransitions).map(([name, value]) => ({ name, value }));
  const heapPercent = usage.heapMaxBytes > 0 ? Math.round((usage.heapUsedBytes / usage.heapMaxBytes) * 100) : 0;
  return (
    <>
      <div className="grid cols-4">
        <Stat label="HTTP requests (since start)" value={usage.totalHttpRequests.toLocaleString('en-GB')} hint={`uptime ${formatDuration(usage.uptimeSeconds)}`} />
        <Stat label="CPU" value={`${Math.round(usage.cpuUsage * 100)}%`} hint="system" />
        <Stat label="JVM memory (heap)" value={formatBytes(usage.heapUsedBytes)} hint={<><div className="progress"><div style={{ width: `${heapPercent}%` }} /></div>{heapPercent}% of {formatBytes(usage.heapMaxBytes)}</>} />
        <Stat label="Outbox" value={usage.outboxPending} hint={`pending · published ${usage.outboxPublished.toLocaleString('en-GB')} · submissions ${usage.claimsSubmitted}`} />
      </div>
      <div className="grid cols-2" style={{ marginTop: '1rem' }}>
        <div className="card table-wrap"><h2>Endpoints by traffic</h2>
          <table><thead><tr><th>Endpoint</th><th className="num">Requests</th><th className="num">Avg ms</th><th className="num">Max ms</th><th className="num">5xx</th></tr></thead>
            <tbody>{usage.endpoints.slice(0, 15).map((endpoint) => <tr key={endpoint.method + endpoint.uri}><td className="mono small">{endpoint.method} {endpoint.uri}</td><td className="num">{endpoint.requests}</td><td className="num">{endpoint.averageMillis.toFixed(1)}</td><td className="num">{endpoint.maxMillis.toFixed(0)}</td><td className="num">{endpoint.errors}</td></tr>)}
              {usage.endpoints.length === 0 && <tr><td colSpan={5} className="empty">No traffic.</td></tr>}</tbody></table></div>
        <div>
          <div className="card"><h2>Status transitions (since start)</h2>
            <div className="chart" style={{ height: 200 }}><ResponsiveContainer><BarChart data={transitions} layout="vertical"><XAxis type="number" allowDecimals={false} fontSize={11} /><YAxis type="category" dataKey="name" width={130} fontSize={11} /><Tooltip /><Bar dataKey="value" name="transitions" fill="var(--accent)" radius={[0, 4, 4, 0]} /></BarChart></ResponsiveContainer></div></div>
          <div className="card table-wrap"><h2>Busiest clients (current minute)</h2>
            <table><thead><tr><th>Client</th><th className="num">Submissions</th></tr></thead><tbody>
              {usage.topClients.map((client) => <tr key={client.clientId}><td className="mono small">{client.clientId}</td><td className="num">{client.submissionsThisMinute}</td></tr>)}
              {usage.topClients.length === 0 && <tr><td colSpan={2} className="empty">Quiet.</td></tr>}</tbody></table></div>
        </div>
      </div>
    </>
  );
}

function UsersPanel({ onError }: { onError: (error: unknown) => void }) {
  const { session } = useAuth();
  const [users, setUsers] = useState<UserAccount[]>([]);
  const [form, setForm] = useState({ username: '', password: '', displayName: '', roles: ['POLICYHOLDER'] as Role[] });
  const refresh = useCallback(() => api.users().then(setUsers).catch(onError), [onError]);
  useEffect(() => { void refresh(); }, [refresh]);
  const create = async (event: FormEvent) => {
    event.preventDefault();
    try { await api.createUser(form); setForm({ username: '', password: '', displayName: '', roles: ['POLICYHOLDER'] }); await refresh(); } catch (candidate) { onError(candidate); }
  };
  const toggleRole = (user: UserAccount, role: Role) => {
    const roles = user.roles.includes(role) ? user.roles.filter((candidate) => candidate !== role) : [...user.roles, role];
    api.updateUser(user.id, { roles }).then(refresh).catch(onError);
  };
  const resetPassword = (user: UserAccount) => {
    const password = window.prompt(`New password for ${user.username}:`);
    if (password) api.updateUser(user.id, { password }).then(() => window.alert('Password changed')).catch(onError);
  };
  return (
    <div className="grid cols-2">
      <div className="card table-wrap"><h2>Accounts ({users.length})</h2>
        <table><thead><tr><th>User</th><th>Roles</th><th>State</th><th></th></tr></thead><tbody>
          {users.map((user) => (
            <tr key={user.id}>
              <td><strong>{user.displayName}</strong><br /><span className="muted small mono">{user.username}</span></td>
              <td><div className="actions">{ALL_ROLES.map((role) => <button key={role} className={`btn sm ${user.roles.includes(role) ? 'primary' : ''}`} onClick={() => toggleRole(user, role)} disabled={user.username === session?.user.username && role === 'ADMIN'}>{role}</button>)}</div></td>
              <td>{user.enabled ? <span className="badge ok">active</span> : <span className="badge bad">disabled</span>}</td>
              <td><div className="actions">
                <button className="btn sm" onClick={() => api.updateUser(user.id, { enabled: !user.enabled }).then(refresh).catch(onError)} disabled={user.username === session?.user.username}>{user.enabled ? 'Disable' : 'Enable'}</button>
                <button className="btn sm" onClick={() => resetPassword(user)}>Password</button></div></td>
            </tr>))}
        </tbody></table></div>
      <form className="card" onSubmit={create}><h2>New account</h2>
        <div className="form-grid">
          <label className="field">Username<input value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} required pattern="[a-z0-9._-]+" /></label>
          <label className="field">Password<input type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} required minLength={4} /></label>
        </div>
        <label className="field" style={{ marginTop: '0.9rem' }}>Display name<input value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} required /></label>
        <div className="field" style={{ marginTop: '0.9rem' }}>Roles<div className="actions">{ALL_ROLES.map((role) => <button type="button" key={role} className={`btn sm ${form.roles.includes(role) ? 'primary' : ''}`} onClick={() => setForm({ ...form, roles: form.roles.includes(role) ? form.roles.filter((candidate) => candidate !== role) : [...form.roles, role] })}>{role}</button>)}</div></div>
        <button className="btn primary" type="submit" style={{ marginTop: '1rem' }}>Create account</button>
      </form>
    </div>
  );
}

function OperationsPanel({ onError }: { onError: (error: unknown) => void }) {
  const [result, setResult] = useState<string | null>(null);
  const replay = (topic: string) => api.replayDeadLetters(topic).then((outcome) => setResult(`${outcome.topic}.DLT → re-delivered ${outcome.replayed} message(s)`)).catch(onError);
  return (
    <div className="grid cols-2">
      <div className="card"><h2>Dead-letter queue (payout-service)</h2>
        <p className="muted">Messages a consumer could not process after 4 attempts land on <span className="mono">&lt;topic&gt;.DLT</span>. Once the cause is fixed they can be safely re-delivered — handling is idempotent.</p>
        <div className="actions">{['claims.events', 'payout.events'].map((topic) => <button key={topic} className="btn" onClick={() => replay(topic)}>Replay {topic}.DLT</button>)}</div>
        {result && <Alert kind="ok">{result}</Alert>}
      </div>
      <div className="card"><h2>System rules</h2>
        <dl className="kv">
          <dt>Submission limit</dt><dd>30 / min per client (429 + Retry-After)</dd>
          <dt>Idempotency</dt><dd>Idempotency-Key header, 24 h</dd>
          <dt>Review SLA</dt><dd>48 h, non-blocking escalation</dd>
          <dt>ML triage timeout</dt><dd>2 min → heuristic fallback</dd>
          <dt>Reservation limit</dt><dd>50 000 PLN</dd>
          <dt>Transfer failure drill</dt><dd>amount ending in .99</dd>
        </dl>
      </div>
    </div>
  );
}

export default function Admin() {
  const [tab, setTab] = useState<Tab>('dashboard');
  const [statistics, setStatistics] = useState<Statistics | null>(null);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError, clearError] = useErrorState();
  const refresh = useCallback(async () => {
    try { const [nextStatistics, nextUsage] = await Promise.all([api.statistics(), api.usage()]); setStatistics(nextStatistics); setUsage(nextUsage); clearError(); }
    catch (candidate) { setError(candidate); }
  }, [setError, clearError]);
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 10000); return () => clearInterval(timer); }, [refresh]);
  return (
    <RequireRole roles={['ADMIN']}>
      <Shell title="Admin panel" subtitle="Business statistics, system usage, accounts and operations">
        <div className="tabs">
          {(['dashboard', 'usage', 'users', 'operations'] as Tab[]).map((candidate) => <button key={candidate} className={tab === candidate ? 'active' : ''} onClick={() => setTab(candidate)}>{{ dashboard: 'Dashboard', usage: 'Usage', users: 'Users', operations: 'Operations' }[candidate]}</button>)}
        </div>
        {error && <Alert kind="error">{error}</Alert>}
        {tab === 'dashboard' && (statistics ? <Dashboard statistics={statistics} /> : <p className="muted">Loading…</p>)}
        {tab === 'usage' && (usage ? <UsagePanel usage={usage} /> : <p className="muted">Loading…</p>)}
        {tab === 'users' && <UsersPanel onError={setError} />}
        {tab === 'operations' && <><ToolLinks /><div style={{ height: '1rem' }} /><OperationsPanel onError={setError} /></>}
      </Shell>
    </RequireRole>
  );
}
