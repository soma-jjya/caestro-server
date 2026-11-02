import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// Loading source as data URLs also supports Windows environments with restricted parent-directory traversal.
const load = async name => import(`data:text/javascript;base64,${Buffer.from(
  fs.readFileSync(`load-test/k6/b2b/${name}`, 'utf8')).toString('base64')}`);
const { config, target, scenario } = await load('config.mjs');
const { workflow, probeSharedIp } = await load('workflow.mjs');

test('production and ambiguous targets fail before any request; remote host must be explicit', () => {
  for (const base of ['https://api.peakpic.app', 'https://peakpic.app', 'https://API.PEAKPIC.APP',
    'https://di519bna44kn1.cloudfront.net', 'http://localhost@api.peakpic.app',
    'https://api.peakpic.app.', 'http://localhost/path', 'http://localhost?x=1', 'http://localhost#x']) {
    assert.throws(() => target({ BASE_URL: base, TEST_ENV: 'isolated', CONFIRM_TEST_HOST: 'api.peakpic.app' }));
  }
  assert.equal(target({}), 'http://localhost:8081');
  assert.throws(() => target({ BASE_URL: 'https://staging.example.org' }));
  assert.equal(target({ BASE_URL: 'https://staging.example.org', TEST_ENV: 'isolated',
    CONFIRM_TEST_HOST: 'staging.example.org' }), 'https://staging.example.org');
});

test('arrival profiles keep explicit rate, graceful drain and invalid configuration rejection', () => {
  for (const profile of ['smoke', 'steady', 'ramp', 'spike']) {
    const s = scenario(config({ PROFILE: profile }));
    assert.ok(s.gracefulStop);
    if (profile !== 'smoke') assert.equal(s.timeUnit, '1m');
  }
  for (const env of [{ PROJECTS: '0' }, { POLL_SECONDS: '0' }, { ARRIVALS_PER_MINUTE: 'NaN' },
    { PROFILE: 'production' }, { MAX_VUS: '1', PREALLOCATED_VUS: '2' }]) assert.throws(() => config(env));
});

function harness(override = () => undefined, frames = 1) {
  const calls = [], measures = [], logs = [];
  let now = 0;
  let filled = 0;
  let exists = true;
  const data = { run: 'abc', marker: 'k6-b2b-219:abc', projects: [{ id: 'bp_test', slug: 'lt-b2b-abc-0' }] };
  const frame = i => ({ id: `bf_${i}`, version: 7, imageUrl: '/b2b/images/test', windows: [1, 2, 3, 4] });
  const io = {
    now: () => now, sleep: seconds => { now += seconds * 1000; },
    expiresAt: token => token === 'expired' ? 0 : now + 3600000,
    measure: (name, value, tags) => measures.push({ name, value, tags }), log: message => logs.push(message),
    request: call => {
      calls.push(call);
      const custom = override(call);
      if (custom) return { status: custom.status, json: () => custom.body };
      let body = {};
      let status = 200;
      if (call.path === '/catalog/studio/images') { status = 201; body = { imageId: 'ci_test' }; }
      else if (call.path === '/b2b/admin/projects' && call.method === 'POST') {
        status = 201; body = { id: 'bp_test' };
      } else if (call.path === '/b2b/admin/projects/bp_test' && call.method === 'GET') {
        status = exists ? 200 : 404;
        body = { id: 'bp_test', slug: data.projects[0].slug, description: data.marker, status: 'LIVE' };
      } else if (call.method === 'DELETE') { exists = false; status = 204; }
      else if (call.path === '/auth/guest' || call.path === '/auth/refresh') {
        body = { accessToken: 'valid', refreshToken: 'secret' };
      } else if (call.path === '/b2b/projects/lt-b2b-abc-0') {
        body = { frames: Array.from({ length: frames }, (_, i) => frame(i)) };
      } else if (call.stage === 'create') { status = 201; body = { fill: { code: 'ABCDEF' } }; }
      else if (call.stage === 'upload') filled++;
      else if (call.stage === 'read') body = { fill: { slots: Array.from({ length: 4 }, (_, i) =>
        ({ index: i, imageUrl: i < filled ? `/fills/ABCDEF/images/${i}` : null })) } };
      return { status, json: () => body };
    },
  };
  return { calls, measures, logs, data, io, runner: workflow(config({}), io) };
}

for (const frames of [1, 2, 3]) test(`journey selects from ${frames} frames, sends version and four raw uploads`, () => {
  const h = harness(undefined, frames);
  h.runner.journey(h.data, 2, 'binary');
  const uploads = h.calls.filter(c => c.stage === 'upload');
  assert.deepEqual(uploads.map(c => c.path), [0, 1, 2, 3].map(i => `/fills/ABCDEF/slots/${i}`));
  assert.ok(uploads.every(c => c.raw && c.credential === 'valid'));
  assert.equal(h.calls.find(c => c.stage === 'create').body.frameVersion, 7);
  assert.equal(h.calls.filter(c => c.stage === 'read').length, 5);
  assert.equal(h.measures.find(m => m.name === 'journey_success').value, true);
  assert.equal(h.calls.filter(c => c.method === 'DELETE').length, 0);
});

test('auth throttling stops the journey without creating fills or retry loops', () => {
  const h = harness(c => c.stage === 'guest' ? { status: 429 } : undefined);
  h.runner.journey(h.data, 0, 'binary');
  assert.deepEqual(h.calls.map(c => c.stage), ['entry', 'guest']);
  assert.equal(h.measures.find(m => m.name === 'journey_success').value, false);
});

test('expired JWT refreshes before create; refresh rejection stops without retrying', () => {
  for (const refreshStatus of [200, 401]) {
    const h = harness(c => c.stage === 'guest'
      ? { status: 200, body: { accessToken: 'expired', refreshToken: 'secret' } }
      : c.stage === 'refresh' ? { status: refreshStatus, body: { accessToken: 'valid', refreshToken: 'rotated' } } : undefined);
    h.runner.journey(h.data, 0, 'binary');
    assert.equal(h.calls.filter(c => c.stage === 'refresh').length, 1);
    assert.equal(h.measures.find(m => m.name === 'journey_success').value, refreshStatus === 200);
    if (refreshStatus === 401) assert.equal(h.calls.some(c => c.stage === 'create'), false);
  }
});

test('four successful uploads are insufficient when final slots are incomplete', () => {
  const h = harness(c => c.stage === 'read' ? { status: 200, body: { fill: { slots: [] } } } : undefined);
  h.runner.journey(h.data, 0, 'binary');
  assert.equal(h.measures.find(m => m.name === 'journey_success').value, false);
});

test('setup publishes its own fixture; teardown checks ownership before pause/delete', () => {
  const h = harness();
  const data = h.runner.setup('abc', 'binary');
  h.runner.cleanup(data);
  assert.ok(h.calls.find(c => c.path.endsWith('/publish')));
  assert.deepEqual(h.calls.slice(-3).map(c => [c.method, c.path]), [
    ['GET', '/b2b/admin/projects/bp_test'], ['POST', '/b2b/admin/projects/bp_test/pause'],
    ['DELETE', '/b2b/admin/projects/bp_test'],
  ]);
});

test('partial setup failure cleans the recorded project even without k6 teardown', () => {
  const h = harness(c => c.path.endsWith('/frames') ? { status: 409 } : undefined);
  assert.throws(() => h.runner.setup('abc', 'binary'), /setup: HTTP 409/);
  assert.equal(h.calls.filter(c => c.method === 'DELETE').length, 1);
});

test('cleanup refuses mismatched marker, id or slug', () => {
  for (const field of ['id', 'slug', 'description']) {
    const h = harness(c => c.method === 'GET' && c.stage === 'cleanup' ? { status: 200,
      body: { id: 'bp_test', slug: 'lt-b2b-abc-0', description: 'k6-b2b-219:abc', [field]: 'changed' } } : undefined);
    assert.throws(() => h.runner.cleanup(h.data), /incomplete/);
    assert.equal(h.calls.some(c => c.method === 'DELETE' || c.path.endsWith('/pause')), false);
    assert.equal(h.measures.find(m => m.name === 'cleanup_failed').value, true);
  }
});

test('shared-IP probe requires expected allowance, 429s and recovery', () => {
  for (const mode of ['valid', 'slow', 'no-limit', 'no-recovery', 'server-error']) {
    let now = 0;
    const result = probeSharedIp({ limit: 30, extra: 5, window: 60 }, {
      now: () => now, sleep: seconds => { now += seconds * 1000; },
      guest: i => {
        now += mode === 'slow' ? 2000 : 10;
        return { status: mode === 'server-error' ? 500 : i === 35 ? (mode === 'no-recovery' ? 429 : 200)
          : i < 30 || mode === 'no-limit' ? 200 : 429 };
      },
    });
    assert.equal(result.validBurst && result.recovered, mode === 'valid');
  }
});
