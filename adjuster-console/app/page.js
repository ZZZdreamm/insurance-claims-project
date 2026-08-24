'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from './api';

const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : '—');

function TaskRow({ task, me, onChange, onError }) {
  const [amount, setAmount] = useState(task.estimatedAmount ?? '');
  const [reason, setReason] = useState('');
  const mine = task.assignee === me;

  const run = async (fn) => {
    try { await fn(); await onChange(); } catch (e) { onError(e.message); }
  };

  return (
    <tr>
      <td>
        <strong>{task.claimNumber}</strong><br />
        <span className="muted">{task.plateNumber}</span>
      </td>
      <td>{task.description}</td>
      <td>
        <span className={`badge ${task.severity}`}>{task.severity ?? '?'}</span><br />
        <span className="muted">est. {task.estimatedAmount}</span>
      </td>
      <td>
        {fmt(task.dueAt)}
        {task.escalated && <div className="escalated">SLA breached</div>}
      </td>
      <td>{task.assignee ?? <span className="muted">unassigned</span>}</td>
      <td>
        {!task.assignee && <button onClick={() => run(() => api.claim(task.taskId, me))}>Claim</button>}
        {mine && (
          <div className="actions">
            <input type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 100 }} />
            <button onClick={() => run(() => api.complete(task.taskId, { decision: 'APPROVE', approvedAmount: Number(amount) }))}>Approve</button>
            <input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            <button onClick={() => run(() => api.complete(task.taskId, { decision: 'REJECT', reason }))}>Reject</button>
            <button onClick={() => run(() => api.unclaim(task.taskId))}>Unclaim</button>
          </div>
        )}
      </td>
    </tr>
  );
}

function SubmitDemoClaim({ onDone, onError }) {
  const [desc, setDesc] = useState('Rear-ended at a red light, bumper and tail light damaged');
  const [amount, setAmount] = useState(2500);
  const submit = async () => {
    try {
      await api.submit({
        policyNumber: 'POL-' + Math.floor(Math.random() * 9000 + 1000),
        plateNumber: 'WA ' + Math.floor(Math.random() * 90000 + 10000),
        incidentDate: new Date().toISOString().slice(0, 10),
        description: desc,
        estimatedAmount: Number(amount),
      });
      setTimeout(onDone, 1500); // give the job executor a moment to reach the review task
    } catch (e) { onError(e.message); }
  };
  return (
    <details style={{ margin: '1rem 0' }}>
      <summary>Submit a demo claim</summary>
      <div className="actions" style={{ marginTop: '0.5rem' }}>
        <input value={desc} onChange={(e) => setDesc(e.target.value)} style={{ flex: 1, minWidth: 300 }} />
        <input type="number" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 100 }} />
        <button onClick={submit}>Submit</button>
      </div>
    </details>
  );
}

export default function Page() {
  const [tasks, setTasks] = useState([]);
  const [me, setMe] = useState('alice');
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    try { setTasks(await api.tasks()); setError(null); } catch (e) { setError(e.message); }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 5000);
    return () => clearInterval(id);
  }, [refresh]);

  return (
    <main>
      <h1>Adjuster console <span className="muted">— open reviews: {tasks.length}</span></h1>
      <div className="actions">
        <label>Acting as <input value={me} onChange={(e) => setMe(e.target.value)} style={{ width: 120 }} /></label>
        <button onClick={refresh}>Refresh</button>
      </div>
      <SubmitDemoClaim onDone={refresh} onError={setError} />
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr><th>Claim</th><th>Description</th><th>Severity</th><th>Due</th><th>Assignee</th><th>Actions</th></tr>
        </thead>
        <tbody>
          {tasks.map((t) => <TaskRow key={t.taskId} task={t} me={me} onChange={refresh} onError={setError} />)}
          {tasks.length === 0 && <tr><td colSpan="6" className="muted">No open reviews.</td></tr>}
        </tbody>
      </table>
    </main>
  );
}
