import http from 'k6/http';
import {check, sleep} from 'k6';
import {Rate, Counter} from 'k6/metrics';
import {configuration} from './config.mjs';

const c = configuration(__ENV);
if (!__ENV.B2B_ADMIN_KEY) throw new Error('Test runtime admin key required');
const contract = new Rate('b2b_nat_contract');
const accepted = new Counter('b2b_nat_accepted');
const denied = new Counter('b2b_nat_rejected');
http.setResponseCallback(http.expectedStatuses(200, 429));
export const options = {
  scenarios: {nat: {executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '6m'}},
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  thresholds: {b2b_nat_contract: ['rate==1']},
};
function assert(value, label) { contract.add(check(value, {[label]: v => Boolean(v)})); if (!value) throw new Error(label); }
function scope(response) { return response.headers['X-Ratelimit-Scope'] || response.headers['X-RateLimit-Scope']; }
function guest(extra = {}) {
  return http.post(c.base + '/auth/guest', null, {headers: extra, redirects: 0, timeout: '5s', tags: {name: 'nat_guest'}});
}
export function setup() {
  const r = http.get(c.base + '/admin/runtime', {headers: {'X-B2B-Admin-Key': __ENV.B2B_ADMIN_KEY},
    redirects: 0, timeout: '5s', tags: {name: 'nat_verify'}});
  if (r.status !== 200) throw new Error('Cannot verify target');
  const id = r.json();
  if (id.environment !== 'test' || id.slug !== c.slug) throw new Error('Only isolated test runtimes are permitted');
  if (!Number.isInteger(id.guestIpLimit) || id.guestIpLimit < 1 || id.guestIpLimit > 100 ||
      !Number.isInteger(id.guestIpWindowSeconds) || id.guestIpWindowSeconds < 10 || id.guestIpWindowSeconds > 120 ||
      id.guestRuntimeLimitPerMinute < id.guestIpLimit) throw new Error('Quota requires a separately reviewed probe');
  return id;
}
export default function (id) {
  // No quota deletion/override: let existing windows expire. Run this probe alone on a quiet test runtime.
  sleep(Math.max(60, id.guestIpWindowSeconds) + 2);
  const started = Date.now();
  let refresh;
  for (let n = 0; n < id.guestIpLimit; n++) {
    const r = guest();
    assert(r.status === 200, 'quota-sized burst accepted');
    accepted.add(1);
    if (n === 0) refresh = r.json('refreshToken');
  }
  for (let n = 0; n < 5; n++) {
    // Deliberately forged LEFT hop: must NOT manufacture a new client IP or a fresh budget.
    const r = guest({'X-Forwarded-For': `198.51.100.${n + 1}`});
    assert(r.status === 429 && scope(r) === 'guest-ip' && Number(r.headers['Retry-After']) > 0 &&
      r.json('status') === 429 && typeof r.json('message') === 'string' && r.json('message').length > 0,
      'IP rejection includes scope, retry and FE message despite forged header');
    denied.add(1);
  }
  assert(Date.now() - started < id.guestIpWindowSeconds * 1000, 'probe completed inside one window');
  const renewed = http.post(c.base + '/auth/refresh', JSON.stringify({refreshToken: refresh}), {
    headers: {'Content-Type': 'application/json'}, redirects: 0, timeout: '5s', tags: {name: 'nat_refresh'}});
  assert(renewed.status === 200, 'existing guests may refresh while new issuance is limited');
  sleep(Math.max(60, id.guestIpWindowSeconds) + 2);
  assert(guest().status === 200, 'issuance recovers after quota window');
}
export function handleSummary(data) {
  return {'load-test/results/shared-ip.json': JSON.stringify({profile: 'shared-ip', origin: c.origin, slug: c.slug,
    note: 'Contract success is not festival readiness: legitimate users above this NAT quota still receive 429.', summary: data}, null, 2)};
}
