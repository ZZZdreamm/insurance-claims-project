// Full-lifecycle load: policyholders submit, an adjuster takes and approves, ~1 in 6 approvals ends in
// .99 (payment provider rejects) and finance retries it. Fills every Grafana panel: submissions, status
// transitions, outbox relay, Kafka listener latency, payout outcomes.
//   RATE_LIMIT_SUBMIT_PER_MINUTE=1000000 docker compose --profile core up -d claim-service
//   docker run --rm --network host -v "$PWD/perf:/perf:ro" grafana/k6:0.56.0 run /perf/k6-lifecycle.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    lifecycle: { executor: 'constant-vus', vus: Number(__ENV.VUS || 8), duration: __ENV.DURATION || '90s' },
  },
  thresholds: { http_req_failed: ['rate<0.05'] },
};
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function login(username) {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username, password: username }), { headers: JSON_HEADERS });
  return res.json('accessToken');
}
export function setup() {
  return { anna: login('anna'), alice: login('alice'), finance: login('finance') };
}
const auth = (token, extra = {}) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, ...extra } });

export default function (tokens) {
  const failing = __ITER % 6 === 0;
  const submit = http.post(`${BASE}/api/v1/claims`, JSON.stringify({
    policyNumber: `POL-LC-${__VU}`, plateNumber: `LC ${10000 + ((__VU * 1000 + __ITER) % 89999)}`,
    incidentDate: new Date().toISOString().slice(0, 10),
    description: ['Cracked windscreen from a stone', 'Rear bumper dented in a car park', 'Engine bay fire after a collision', 'Door and headlight damage from a deer'][__ITER % 4],
    estimatedAmount: 400 + (__ITER % 9) * 350,
  }), auth(tokens.anna, { 'X-Client-Id': `k6-lc-${__VU}`, 'Idempotency-Key': `lc-${__VU}-${__ITER}-${Date.now()}` }));
  if (!check(submit, { submitted: (r) => r.status === 201 })) return;
  const id = submit.json('id');

  // assessment-service answers over Kafka; poll until the claim is reviewable
  let status = 'SUBMITTED';
  for (let attempt = 0; attempt < 60 && status !== 'PENDING_REVIEW'; attempt++) {
    sleep(1);
    status = http.get(`${BASE}/api/v1/claims/${id}`, auth(tokens.alice)).json('status');
  }
  if (!check(null, { triaged: () => status === 'PENDING_REVIEW' })) return;

  check(http.post(`${BASE}/api/v1/reviews/${id}/claim`, null, auth(tokens.alice)), { taken: (r) => r.status === 200 });
  const amount = failing ? 1000.99 : 800 + (__ITER % 7) * 120;
  check(http.post(`${BASE}/api/v1/reviews/${id}/approve`, JSON.stringify({ approvedAmount: amount }), auth(tokens.alice)), { approved: (r) => r.status === 200 });

  for (let attempt = 0; attempt < 40 && !['PAID', 'PAYOUT_FAILED'].includes(status); attempt++) {
    sleep(1);
    status = http.get(`${BASE}/api/v1/claims/${id}`, auth(tokens.finance)).json('status');
  }
  if (status === 'PAYOUT_FAILED') {
    check(http.post(`${BASE}/api/v1/claims/${id}/retry-payout`, JSON.stringify({ approvedAmount: 1001 }), auth(tokens.finance)), { retried: (r) => r.status === 200 });
  }
  check(null, { settled: () => status === 'PAID' || status === 'PAYOUT_FAILED' });
  sleep(0.3);
}
