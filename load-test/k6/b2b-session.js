import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';
import { config, scenario } from './b2b/config.mjs';
import { workflow } from './b2b/workflow.mjs';

const c = config(__ENV);
if (!__ENV.STUDIO_KEY) throw new Error('STUDIO_KEY for the isolated test environment is required');
const photo = open(__ENV.PHOTO_FILE || './b2b/generated/photo.png', 'b');
if (photo.byteLength < 1024 || photo.byteLength > 3 * 1024 * 1024) throw new Error('Use a representative PNG of 1 KiB..3 MiB');
const metrics = {
  request_failed: new Rate('b2b_request_failed'),
  cleanup_failed: new Rate('b2b_cleanup_failed'),
  journey_success: new Rate('b2b_journey_success'),
  journey_error: new Counter('b2b_journey_errors'),
  interrupted: new Counter('b2b_interrupted_iterations'),
  journey_duration: new Trend('b2b_journey_duration', true),
};
export const options = {
  scenarios: { participants: scenario(c) },
  setupTimeout: '5m', teardownTimeout: '10m',
  // Exclude raw URL/error/group tags: IDs, signed URLs and secrets must not reach time-series labels.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  thresholds: {
    b2b_journey_success: ['rate>=0.99'],
    b2b_request_failed: ['rate<0.01'],
    b2b_cleanup_failed: ['rate==0'],
    b2b_interrupted_iterations: ['count==0'],
    'http_req_duration{stage:entry}': [`p(95)<${c.apiP95}`],
    'http_req_duration{stage:create}': [`p(95)<${c.apiP95}`],
    'http_req_duration{stage:read}': [`p(95)<${c.apiP95}`],
    'http_req_duration{stage:upload}': [`p(95)<${c.uploadP95}`],
    ...(c.profile === 'smoke' ? {} : { dropped_iterations: ['count==0'] }),
  },
};

const runner = workflow(c, {
  request({ method, path, body, credential, stage, raw }) {
    const headers = { 'Content-Type': raw ? 'image/png' : 'application/json' };
    if (credential === 'studio') headers['X-Studio-Key'] = __ENV.STUDIO_KEY;
    else if (credential) headers.Authorization = `Bearer ${credential}`;
    // Never follow a redirect with a studio key/JWT. CDN/canvas is a separate browser verification.
    return http.request(method, `${c.base}${path}`, raw ? body : body == null ? null : JSON.stringify(body),
      { headers, redirects: 0, timeout: '10s', tags: { name: `b2b_${stage}`, stage } });
  },
  measure(name, value, tags) { metrics[name].add(value, tags || {}); },
  sleep, now: () => Date.now(), log: message => console.log(message),
  expiresAt(token) {
    const expiry = JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's')).exp;
    if (!Number.isFinite(expiry)) throw new Error('Token has no expiry');
    return expiry * 1000;
  },
});

export function setup() {
  console.log(`Profile=${c.profile}, projects=${c.projects}, arrivals/min=${c.rate}, photoBytes=${photo.byteLength}`);
  const run = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  return runner.setup(run, photo);
}
export default function (data) { runner.journey(data, exec.scenario.iterationInTest, photo); }
export function teardown(data) {
  // Local single-process runner: interrupted journeys must not disappear from the success denominator.
  metrics.interrupted.add(exec.instance.iterationsInterrupted);
  runner.cleanup(data);
}
