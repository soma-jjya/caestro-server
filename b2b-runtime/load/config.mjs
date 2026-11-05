export function configuration(env) {
  const origin = env.TARGET_ORIGIN || 'http://127.0.0.1:18090';
  // k6 has no browser URL implementation; deliberately accept a narrow origin grammar.
  if (!/^https:\/\/[a-z0-9.-]+(?::443)?$/.test(origin) && !/^http:\/\/127\.0\.0\.1:(18090|8080)$/.test(origin)) {
    throw new Error('Use an HTTPS origin or the isolated local Compose origin');
  }
  const host = origin.replace(/^https?:\/\//, '').split(':')[0];
  if (host === 'api.peakpic.app' || host === 'peakpic.app' || host.endsWith('.cloudfront.net')) {
    throw new Error('B2C and CDN targets are forbidden');
  }
  if (host !== '127.0.0.1' && env.CONFIRM_TEST_ORIGIN !== origin) throw new Error('Confirm the exact isolated origin');
  const slug = env.PROJECT_SLUG || 'yuja-test';
  if (!/^[a-z0-9-]{3,40}$/.test(slug)) throw new Error('Invalid slug');
  const profile = env.PROFILE || 'smoke';
  if (!['smoke', 'capacity'].includes(profile)) throw new Error('Invalid profile');
  const integer = (value, fallback, max) => {
    const number = value === undefined ? fallback : Number(value);
    if (!Number.isInteger(number) || number < 1 || number > max) throw new Error('Invalid load limit');
    return number;
  };
  return {origin, slug, base: `${origin}/projects/${slug}`, profile,
    rate: integer(env.ARRIVALS_PER_MINUTE, 1, 10000), maxVus: integer(env.MAX_VUS, 2, 2000),
    durationMinutes: integer(env.DURATION_MINUTES, 10, 120)};
}

export function verifyGuestBudget(identity, config) {
  if (![identity.guestIpLimit, identity.guestIpWindowSeconds, identity.guestRuntimeLimitPerMinute]
      .every(n => Number.isInteger(n) && n > 0)) throw new Error('Missing guest quota contract');
  if (config.profile === 'capacity' && config.rate > Math.min(identity.guestIpLimit * 60 / identity.guestIpWindowSeconds,
      identity.guestRuntimeLimitPerMinute)) {
    throw new Error('This runner exceeds the guest budget. Run shared-ip.js to measure NAT rejection; use real separate egress IPs for capacity. Never spoof XFF or silently raise quotas.');
  }
}
