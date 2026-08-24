export type ClaimStatus = 'SUBMITTED' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'PAID' | 'PAYOUT_FAILED' | 'WITHDRAWN';
export type Severity = 'MINOR' | 'MODERATE' | 'SEVERE';

/** Mirrors claim-service's ClaimResponse. */
export interface Claim {
  id: string;
  claimNumber: string;
  policyNumber: string;
  plateNumber: string;
  incidentDate: string;
  description: string;
  estimatedAmount: number | null;
  approvedAmount: number | null;
  status: ClaimStatus;
  rejectionReason: string | null;
  payoutFailureReason: string | null;
  severity: Severity | null;
  assessmentProvider: string | null;
  reviewAssignee: string | null;
  reviewDueAt: string | null;
  escalated: boolean;
  photoIds: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
}

export interface SubmitClaimRequest {
  policyNumber: string;
  plateNumber: string;
  incidentDate: string;
  description: string;
  estimatedAmount: number | null;
}
