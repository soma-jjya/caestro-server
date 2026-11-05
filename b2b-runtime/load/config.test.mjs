import test from 'node:test';
import assert from 'node:assert/strict';
import {configuration, verifyGuestBudget} from './config.mjs';
test('only explicit isolated origins can receive load', () => {
  assert.throws(() => configuration({TARGET_ORIGIN: 'https://api.peakpic.app', CONFIRM_TEST_ORIGIN: 'https://api.peakpic.app'}));
  assert.throws(() => configuration({TARGET_ORIGIN: 'https://test.example.com'}));
  assert.throws(() => configuration({TARGET_ORIGIN: 'https://user:pass@test.example.com'}));
  assert.equal(configuration({TARGET_ORIGIN: 'https://test.example.com', CONFIRM_TEST_ORIGIN: 'https://test.example.com'}).origin, 'https://test.example.com');
});
test('single-egress capacity cannot hide a restrictive guest IP quota', () => {
  const id = {guestIpLimit: 30, guestIpWindowSeconds: 60, guestRuntimeLimitPerMinute: 300};
  assert.throws(() => verifyGuestBudget(id, configuration({PROFILE: 'capacity', ARRIVALS_PER_MINUTE: '90'})));
  assert.doesNotThrow(() => verifyGuestBudget(id, configuration({PROFILE: 'capacity', ARRIVALS_PER_MINUTE: '20'})));
  assert.throws(() => verifyGuestBudget({...id, guestRuntimeLimitPerMinute: 10}, configuration({PROFILE: 'capacity', ARRIVALS_PER_MINUTE: '20'})));
});
test('invalid and unbounded load inputs are refused', () => {
  for (const value of ['-1', 'NaN', 'Infinity', '1.5', '0']) assert.throws(() => configuration({ARRIVALS_PER_MINUTE: value}));
  assert.throws(() => configuration({PROJECT_SLUG: '../../auth'}));
  assert.throws(() => configuration({MAX_VUS: '2001'}));
});
