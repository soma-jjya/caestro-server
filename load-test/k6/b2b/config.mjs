// Shared by k6 and offline contract tests. No network access during configuration.
export function target(env) {
  const base = (env.BASE_URL || 'http://localhost:8081').replace(/\/+$/, '');
  const match = /^(https?):\/\/([a-zA-Z0-9.-]+)(?::([0-9]+))?$/.exec(base);
  if (!match) throw new Error('BASE_URL must be an origin without credentials, path, query or fragment');
  const host = match[2].toLowerCase();
  if (host.endsWith('.') || host === 'peakpic.app' || host.endsWith('.peakpic.app') ||
      host === 'di519bna44kn1.cloudfront.net') throw new Error('Production host is blocked');
  const local = ['localhost', '127.0.0.1', 'host.docker.internal'].includes(host);
  if (!local && (env.TEST_ENV !== 'isolated' || env.CONFIRM_TEST_HOST !== host)) {
    throw new Error('Remote target requires TEST_ENV=isolated and CONFIRM_TEST_HOST=<exact host>');
  }
  if (!local && match[1] !== 'https') throw new Error('Remote target must use HTTPS');
  return base;
}

export function integer(env, key, fallback, min, max) {
  const value = env[key] === undefined ? fallback : Number(env[key]);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`Invalid ${key}: ${min}..${max}`);
  return value;
}

export function config(env) {
  const profile = env.PROFILE || 'smoke';
  if (!['smoke', 'steady', 'ramp', 'spike'].includes(profile)) throw new Error('Unknown PROFILE');
  const c = {
    base: target(env), profile,
    projects: integer(env, 'PROJECTS', 1, 1, 20),
    rate: integer(env, 'ARRIVALS_PER_MINUTE', 6, 1, 6000),
    seconds: integer(env, 'DURATION_SECONDS', 60, 10, 86400),
    hold: integer(env, 'HOLD_SECONDS', 20, 4, 3600),
    poll: integer(env, 'POLL_SECONDS', 5, 1, 60),
    vus: integer(env, 'PREALLOCATED_VUS', 10, 1, 10000),
    maxVus: integer(env, 'MAX_VUS', 100, 1, 10000),
    apiP95: integer(env, 'API_P95_MS', 1000, 1, 60000),
    uploadP95: integer(env, 'UPLOAD_P95_MS', 3000, 1, 60000),
  };
  if (c.maxVus < c.vus) throw new Error('MAX_VUS must be >= PREALLOCATED_VUS');
  return c;
}

export function scenario(c) {
  const gracefulStop = `${c.hold + 180}s`;
  if (c.profile === 'smoke') return { executor: 'shared-iterations', vus: 1, iterations: c.projects,
    maxDuration: `${c.projects * (c.hold + 180)}s`, gracefulStop };
  const common = { timeUnit: '1m', preAllocatedVUs: c.vus, maxVUs: c.maxVus, gracefulStop };
  if (c.profile === 'steady') return { ...common, executor: 'constant-arrival-rate',
    rate: c.rate, duration: `${c.seconds}s` };
  const low = Math.max(1, Math.floor(c.rate / 10));
  return { ...common, executor: 'ramping-arrival-rate', startRate: low,
    stages: c.profile === 'ramp'
      ? [{ duration: `${c.seconds}s`, target: c.rate }, { duration: `${c.seconds}s`, target: c.rate }]
      : [{ duration: `${c.seconds}s`, target: low }, { duration: '1s', target: c.rate },
        { duration: `${c.seconds}s`, target: c.rate }, { duration: '1s', target: low }] };
}
