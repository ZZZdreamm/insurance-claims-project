// k6 run --vus 20 --duration 60s perf/k6-submit.js
// Measures the ingestion endpoint (POST /api/v1/claims) which also writes the outbox row
// and starts the process instance in the same transaction.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const body = JSON.stringify({
    policyNumber: `POL-${Math.floor(Math.random() * 100000)}`,
    plateNumber: `WA ${Math.floor(Math.random() * 90000 + 10000)}`,
    incidentDate: new Date().toISOString().slice(0, 10),
    description: 'k6 load test claim: rear bumper dented in a car park',
    estimatedAmount: 800,
  });
  const res = http.post(`${BASE}/api/v1/claims`, body, { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'created': (r) => r.status === 201 });
  sleep(0.2);
}
