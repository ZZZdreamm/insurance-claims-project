export type ClaimStatus = 'SUBMITTED' | 'PENDING_REVIEW' | 'PENDING_SECOND_APPROVAL' | 'APPROVED' | 'PARTIALLY_PAID' | 'REJECTED' | 'PAID' | 'PAYOUT_FAILED' | 'WITHDRAWN';
export type Severity = 'MINOR' | 'MODERATE' | 'SEVERE';
export type Role = 'POLICYHOLDER' | 'ADJUSTER' | 'FINANCE' | 'ADMIN' | 'SERVICE';
export const ALL_ROLES: Role[] = ['POLICYHOLDER', 'ADJUSTER', 'FINANCE', 'ADMIN', 'SERVICE'];

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
  grossApprovedAmount: number | null;
  payableAmount: number | null;
  deductibleApplied: number | null;
  paidAmount: number;
  firstApprover: string | null;
  fraudFlags: string[];
  status: ClaimStatus;
  rejectionReason: string | null;
  payoutFailureReason: string | null;
  severity: Severity | null;
  assessmentProvider: string | null;
  assessmentScore: number | null;
  assessmentExplanation: string | null;
  assessedAt: string | null;
  paidAt: string | null;
  payoutReference: string | null;
  ownerId: string | null;
  reviewAssignee: string | null;
  reviewDueAt: string | null;
  escalated: boolean;
  photoIds: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number; size: number; }
export type ReviewScope = 'ALL' | 'UNASSIGNED' | 'MINE';
export interface ReviewQueueSummary { open: number; unassigned: number; mine: number; escalated: number; severe: number; fraudSuspected: number; awaitingSecondApproval: number; }

export interface Policy {
  policyNumber: string; coverageType: 'OC' | 'AC'; validFrom: string; validTo: string;
  sumInsured: number; deductible: number; active: boolean;
}
export interface ClaimPayment { id: string; amount: number; paymentType: 'ADVANCE' | 'FINAL'; reference: string | null; issuedAt: string; }
export interface ReserveExposure { severity: string; claims: number; totalReserved: number; }
export interface ReserveSummary { openClaims: number; totalOpen: number; totalSettled: number; bySeverity: ReserveExposure[]; }

export interface FraudContext { duplicateCandidates: Claim[]; duplicateTotal: number; ownerClaims: Claim[]; ownerClaimTotal: number; }
export interface CustomerCommunication { id: string; type: string; subject: string; body: string; sentAt: string; }
export interface SubrogationCase {
  id: string; claimId: string; liableParty: string; expectedAmount: number; recoveredAmount: number;
  status: 'OPEN' | 'RECOVERED' | 'WRITTEN_OFF'; writeOffReason: string | null; openedBy: string; openedAt: string; updatedAt: string;
}
export interface RecoverySummary { openCases: number; expectedOpen: number; totalRecovered: number; }

export interface SubmitClaimRequest {
  policyNumber: string; plateNumber: string; incidentDate: string; description: string; estimatedAmount: number | null;
}

export interface UserInfo { username: string; displayName: string; roles: Role[]; }
export interface LoginResponse { accessToken: string; tokenType: string; expiresAt: string; user: UserInfo; }

/** search-service */
export interface ClaimDocument {
  claimId: string; claimNumber: string; policyNumber: string; plateNumber: string; incidentDate: string; description: string;
  estimatedAmount: number | null; approvedAmount: number | null; status: ClaimStatus; rejectionReason: string | null;
  lastEventAt: string; lastEventType: string;
}
export interface SearchResult { items: ClaimDocument[]; total: number; page: number; size: number; }
export interface ClaimEventLogEntry {
  '@timestamp': string; eventId: string; eventType: string; sequence: number; claimId: string; status?: string;
  severity?: string; reviewAssignee?: string; escalated?: boolean; approvedAmount?: number | null; estimatedAmount?: number | null;
}

/** payout-service */
export interface LedgerEntry {
  claimId: string; reservedAmount: number; reservationStatus: 'RESERVED' | 'RELEASED' | 'SETTLED';
  payoutAmount: number | null; payoutStatus: 'PENDING' | 'ISSUED' | 'REVERSED' | 'FAILED' | null; reference: string | null; reason: string | null; updatedAt: string;
}
export interface LedgerSummary {
  reservations: number; payoutsIssued: number; payoutsFailed: number; payoutsReversed: number; totalIssued: number; totalReserved: number;
  entries: LedgerEntry[]; page: number; totalPages: number; totalElements: number;
}
export interface ReplayResult { topic: string; replayed: number; }

/** admin */
export interface Statistics {
  totalClaims: number; byStatus: Record<ClaimStatus, number>; everInStatus: Record<ClaimStatus, number>; bySeverity: Record<Severity, number>; submittedPerDay: Record<string, number>;
  openReviews: number; escalatedReviews: number; paidTotal: number; approvedAwaitingPayout: number;
  averageSecondsToAssessment: number | null; averageSecondsToPayment: number | null; accounts: number;
}
export interface EndpointUsage { method: string; uri: string; requests: number; averageMillis: number; maxMillis: number; errors: number; }
export interface Usage {
  uptimeSeconds: number; cpuUsage: number; heapUsedBytes: number; heapMaxBytes: number; totalHttpRequests: number; endpoints: EndpointUsage[];
  claimsSubmitted: number; outboxPublished: number; outboxPending: number; claimTransitions: Record<string, number>;
  topClients: { clientId: string; submissionsThisMinute: number }[];
}
export interface UserAccount { id: string; username: string; displayName: string; roles: Role[]; enabled: boolean; createdAt: string; }
