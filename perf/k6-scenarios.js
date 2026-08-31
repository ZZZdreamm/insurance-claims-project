// Mass business-scenario test against the live stack: every workflow the platform supports,
// executed concurrently and verified end-to-end (real ML triage, real async payment gateway).
// Run: RATE_LIMIT_SUBMIT_PER_MINUTE=1000000 docker compose --profile core up -d claim-service
//      docker run --rm --network host -v "$PWD/perf:/perf:ro" grafana/k6:0.56.0 run /perf/k6-scenarios.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    happy_deductible:    { executor: 'shared-iterations', vus: 2, iterations: 10, maxDuration: '6m', exec: 'happyDeductible' },
    four_eyes:           { executor: 'shared-iterations', vus: 2, iterations: 6,  maxDuration: '6m', exec: 'fourEyes' },
    advance_remainder:   { executor: 'shared-iterations', vus: 2, iterations: 6,  maxDuration: '6m', exec: 'advanceRemainder' },
    payout_failure_retry:{ executor: 'shared-iterations', vus: 2, iterations: 6,  maxDuration: '6m', exec: 'payoutFailureRetry' },
    fraud_duplicates:    { executor: 'shared-iterations', vus: 1, iterations: 5,  maxDuration: '6m', exec: 'fraudDuplicates' },
    rejection_letters:   { executor: 'shared-iterations', vus: 2, iterations: 6,  maxDuration: '6m', exec: 'rejectionLetters' },
    validation_guards:   { executor: 'shared-iterations', vus: 2, iterations: 10, maxDuration: '3m', exec: 'validationGuards' },
    subrogation:         { executor: 'shared-iterations', vus: 1, iterations: 4,  maxDuration: '6m', exec: 'subrogationRecovery' },
    withdrawals:         { executor: 'shared-iterations', vus: 1, iterations: 5,  maxDuration: '3m', exec: 'withdrawals' },
  },
  thresholds: { checks: ['rate>0.97'] },
};

function login(username) {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username, password: username }), { headers: JSON_HEADERS });
  return res.json('accessToken');
}
export function setup() {
  return { anna: login('anna'), alice: login('alice'), bob: login('bob'), finance: login('finance') };
}

const auth = (token, extra = {}) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, ...extra } });
const uuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0; return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
});
const plate = (prefix) => `${prefix} ${10000 + ((exec.scenario.iterationInTest * 37 + Math.floor(Math.random() * 900)) % 89999)}`;

function submit(tokens, policyNumber, plateNumber, description, amount) {
  const res = http.post(`${BASE}/api/v1/claims`, JSON.stringify({
    policyNumber, plateNumber, incidentDate: new Date(Date.now() - 86400000).toISOString().slice(0, 10),
    description, estimatedAmount: amount,
  }), auth(tokens.anna, { 'Idempotency-Key': uuid() }));
  check(res, { 'submit 201': (r) => r.status === 201 });
  return res.status === 201 ? res.json('id') : null;
}

function pollUntil(tokens, id, predicate, label, timeoutSeconds = 60) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let claim = null;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE}/api/v1/claims/${id}`, auth(tokens.alice));
    if (res.status === 200) { claim = res.json(); if (predicate(claim)) { check(claim, { [label]: () => true }); return claim; } }
    sleep(1);
  }
  check(claim, { [label]: () => false });
  return claim;
}
const pollStatus = (tokens, id, status, label, t) => pollUntil(tokens, id, (c) => c.status === status, label, t);

function takeAndApprove(tokens, id, amount, advancePercent) {
  check(http.post(`${BASE}/api/v1/reviews/${id}/claim`, null, auth(tokens.alice)), { 'review taken': (r) => r.status === 200 });
  const body = advancePercent ? { approvedAmount: amount, advancePercent } : { approvedAmount: amount };
  return http.post(`${BASE}/api/v1/reviews/${id}/approve`, JSON.stringify(body), auth(tokens.alice));
}

// 1) Policy arithmetic: POL-123 carries a 400 deductible; PAID amount must be gross - 400.
export function happyDeductible(tokens) {
  const gross = 2000 + (exec.scenario.iterationInTest % 5) * 250;
  const id = submit(tokens, 'POL-123', plate('HD'), 'Rear bumper cracked in a parking collision', gross);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  check(takeAndApprove(tokens, id, gross), { 'approved': (r) => r.status === 200 });
  const paid = pollStatus(tokens, id, 'PAID', 'paid', 90);
  if (paid) check(paid, { 'deductible applied': (c) => c.paidAmount === gross - 400 && c.deductibleApplied === 400 });
}

// 2) Above the limit two different adjusters must sign off.
export function fourEyes(tokens) {
  const gross = 12000 + (exec.scenario.iterationInTest % 4) * 1500;
  const id = submit(tokens, 'POL-2024-077', plate('FE'), 'Severe front-end damage on the motorway', gross);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  const approval = takeAndApprove(tokens, id, gross);
  check(approval, { 'parked for 2nd approval': (r) => r.status === 200 && r.json('status') === 'PENDING_SECOND_APPROVAL' });
  const sameApprover = http.post(`${BASE}/api/v1/reviews/${id}/second-approval`, null, auth(tokens.alice));
  check(sameApprover, { 'same approver refused': (r) => r.status === 409 });
  check(http.post(`${BASE}/api/v1/reviews/${id}/second-approval`, null, auth(tokens.bob)), { '2nd approval ok': (r) => r.status === 200 });
  pollStatus(tokens, id, 'PAID', 'paid after four eyes', 90);
}

// 3) 25% advance now, finance releases the remainder.
export function advanceRemainder(tokens) {
  const gross = 4000;
  const id = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, plate('AD'), 'Repair in progress, advance requested', gross);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  check(takeAndApprove(tokens, id, gross, 25), { 'approved with advance': (r) => r.status === 200 });
  const partial = pollStatus(tokens, id, 'PARTIALLY_PAID', 'advance paid', 90);
  if (partial) check(partial, { 'advance is 25%': (c) => c.paidAmount === gross * 0.25 });
  check(http.post(`${BASE}/api/v1/claims/${id}/pay-remainder`, null, auth(tokens.finance)), { 'remainder released': (r) => r.status === 200 });
  const paid = pollStatus(tokens, id, 'PAID', 'fully paid', 90);
  if (paid) {
    const payments = http.get(`${BASE}/api/v1/claims/${id}/payments`, auth(tokens.finance));
    check(payments, { 'two payments recorded': (r) => r.status === 200 && r.json().length === 2 });
  }
}

// 4) Deterministic provider rejection (.99) and the finance retry.
export function payoutFailureRetry(tokens) {
  const id = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, plate('PF'), 'Side panel dented, provider hiccup drill', 1200);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  check(takeAndApprove(tokens, id, 1500.99), { 'approved .99': (r) => r.status === 200 });
  if (!pollStatus(tokens, id, 'PAYOUT_FAILED', 'payout failed as designed', 90)) return;
  check(http.post(`${BASE}/api/v1/claims/${id}/retry-payout`, JSON.stringify({ approvedAmount: 1501 }), auth(tokens.finance)),
    { 'retried': (r) => r.status === 200 });
  pollStatus(tokens, id, 'PAID', 'paid after retry', 90);
}

// 5) Same vehicle twice in the window -> the second claim carries the fraud flag.
export function fraudDuplicates(tokens) {
  const sharedPlate = plate('FR');
  const first = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, sharedPlate, 'First claim for this vehicle', 900);
  if (!first) return;
  pollStatus(tokens, first, 'PENDING_REVIEW', 'first triaged', 90);
  const second = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, sharedPlate, 'Second claim same vehicle same week', 900);
  if (!second) return;
  const flagged = pollUntil(tokens, second, (c) => (c.fraudFlags || []).includes('DUPLICATE_CLAIM'), 'duplicate flagged', 30);
  if (flagged) {
    // the SIU listing is oldest-first and shared with every historical flag, so assert the
    // counter instead of hunting for this claim on a page
    pollStatus(tokens, second, 'PENDING_REVIEW', 'flagged claim triaged', 90);
    const summary = http.get(`${BASE}/api/v1/reviews/summary`, auth(tokens.alice));
    check(summary, { 'SIU counter sees fraud': (r) => r.status === 200 && r.json('fraudSuspected') >= 1 });
  }
}

// 6) Rejection produces the letter and the communication trail.
export function rejectionLetters(tokens) {
  const id = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, plate('RJ'), 'Suspected pre-existing damage, to be rejected', 700);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  check(http.post(`${BASE}/api/v1/reviews/${id}/claim`, null, auth(tokens.alice)), { 'review taken': (r) => r.status === 200 });
  check(http.post(`${BASE}/api/v1/reviews/${id}/reject`, JSON.stringify({ reason: 'Damage predates the policy period' }), auth(tokens.alice)),
    { 'rejected': (r) => r.status === 200 });
  const pdf = http.get(`${BASE}/api/v1/claims/${id}/decision-document`, auth(tokens.anna));
  check(pdf, { 'decision letter is a PDF': (r) => r.status === 200 && r.headers['Content-Type'] === 'application/pdf' && r.body.slice(0, 4) === '%PDF' });
  const comms = http.get(`${BASE}/api/v1/claims/${id}/communications`, auth(tokens.anna));
  check(comms, { 'rejection communicated': (r) => r.status === 200 && JSON.stringify(r.json()).includes('DECISION_REJECTED') });
}

// 7) The guards: unknown policy, someone else's policy, incident outside coverage.
export function validationGuards(tokens) {
  const submitRaw = (policyNumber, incidentDate) => http.post(`${BASE}/api/v1/claims`, JSON.stringify({
    policyNumber, plateNumber: plate('VG'), incidentDate, description: 'Validation guard drill, should be rejected', estimatedAmount: 500,
  }), auth(tokens.anna, { 'Idempotency-Key': uuid() }));
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  check(submitRaw('POL-NOPE', yesterday), { 'unknown policy 422': (r) => r.status === 422 });
  check(submitRaw('POL-777', yesterday), { "someone else's policy 422": (r) => r.status === 422 });
  check(submitRaw('POL-123', '2023-06-01'), { 'outside coverage 422': (r) => r.status === 422 });
}

// 8) Paid claim recovered from the liable party in two instalments.
export function subrogationRecovery(tokens) {
  const gross = 1600;
  const id = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, plate('SU'), 'Rear-ended by an identified third party', gross);
  if (!id) return;
  if (!pollStatus(tokens, id, 'PENDING_REVIEW', 'triaged', 90)) return;
  check(takeAndApprove(tokens, id, gross), { 'approved': (r) => r.status === 200 });
  if (!pollStatus(tokens, id, 'PAID', 'paid', 90)) return;
  const opened = http.post(`${BASE}/api/v1/claims/${id}/subrogation`, JSON.stringify({ liableParty: 'Other Insurer S.A.', expectedAmount: gross }), auth(tokens.alice));
  check(opened, { 'recovery opened': (r) => r.status === 200 && r.json('status') === 'OPEN' });
  check(http.post(`${BASE}/api/v1/claims/${id}/subrogation`, JSON.stringify({ liableParty: 'X', expectedAmount: 1 }), auth(tokens.alice)),
    { 'second case refused': (r) => r.status === 409 });
  const caseId = opened.json('id');
  http.post(`${BASE}/api/v1/subrogations/${caseId}/recoveries`, JSON.stringify({ amount: gross * 0.6 }), auth(tokens.finance));
  const closed = http.post(`${BASE}/api/v1/subrogations/${caseId}/recoveries`, JSON.stringify({ amount: gross * 0.4 }), auth(tokens.finance));
  check(closed, { 'fully recovered': (r) => r.status === 200 && r.json('status') === 'RECOVERED' });
}

// 9) Withdrawal from any non-terminal state.
export function withdrawals(tokens) {
  const id = submit(tokens, `POL-${1 + (exec.scenario.iterationInTest % 50)}`, plate('WD'), 'Changed my mind, withdrawing this claim', 300);
  if (!id) return;
  check(http.post(`${BASE}/api/v1/claims/${id}/withdraw`, null, auth(tokens.anna)), { 'withdrawn': (r) => r.status === 200 });
  pollStatus(tokens, id, 'WITHDRAWN', 'terminal withdrawn', 30);
}
