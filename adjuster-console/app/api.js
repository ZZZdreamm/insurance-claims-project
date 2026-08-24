const BASE = process.env.NEXT_PUBLIC_CLAIM_API ?? 'http://localhost:8080';

async function call(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
    cache: 'no-store',
  });
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try { const p = await res.json(); detail = p.detail ?? p.title ?? detail; if (p.errors) detail += ': ' + p.errors.join('; '); } catch {}
    throw new Error(detail);
  }
  return res.status === 204 ? null : res.json();
}

export const api = {
  tasks: () => call('/api/v1/tasks'),
  claim: (taskId, assignee) => call(`/api/v1/tasks/${taskId}/claim`, { method: 'POST', body: JSON.stringify({ assignee }) }),
  unclaim: (taskId) => call(`/api/v1/tasks/${taskId}/unclaim`, { method: 'POST' }),
  complete: (taskId, body) => call(`/api/v1/tasks/${taskId}/complete`, { method: 'POST', body: JSON.stringify(body) }),
  submit: (body) => call('/api/v1/claims', { method: 'POST', body: JSON.stringify(body) }),
};
