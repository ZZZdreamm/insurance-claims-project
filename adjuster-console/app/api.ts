import type { Claim, Page, SubmitClaimRequest } from './types';

export const BASE = process.env.NEXT_PUBLIC_CLAIM_API ?? 'http://localhost:8080';

interface ProblemDetail {
  title?: string;
  detail?: string;
  errors?: string[];
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { ...init, cache: 'no-store' });
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const p = (await res.json()) as ProblemDetail;
      detail = p.detail ?? p.title ?? detail;
      if (p.errors) detail += ': ' + p.errors.join('; ');
    } catch {
      /* not a problem+json body */
    }
    throw new Error(detail);
  }
  return (res.status === 204 ? null : await res.json()) as T;
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export const api = {
  reviews: () => call<Claim[]>('/api/v1/reviews'),
  claimsByStatus: (status: Claim['status']) => call<Page<Claim>>(`/api/v1/claims?status=${status}&size=50`),
  claimReview: (id: string, assignee: string) => call<Claim>(`/api/v1/reviews/${id}/claim`, json({ assignee })),
  unclaimReview: (id: string) => call<Claim>(`/api/v1/reviews/${id}/unclaim`, { method: 'POST' }),
  approve: (id: string, approvedAmount: number) => call<Claim>(`/api/v1/reviews/${id}/approve`, json({ approvedAmount })),
  reject: (id: string, reason: string) => call<Claim>(`/api/v1/reviews/${id}/reject`, json({ reason })),
  retryPayout: (id: string, approvedAmount?: number) =>
    call<Claim>(`/api/v1/claims/${id}/retry-payout`, json(approvedAmount ? { approvedAmount } : {})),
  submit: (claim: SubmitClaimRequest, photos: File[]) => {
    const form = new FormData();
    form.append('claim', new Blob([JSON.stringify(claim)], { type: 'application/json' }));
    photos.forEach((f) => form.append('photos', f));
    return call<Claim>('/api/v1/claims', { method: 'POST', body: form });
  },
  photoUrl: (claimId: string, photoId: string) => `${BASE}/api/v1/claims/${claimId}/photos/${photoId}`,
};
