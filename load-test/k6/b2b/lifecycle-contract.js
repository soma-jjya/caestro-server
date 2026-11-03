// Offline checks using the real pinned k6 runtime. No API requests are sent.
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { teardown as cleanupFixtures } from '../b2b-session.js';
import { trackJourney } from './lifecycle.mjs';

const unfinished = new Counter('lifecycle_unfinished');
export const options = {
  scenarios: { lifecycle: { executor: 'shared-iterations', vus: 1, iterations: 1,
    maxDuration: '1s', gracefulStop: '0s' } },
  thresholds: { lifecycle_unfinished: ['count==0'], b2b_cleanup_failed: ['rate==0'] },
};

export default function () {
  trackJourney(unfinished, () => {
    if (__ENV.INTERRUPT === '1') sleep(2);
  });
}

export function teardown() {
  // Empty owned fixture list tests the actual adapter teardown without HTTP access.
  cleanupFixtures({ projects: [] });
}
