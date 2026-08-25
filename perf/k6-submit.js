// Ingestion load test against a running stack. Logs in once per VU as a policyholder, then submits
// claims through the real path: Redis guards -> validation -> Postgres (claim + outbox) -> 201.
//   ./perf/run.sh                                  (docker, no local k6 needed)
//   k6 run --vus 20 --duration 30s perf/k6-submit.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    'http_req_duration{name:submit}': ['p(95)<300'],
    'http_req_failed{name:submit}': ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const submitLatency = new Trend('submit_latency', true);

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: 'anna', password: 'anna' }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'login ok': (r) => r.status === 200 });
  return { token: res.json('accessToken') };
}

export default function (data) {
  const body = JSON.stringify({
    policyNumber: `POL-${__VU}`,
    plateNumber: `WA ${10000 + (__ITER % 89999)}`,
    incidentDate: new Date().toISOString().slice(0, 10),
    description: 'k6 load test claim: rear bumper dented in a car park',
    estimatedAmount: 800,
  });
  const res = http.post(`${BASE}/api/v1/claims`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.token}`,
      'X-Client-Id': `k6-vu-${__VU}`,          // one rate-limit bucket per VU
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { name: 'submit' },
  });
  submitLatency.add(res.timings.duration);
  check(res, { created: (r) => r.status === 201, 'not rate limited': (r) => r.status !== 429 });
  sleep(0.1);
}
