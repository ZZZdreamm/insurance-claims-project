import type {
  Claim, ClaimEventLogEntry, ClaimStatus, LedgerEntry, LedgerSummary, LoginResponse, Page, ReplayResult, ReviewQueueSummary, ReviewScope, Role,
  SearchResult, Severity, Statistics, SubmitClaimRequest, Usage, UserAccount, UserInfo,
} from './types';

export const CLAIM_BASE = process.env.NEXT_PUBLIC_CLAIM_API ?? 'http://localhost:8080';
export const SEARCH_BASE = process.env.NEXT_PUBLIC_SEARCH_API ?? 'http://localhost:8081';
export const PAYOUT_BASE = process.env.NEXT_PUBLIC_PAYOUT_API ?? 'http://localhost:8082';
export const EXTERNAL_LINKS = {
  grafana: process.env.NEXT_PUBLIC_GRAFANA_URL ?? 'http://localhost:3001',
  kibana: process.env.NEXT_PUBLIC_KIBANA_URL ?? 'http://localhost:5601',
  jenkins: process.env.NEXT_PUBLIC_JENKINS_URL ?? 'http://localhost:8090',
};
const SESSION_KEY = 'claims.session';

export interface Session { token: string; expiresAt: string; user: UserInfo; }

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt).getTime() > Date.now() ? session : null;
  } catch { return null; }
}
export function saveSession(session: Session | null): void {
  try { if (session) sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); else sessionStorage.removeItem(SESSION_KEY); } catch { /* memory only */ }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
interface ProblemDetail { title?: string; detail?: string; errors?: string[]; }

async function call<T>(base: string, path: string, init: RequestInit = {}): Promise<T> {
  const session = loadSession();
  const headers = new Headers(init.headers);
  if (session) headers.set('Authorization', `Bearer ${session.token}`);
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, { ...init, headers, cache: 'no-store' });
  } catch {
    throw new ApiError(0, `Brak połączenia z ${base} — czy ten serwis jest uruchomiony?`);
  }
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const problem = (await response.json()) as ProblemDetail;
      detail = problem.detail ?? problem.title ?? detail;
      if (problem.errors) detail += ': ' + problem.errors.join('; ');
    } catch { /* not problem+json */ }
    if (response.status === 401) saveSession(null);
    throw new ApiError(response.status, detail);
  }
  return (response.status === 204 ? null : await response.json()) as T;
}
const json = (method: string, body: unknown): RequestInit => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export const api = {
  // auth
  login: (username: string, password: string) => call<LoginResponse>(CLAIM_BASE, '/api/v1/auth/login', json('POST', { username, password })),
  me: () => call<UserInfo>(CLAIM_BASE, '/api/v1/auth/me'),

  // claims (policyholder + staff)
  claims: (status?: ClaimStatus, size = 50) => call<Page<Claim>>(CLAIM_BASE, `/api/v1/claims?size=${size}${status ? `&status=${status}` : ''}`),
  claim: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}`),
  submit: (claim: SubmitClaimRequest, photos: File[]) => {
    const form = new FormData();
    form.append('claim', new Blob([JSON.stringify(claim)], { type: 'application/json' }));
    photos.forEach((file) => form.append('photos', file));
    return call<Claim>(CLAIM_BASE, '/api/v1/claims', { method: 'POST', body: form, headers: { 'Idempotency-Key': crypto.randomUUID() } });
  },
  withdraw: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}/withdraw`, { method: 'POST' }),
  retryPayout: (id: string, approvedAmount?: number) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}/retry-payout`, json('POST', approvedAmount ? { approvedAmount } : {})),
  photoBlob: async (claimId: string, photoId: string): Promise<string> => {
    const session = loadSession();
    const response = await fetch(`${CLAIM_BASE}/api/v1/claims/${claimId}/photos/${photoId}`, { headers: session ? { Authorization: `Bearer ${session.token}` } : {} });
    return URL.createObjectURL(await response.blob());
  },

  // reviews (adjuster)
  reviews: (options: { scope?: ReviewScope; severity?: Severity | ''; escalatedOnly?: boolean; page?: number; size?: number } = {}) => {
    const params = new URLSearchParams();
    if (options.scope) params.set('scope', options.scope);
    if (options.severity) params.set('severity', options.severity);
    if (options.escalatedOnly) params.set('escalatedOnly', 'true');
    params.set('page', String(options.page ?? 0));
    params.set('size', String(options.size ?? 20));
    return call<Page<Claim>>(CLAIM_BASE, `/api/v1/reviews?${params.toString()}`);
  },
  reviewSummary: () => call<ReviewQueueSummary>(CLAIM_BASE, '/api/v1/reviews/summary'),
  claimReview: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/claim`, { method: 'POST' }),
  unclaimReview: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/unclaim`, { method: 'POST' }),
  approve: (id: string, approvedAmount: number) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/approve`, json('POST', { approvedAmount })),
  reject: (id: string, reason: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/reject`, json('POST', { reason })),

  // search-service (staff)
  search: (queryText: string, status?: string) => call<SearchResult>(SEARCH_BASE, `/api/v1/search?q=${encodeURIComponent(queryText)}${status ? `&status=${status}` : ''}&size=50`),
  timeline: (claimId: string) => call<ClaimEventLogEntry[]>(SEARCH_BASE, `/api/v1/claims/${claimId}/events`),

  // payout-service (finance/admin)
  ledger: () => call<LedgerSummary>(PAYOUT_BASE, '/api/v1/payouts'),
  ledgerEntry: (claimId: string) => call<LedgerEntry>(PAYOUT_BASE, `/api/v1/payouts/${claimId}`),
  replayDeadLetters: (topic: string) => call<ReplayResult>(PAYOUT_BASE, `/api/v1/dlq/replay?topic=${encodeURIComponent(topic)}`, { method: 'POST' }),

  // admin
  statistics: (days = 14) => call<Statistics>(CLAIM_BASE, `/api/v1/admin/statistics?days=${days}`),
  usage: () => call<Usage>(CLAIM_BASE, '/api/v1/admin/usage'),
  users: () => call<UserAccount[]>(CLAIM_BASE, '/api/v1/admin/users'),
  createUser: (body: { username: string; password: string; displayName: string; roles: Role[] }) => call<UserAccount>(CLAIM_BASE, '/api/v1/admin/users', json('POST', body)),
  updateUser: (id: string, body: { roles?: Role[]; enabled?: boolean; password?: string; displayName?: string }) => call<UserAccount>(CLAIM_BASE, `/api/v1/admin/users/${id}`, json('PATCH', body)),
};
