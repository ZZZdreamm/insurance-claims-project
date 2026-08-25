import type { Claim, LoginResponse, Page, SubmitClaimRequest, UserInfo } from './types';

export const BASE = process.env.NEXT_PUBLIC_CLAIM_API ?? 'http://localhost:8080';
const SESSION_KEY = 'claims.session';

export interface Session {
  token: string;
  expiresAt: string;
  user: UserInfo;
}

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    return new Date(s.expiresAt).getTime() > Date.now() ? s : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null): void {
  try {
    if (s) sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
    /* storage unavailable: session lives in memory only */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

interface ProblemDetail {
  title?: string;
  detail?: string;
  errors?: string[];
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const session = loadSession();
  const headers = new Headers(init.headers);
  if (session) headers.set('Authorization', `Bearer ${session.token}`);
  const res = await fetch(`${BASE}${path}`, { ...init, headers, cache: 'no-store' });
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const p = (await res.json()) as ProblemDetail;
      detail = p.detail ?? p.title ?? detail;
      if (p.errors) detail += ': ' + p.errors.join('; ');
    } catch {
      /* not a problem+json body */
    }
    if (res.status === 401) saveSession(null);
    throw new ApiError(res.status, detail);
  }
  return (res.status === 204 ? null : await res.json()) as T;
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export const api = {
  login: (username: string, password: string) => call<LoginResponse>('/api/v1/auth/login', json({ username, password })),
  me: () => call<UserInfo>('/api/v1/auth/me'),

  // policyholder
  myClaims: () => call<Page<Claim>>('/api/v1/claims?size=50'),
  submit: (claim: SubmitClaimRequest, photos: File[]) => {
    const form = new FormData();
    form.append('claim', new Blob([JSON.stringify(claim)], { type: 'application/json' }));
    photos.forEach((f) => form.append('photos', f));
    return call<Claim>('/api/v1/claims', { method: 'POST', body: form, headers: { 'Idempotency-Key': crypto.randomUUID() } });
  },
  withdraw: (id: string) => call<Claim>(`/api/v1/claims/${id}/withdraw`, { method: 'POST' }),

  // adjuster
  reviews: () => call<Claim[]>('/api/v1/reviews'),
  claimReview: (id: string) => call<Claim>(`/api/v1/reviews/${id}/claim`, { method: 'POST' }),
  unclaimReview: (id: string) => call<Claim>(`/api/v1/reviews/${id}/unclaim`, { method: 'POST' }),
  approve: (id: string, approvedAmount: number) => call<Claim>(`/api/v1/reviews/${id}/approve`, json({ approvedAmount })),
  reject: (id: string, reason: string) => call<Claim>(`/api/v1/reviews/${id}/reject`, json({ reason })),

  // finance / staff
  claimsByStatus: (status: Claim['status']) => call<Page<Claim>>(`/api/v1/claims?status=${status}&size=50`),
  retryPayout: (id: string, approvedAmount?: number) =>
    call<Claim>(`/api/v1/claims/${id}/retry-payout`, json(approvedAmount ? { approvedAmount } : {})),

  photoUrl: (claimId: string, photoId: string) => `${BASE}/api/v1/claims/${claimId}/photos/${photoId}`,
  photoBlob: async (claimId: string, photoId: string): Promise<string> => {
    const session = loadSession();
    const res = await fetch(`${BASE}/api/v1/claims/${claimId}/photos/${photoId}`, {
      headers: session ? { Authorization: `Bearer ${session.token}` } : {},
    });
    return URL.createObjectURL(await res.blob());
  },
};
