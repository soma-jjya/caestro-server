import unittest
from capacity_plan import plan

class CapacityPlanTest(unittest.TestCase):
    def request(self):
        return {'peakParticipantsPer10Minutes': 600, 'maxVirtualUsers': 200, 'peakNewGuestsPerIpPerMinute': 60,
                'startsAt': '2026-12-01T09:00:00+09:00', 'endsAt': '2026-12-02T18:00:00+09:00',
                'downloadsUntil': '2026-12-09T18:00:00+09:00'}
    def test_arrival_model_and_pending_measurement(self):
        result = plan(self.request())
        self.assertEqual(result['load']['ARRIVALS_PER_MINUTE'], 90)
        self.assertEqual(result['load']['MAX_VUS'], 90)
        self.assertEqual(result['status'], 'NEEDS_MEASUREMENT')
        self.assertEqual(result['schedule']['startsAt'], '2026-12-01T00:00:00Z')
        self.assertEqual(result['suggestedGuestLimitPerMinute'], 108)
        self.assertEqual(result['guestAdmission']['status'], 'POLICY_REVIEW_REQUIRED')
        self.assertEqual(result['guestAdmission']['minimumDistinctRunnerEgressIps'], 3)
        self.assertFalse(result['guestAdmission']['automaticallyChangeLimit'])
    def test_excess_load_is_not_silently_capped(self):
        request = self.request(); request['maxVirtualUsers'] = 20
        with self.assertRaises(ValueError): plan(request)
    def test_invalid_time_and_nonfinite_factor_rejected(self):
        request = self.request(); request['downloadsUntil'] = request['startsAt']
        with self.assertRaises(ValueError): plan(request)
        request = self.request(); request['safetyFactor'] = float('nan')
        with self.assertRaises(ValueError): plan(request)
    def test_changed_configuration_requires_new_evidence(self):
        first = plan(self.request())['requestSha256']
        request = self.request(); request['peakParticipantsPer10Minutes'] += 1
        self.assertNotEqual(first, plan(request)['requestSha256'])

if __name__ == '__main__': unittest.main()
