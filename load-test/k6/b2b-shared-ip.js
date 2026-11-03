import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { target, integer } from './b2b/config.mjs';
import { probeSharedIp } from './b2b/workflow.mjs';

const base = target(__ENV);
const c = { limit: integer(__ENV, 'GUEST_LIMIT', 30, 1, 1000),
  window: integer(__ENV, 'GUEST_WINDOW_SECONDS', 60, 1, 3600),
  extra: integer(__ENV, 'EXTRA_GUESTS', 5, 1, 1000) };
const accepted = new Counter('b2b_nat_accepted');
const rejected = new Counter('b2b_nat_rejected');
export const options = {
  scenarios: { shared_ip: { executor: 'shared-iterations', vus: 1, iterations: 1,
    maxDuration: `${2 * (c.window + 1) + (c.limit + c.extra + 1) * 10}s` } },
  systemTags: ['status', 'method', 'name', 'expected_response'],
  thresholds: { checks: ['rate==1'] },
};
export default function () {
  const run = Date.now().toString(36);
  const result = probeSharedIp(c, {
    now: () => Date.now(), sleep,
    guest: i => http.post(`${base}/auth/guest`, JSON.stringify({ deviceId: `lt-nat-${run}-${i}` }), {
      headers: { 'Content-Type': 'application/json' }, redirects: 0, timeout: '10s',
      tags: { name: 'b2b_nat_guest' }, responseCallback: http.expectedStatuses(200, 429),
    }),
  });
  accepted.add(result.accepted);
  rejected.add(result.rejected);
  check(result, { 'configured shared-IP allowance and 429 observed within one window': r => r.validBurst,
    'authentication recovers after the window': r => r.recovered });
  console.log(JSON.stringify(result)); // counts only; never token bodies
}
