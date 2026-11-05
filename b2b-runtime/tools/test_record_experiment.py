import copy
import unittest
from record_experiment import build_record, REQUIRED_CONFIG, REQUIRED_OBSERVATIONS, REQUIRED_THRESHOLDS

class RecordExperimentTest(unittest.TestCase):
    def inputs(self):
        experiment = {'environment': 'test', 'configuration': {key: 'test-fixture' for key in REQUIRED_CONFIG},
            'workload': {'arrivalsPerMinute': 20, 'durationMinutes': 60, 'photoBytes': 1500000,
                         'peakNewGuestsPerIpPerMinuteWithMargin': 20}}
        experiment['configuration'].update(instanceType='t3.micro', instanceCount=2, guestIpLimit=30, guestIpWindowSeconds=60)
        journey = {'profile': 'capacity', 'origin': 'https://isolated.example', 'slug': 'test',
                   'arrivalsPerMinute': 20, 'durationMinutes': 60, 'photoBytes': 1500000,
                   'summary': {'metrics': {'iterations': {'values': {'count': 1200}},
                       'b2b_journey_success': {'thresholds': {'rate>=0.99': {'ok': True}}}}}}
        for metric in REQUIRED_THRESHOLDS:
            journey['summary']['metrics'].setdefault(metric, {'thresholds': {'fixture-condition': {'ok': True}}})
        nat = {'profile': 'shared-ip', 'origin': journey['origin'], 'slug': journey['slug'],
               'summary': {'metrics': {'b2b_nat_contract': {'thresholds': {'rate==1': {'ok': True}}}}}}
        observations = dict.fromkeys(REQUIRED_OBSERVATIONS, 1)
        observations.update(t3SustainedLoadReviewed=True, representativePhotoReviewed=True, baselineParityReviewed=True)
        return experiment, journey, nat, observations
    def test_missing_observation_cannot_pass(self):
        e, j, n, o = self.inputs(); del o['cpuCreditBalanceEndMin']
        self.assertEqual(build_record(e, j, n, o)['verdict'], 'INCOMPLETE')
    def test_nat_policy_failure_cannot_be_fixed_by_more_servers(self):
        e, j, n, o = self.inputs(); e['workload']['peakNewGuestsPerIpPerMinuteWithMargin'] = 90
        self.assertEqual(build_record(e, j, n, o)['verdict'], 'BLOCKED_GUEST_POLICY')
    def test_one_change_and_same_workload_required(self):
        e, j, n, o = self.inputs(); first = build_record(copy.deepcopy(e), j, n, o)
        self.assertEqual(first['verdict'], 'CANDIDATE_FOR_REVIEW')
        e['configuration']['instanceCount'] = 4
        self.assertEqual(build_record(e, j, n, o, first)['changedFields'], ['instanceCount'])
        e['configuration']['instanceType'] = 't3.small'
        with self.assertRaises(ValueError): build_record(e, j, n, o, first)
    def test_smoke_and_zero_iterations_are_not_capacity(self):
        e, j, n, o = self.inputs(); j['summary']['metrics']['iterations']['values']['count'] = 0
        self.assertEqual(build_record(e, j, n, o)['verdict'], 'INCOMPLETE')
        j['profile'] = 'smoke'
        with self.assertRaises(ValueError): build_record(e, j, n, o)

if __name__ == '__main__': unittest.main()
