import type {
  Policy, ClaimPayment, ReserveSummary, CustomerCommunication, SubrogationCase, RecoverySummary, FraudContext,
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
    throw new ApiError(0, `Cannot reach ${base} — is that service running?`);
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
  claims: (status?: ClaimStatus, page = 0, size = 25, q?: string) => call<Page<Claim>>(CLAIM_BASE, `/api/v1/claims?page=${page}&size=${size}${status ? `&status=${status}` : ''}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  claim: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}`),
  submit: (claim: SubmitClaimRequest, photos: File[]) => {
    const form = new FormData();
    form.append('claim', new Blob([JSON.stringify(claim)], { type: 'application/json' }));
    photos.forEach((file) => form.append('photos', file));
    return call<Claim>(CLAIM_BASE, '/api/v1/claims', { method: 'POST', body: form, headers: { 'Idempotency-Key': crypto.randomUUID() } });
  },
  myPolicies: () => call<Policy[]>(CLAIM_BASE, '/api/v1/policies/mine'),
  allPolicies: () => call<Policy[]>(CLAIM_BASE, '/api/v1/policies'),
  payments: (id: string) => call<ClaimPayment[]>(CLAIM_BASE, `/api/v1/claims/${id}/payments`),
  payRemainder: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}/pay-remainder`, { method: 'POST' }),
  reserveSummary: () => call<ReserveSummary>(CLAIM_BASE, '/api/v1/reserves/summary'),
  communications: (id: string) => call<CustomerCommunication[]>(CLAIM_BASE, `/api/v1/claims/${id}/communications`),
  fraudContext: (id: string) => call<FraudContext>(CLAIM_BASE, `/api/v1/claims/${id}/fraud-context`),
  decisionDocumentBlob: async (id: string): Promise<string> => {
    const session = loadSession();
    const response = await fetch(`${CLAIM_BASE}/api/v1/claims/${id}/decision-document`, { headers: session ? { Authorization: `Bearer ${session.token}` } : {} });
    if (!response.ok) throw new Error('The decision letter is available once a decision has been made.');
    return URL.createObjectURL(await response.blob());
  },
  subrogationOf: (claimId: string) => call<SubrogationCase>(CLAIM_BASE, `/api/v1/claims/${claimId}/subrogation`),
  openSubrogation: (claimId: string, liableParty: string, expectedAmount: number) =>
    call<SubrogationCase>(CLAIM_BASE, `/api/v1/claims/${claimId}/subrogation`, json('POST', { liableParty, expectedAmount })),
  recordRecovery: (id: string, amount: number) => call<SubrogationCase>(CLAIM_BASE, `/api/v1/subrogations/${id}/recoveries`, json('POST', { amount })),
  writeOffSubrogation: (id: string, reason: string) => call<SubrogationCase>(CLAIM_BASE, `/api/v1/subrogations/${id}/write-off`, json('POST', { reason })),
  openSubrogations: (page = 0, size = 25, q?: string) => call<Page<SubrogationCase>>(CLAIM_BASE, `/api/v1/subrogations?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  recoverySummary: () => call<RecoverySummary>(CLAIM_BASE, '/api/v1/subrogations/summary'),
  withdraw: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}/withdraw`, { method: 'POST' }),
  retryPayout: (id: string, approvedAmount?: number) => call<Claim>(CLAIM_BASE, `/api/v1/claims/${id}/retry-payout`, json('POST', approvedAmount ? { approvedAmount } : {})),
  photoBlob: async (claimId: string, photoId: string): Promise<string> => {
    const session = loadSession();
    const response = await fetch(`${CLAIM_BASE}/api/v1/claims/${claimId}/photos/${photoId}`, { headers: session ? { Authorization: `Bearer ${session.token}` } : {} });
    return URL.createObjectURL(await response.blob());
  },

  // reviews (adjuster)
  reviews: (options: { scope?: ReviewScope; severity?: Severity | ''; escalatedOnly?: boolean; fraudOnly?: boolean; q?: string; page?: number; size?: number } = {}) => {
    const params = new URLSearchParams();
    if (options.scope) params.set('scope', options.scope);
    if (options.severity) params.set('severity', options.severity);
    if (options.escalatedOnly) params.set('escalatedOnly', 'true');
    if (options.fraudOnly) params.set('fraudOnly', 'true');
    if (options.q) params.set('q', options.q);
    params.set('page', String(options.page ?? 0));
    params.set('size', String(options.size ?? 20));
    return call<Page<Claim>>(CLAIM_BASE, `/api/v1/reviews?${params.toString()}`);
  },
  reviewSummary: () => call<ReviewQueueSummary>(CLAIM_BASE, '/api/v1/reviews/summary'),
  claimReview: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/claim`, { method: 'POST' }),
  unclaimReview: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/unclaim`, { method: 'POST' }),
  approve: (id: string, approvedAmount: number, advancePercent?: number) =>
    call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/approve`, json('POST', advancePercent ? { approvedAmount, advancePercent } : { approvedAmount })),
  secondApprovals: (page = 0, size = 25, q?: string) => call<Page<Claim>>(CLAIM_BASE, `/api/v1/reviews/second-approvals?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  secondApprove: (id: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/second-approval`, { method: 'POST' }),
  reject: (id: string, reason: string) => call<Claim>(CLAIM_BASE, `/api/v1/reviews/${id}/reject`, json('POST', { reason })),

  // search-service (staff)
  search: (queryText: string, status?: string, page = 0, size = 25) => call<SearchResult>(SEARCH_BASE, `/api/v1/search?q=${encodeURIComponent(queryText)}${status ? `&status=${status}` : ''}&page=${page}&size=${size}`),
  timeline: (claimId: string) => call<ClaimEventLogEntry[]>(SEARCH_BASE, `/api/v1/claims/${claimId}/events`),

  // payout-service (finance/admin)
  ledger: (page = 0, size = 25, q?: string) => call<LedgerSummary>(PAYOUT_BASE, `/api/v1/payouts?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  ledgerEntry: (claimId: string) => call<LedgerEntry>(PAYOUT_BASE, `/api/v1/payouts/${claimId}`),
  replayDeadLetters: (topic: string) => call<ReplayResult>(PAYOUT_BASE, `/api/v1/dlq/replay?topic=${encodeURIComponent(topic)}`, { method: 'POST' }),

  // admin
  statistics: (days = 14) => call<Statistics>(CLAIM_BASE, `/api/v1/admin/statistics?days=${days}`),
  usage: () => call<Usage>(CLAIM_BASE, '/api/v1/admin/usage'),
  users: () => call<UserAccount[]>(CLAIM_BASE, '/api/v1/admin/users'),
  createUser: (body: { username: string; password: string; displayName: string; roles: Role[] }) => call<UserAccount>(CLAIM_BASE, '/api/v1/admin/users', json('POST', body)),
  updateUser: (id: string, body: { roles?: Role[]; enabled?: boolean; password?: string; displayName?: string }) => call<UserAccount>(CLAIM_BASE, `/api/v1/admin/users/${id}`, json('PATCH', body)),
};
