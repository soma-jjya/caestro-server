import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Exercise the real k6 adapter as well as the transport-independent workflow tests.
// Execution-state getters deliberately fail outside a VU, as k6 1.0.0 does in teardown.
async function adapter({ journey = () => {}, cleanup = () => {}, sums = new Map() } = {}) {
  const context = vm.createContext({
    __ENV: { STUDIO_KEY: 'test-only' }, open: () => new ArrayBuffer(1024),
    console: { log() {} },
  });
  class Metric {
    constructor(name) { this.name = name; }
    add(value) { sums.set(this.name, (sums.get(this.name) || 0) + Number(value)); }
  }
  const execution = {
    scenario: { iterationInTest: 0 },
    get instance() { throw new Error('getting instance information in the init context is not supported'); },
  };
  const stubs = {
    'k6/http': { default: { request() { throw new Error('No live HTTP in adapter tests'); } } },
    k6: { sleep() {} },
    'k6/execution': { default: execution },
    'k6/encoding': { default: {} },
    'k6/metrics': { Counter: Metric, Rate: Metric, Trend: Metric },
    './b2b/workflow.mjs': { workflow: () => ({ journey, cleanup }) },
  };
  const entry = new vm.SourceTextModule(fs.readFileSync('load-test/k6/b2b-session.js', 'utf8'), { context });
  await entry.link(specifier => {
    if (['./b2b/config.mjs', './b2b/lifecycle.mjs'].includes(specifier)) {
      return new vm.SourceTextModule(fs.readFileSync(`load-test/k6/${specifier}`, 'utf8'), { context });
    }
    const exports = stubs[specifier];
    assert.ok(exports, `Unexpected import: ${specifier}`);
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [key, value] of Object.entries(exports)) this.setExport(key, value);
    }, { context });
  });
  await entry.evaluate();
  return { script: entry.namespace, sums };
}

test('teardown cleans the recorded fixtures without accessing VU execution state', async () => {
  const data = { projects: [{ id: 'bp_own' }] };
  let cleaned;
  const { script } = await adapter({ cleanup: value => { cleaned = value; } });
  script.teardown(data);
  assert.equal(cleaned, data);
});

test('a cleanup failure still fails teardown', async () => {
  const { script } = await adapter({ cleanup: () => { throw new Error('cleanup incomplete'); } });
  assert.throws(() => script.teardown({ projects: [] }), /cleanup incomplete/);
});

test('finished VUs cannot erase an interrupted journey from the final balance', async () => {
  const sums = new Map();
  const completed = await adapter({ sums });
  completed.script.default({});
  assert.equal(sums.get('b2b_unfinished_journeys'), 0);

  const interrupted = await adapter({ sums, journey: () => { throw new Error('VU interrupted'); } });
  assert.throws(() => interrupted.script.default({}), /VU interrupted/);
  // A different VU finishing later must not reset the outstanding count to zero.
  completed.script.default({});
  assert.equal(sums.get('b2b_unfinished_journeys'), 1);
  assert.deepEqual(Array.from(completed.script.options.thresholds.b2b_unfinished_journeys), ['count==0']);
});
