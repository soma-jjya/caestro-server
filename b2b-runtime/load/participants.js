import http from 'k6/http';
import {sleep} from 'k6';
import {Counter, Rate} from 'k6/metrics';
import {configuration, verifyGuestBudget} from './config.mjs';

const c = configuration(__ENV);
if (!__ENV.B2B_ADMIN_KEY) throw new Error('An isolated runtime admin key is required for target verification');
const photo = open(__ENV.PHOTO_FILE || './photo.png', 'b');
if (photo.byteLength < 1024 || photo.byteLength > 3145728) throw new Error('Use a representative image of 1 KiB..3 MiB');
const success = new Rate('b2b_journey_success');
const unfinished = new Counter('b2b_unfinished_journeys');
const rejections = new Counter('b2b_429');
export const options = {
  scenarios: {participants: c.profile === 'smoke'
    ? {executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '2m'}
    : {executor: 'constant-arrival-rate', rate: c.rate, timeUnit: '1m', duration: `${c.durationMinutes}m`,
       preAllocatedVUs: Math.min(c.maxVus, Math.max(2, c.rate)), maxVUs: c.maxVus, gracefulStop: '2m'}},
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  thresholds: {
    b2b_journey_success: ['rate>=0.99'], b2b_unfinished_journeys: ['count==0'],
    http_req_failed: ['rate<0.01'], 'http_req_duration{stage:upload}': ['p(95)<3000'],
    'http_req_duration{stage:read}': ['p(95)<1000'],
    'b2b_429{scope:guest-ip}': ['count==0'], 'b2b_429{scope:guest-runtime}': ['count==0'],
    'b2b_429{scope:other}': ['count==0'],
    ...(c.profile === 'smoke' ? {} : {dropped_iterations: ['count==0']}),
  },
};
function request(method, path, stage, status, token, body, admin = false) {
  const headers = {'Content-Type': stage === 'upload' ? 'image/png' : 'application/json'};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (admin) headers['X-B2B-Admin-Key'] = __ENV.B2B_ADMIN_KEY;
  const response = http.request(method, c.base + path, stage === 'upload' ? body : body ? JSON.stringify(body) : null,
    {headers, redirects: 0, timeout: '10s', tags: {name: `b2b_${stage}`, stage}});
  if (response.status === 429) {
    const scope = response.headers['X-Ratelimit-Scope'] || response.headers['X-RateLimit-Scope'];
    rejections.add(1, {scope: ['guest-ip', 'guest-runtime'].includes(scope) ? scope : 'other'});
  }
  if (response.status !== status) throw new Error(`${stage} returned ${response.status}`);
  return response.json();
}
export function setup() {
  const identity = request('GET', '/admin/runtime', 'verify', 200, null, null, true);
  if (identity.environment !== 'test' || identity.slug !== c.slug) throw new Error('Only a test runtime can receive load');
  verifyGuestBudget(identity, c);
  const project = request('GET', '/', 'entry', 200);
  if (!project.frames || project.frames.length < 1 || project.frames.length > 3) throw new Error('Publish the isolated fixture first');
  return {identity, frame: project.frames[0]};
}
export default function (data) {
  for (const scope of ['guest-ip', 'guest-runtime', 'other']) rejections.add(0, {scope});
  unfinished.add(1);
  let passed = false;
  try {
    request('GET', '/', 'entry', 200);
    const tokens = request('POST', '/auth/guest', 'guest', 200);
    const created = request('POST', '/fills', 'create', 201, tokens.accessToken,
      {frameId: data.frame.id, frameVersion: data.frame.version});
    const code = created.fill.code;
    for (let index = 0; index < 4; index++) {
      sleep(5);
      request('GET', `/fills/${code}`, 'read', 200, tokens.accessToken);
      request('PUT', `/fills/${code}/slots/${index}`, 'upload', 200, tokens.accessToken, photo);
    }
    const done = request('GET', `/fills/${code}`, 'read', 200, tokens.accessToken);
    passed = done.fill.slots.length === 4 && done.fill.slots.every(slot => Boolean(slot.imageUrl));
  } catch (_) { passed = false; }
  success.add(passed);
  // An interrupted VU never reaches here; a positive residual fails the test.
  unfinished.add(-1);
}
export function handleSummary(data) {
  return {'load-test/results/participants.json': JSON.stringify({profile: c.profile, origin: c.origin, slug: c.slug,
    arrivalsPerMinute: c.rate, durationMinutes: c.durationMinutes, photoBytes: photo.byteLength, summary: data}, null, 2)};
}
