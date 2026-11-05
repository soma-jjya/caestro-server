"""Save immutable, measured experiment evidence; never treat a missing result as a pass."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'load-test' / 'results'
REQUIRED_CONFIG = {'instanceType', 'instanceCount', 'amiId', 'imageDigest', 'cpuCreditMode',
    'containerMemoryMb', 'dbInstanceClass', 'dbEngineVersion', 'dbMultiAz', 'dbStorageType',
    'dbStorageGb', 'dbPoolSize', 'redisNodeType', 'redisEngineVersion', 'redisNodeCount',
    'redisMultiAz', 'guestIpLimit', 'guestIpWindowSeconds', 'guestRuntimeLimitPerMinute'}
REQUIRED_OBSERVATIONS = {'appCpuPeakPercent', 'appMemoryPeakPercent', 'cpuCreditBalanceStartMin',
    'cpuCreditBalanceEndMin', 'cpuSurplusCreditBalanceEndMax', 'cpuSurplusCreditsChargedSum',
    'dbCpuPeakPercent', 'dbConnectionsPeak', 'dbReadLatencyMaxSeconds', 'dbWriteLatencyMaxSeconds',
    'redisCpuPeakPercent', 'redisMemoryPeakPercent', 'redisEvictionsSum'}
REQUIRED_THRESHOLDS = {'b2b_journey_success', 'b2b_unfinished_journeys', 'http_req_failed',
    'http_req_duration{stage:upload}', 'http_req_duration{stage:read}', 'dropped_iterations',
    'b2b_429{scope:guest-ip}', 'b2b_429{scope:guest-runtime}', 'b2b_429{scope:other}'}

def build_record(experiment, journey, nat, observations, previous=None):
    config = experiment['configuration']
    if set(config) != REQUIRED_CONFIG or any(v is None for v in config.values()):
        raise ValueError('Provide every observed configuration field; placeholders are not a baseline')
    if experiment.get('environment') != 'test':
        raise ValueError('Evidence must come from the isolated test environment')
    changed = []
    if previous:
        if previous['experiment']['workload'] != experiment['workload']:
            raise ValueError('Keep workload, NAT assumption, duration and photo constant when comparing capacity')
        changed = sorted(k for k in REQUIRED_CONFIG if config[k] != previous['experiment']['configuration'][k])
        if len(changed) != 1:
            raise ValueError('Change exactly one configuration field per experiment')
    elif config['instanceType'] != 't3.micro' or config['instanceCount'] != 2:
        raise ValueError('First experiment must use two t3.micro instances')
    if journey.get('profile') != 'capacity' or nat.get('profile') != 'shared-ip':
        raise ValueError('Capacity and shared-IP evidence are both required; smoke is not capacity evidence')
    if any(journey.get(k) != nat.get(k) for k in ('origin', 'slug')):
        raise ValueError('Evidence targets differ')
    workload = experiment['workload']
    for k in ('arrivalsPerMinute', 'durationMinutes', 'photoBytes'):
        if journey.get(k) != workload[k]:
            raise ValueError('Measured workload differs from experiment: ' + k)
    metrics = journey['summary']['metrics']
    checks = [v['ok'] for m in metrics.values() for v in m.get('thresholds', {}).values()]
    nat_checks = [v['ok'] for m in nat['summary']['metrics'].values() for v in m.get('thresholds', {}).values()]
    count = lambda name: metrics.get(name, {}).get('values', {}).get('count', 0)
    ip429 = count('b2b_429{scope:guest-ip}')
    total429 = count('b2b_429{scope:guest-runtime}')
    missing = sorted(k for k in REQUIRED_OBSERVATIONS if type(observations.get(k)) not in (int, float)
                     or not math.isfinite(observations[k]) or observations[k] < 0)
    missing += sorted(k for k in REQUIRED_THRESHOLDS if not metrics.get(k, {}).get('thresholds'))
    if not nat['summary']['metrics'].get('b2b_nat_contract', {}).get('thresholds'):
        missing.append('b2b_nat_contract')
    missing += [k for k in ('t3SustainedLoadReviewed', 'representativePhotoReviewed', 'baselineParityReviewed')
                if observations.get(k) is not True]
    quota = config['guestIpLimit'] * 60 / config['guestIpWindowSeconds']
    policy_blocked = workload['peakNewGuestsPerIpPerMinuteWithMargin'] > quota
    if not checks or not nat_checks or count('iterations') == 0:
        verdict = 'INCOMPLETE'
    elif ip429 or total429 or policy_blocked:
        verdict = 'BLOCKED_GUEST_POLICY'
    elif not all(checks + nat_checks):
        verdict = 'FAILED'
    elif missing:
        verdict = 'INCOMPLETE'
    else:
        verdict = 'CANDIDATE_FOR_REVIEW' # Never auto-promote or touch AWS.
    return {'recordedAt': datetime.now(timezone.utc).isoformat(), 'verdict': verdict,
            'changedFields': changed, 'missingEvidence': missing, 'experiment': experiment,
            'guest429': {'ip': ip429, 'runtime': total429, 'festivalNatPolicyBlocked': policy_blocked},
            'observations': observations, 'journey': journey, 'sharedIp': nat}

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--id', required=True)
    parser.add_argument('--experiment', required=True)
    parser.add_argument('--journey', required=True)
    parser.add_argument('--nat', required=True)
    parser.add_argument('--observations', required=True)
    parser.add_argument('--previous')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,79}', args.id):
        parser.error('id must be a simple filename without paths')
    record = build_record(read(args.experiment), read(args.journey), read(args.nat), read(args.observations),
                          read(args.previous) if args.previous else None)
    record['evidenceSha256'] = {name: hashlib.sha256(Path(path).read_bytes()).hexdigest() for name, path in
        [('experiment', args.experiment), ('journey', args.journey), ('nat', args.nat), ('observations', args.observations)]}
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / (args.id + '.json')).open('x', encoding='utf-8') as out:
        json.dump(record, out, ensure_ascii=False, indent=2, allow_nan=False)
    print(record['verdict'])
