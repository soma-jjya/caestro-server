"""Generate load inputs from expected peak attendance; this is not a capacity guarantee."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path

def plan(request):
    expected = request['peakParticipantsPer10Minutes']
    limit = request['maxVirtualUsers']
    if type(expected) is not int or not 1 <= expected <= 50000:
        raise ValueError('peakParticipantsPer10Minutes must be 1..50000')
    if type(limit) is not int or not 1 <= limit <= 2000:
        raise ValueError('maxVirtualUsers must be 1..2000')
    safety = request.get('safetyFactor', 1.5)
    if type(safety) not in (int, float) or not math.isfinite(safety) or not 1 <= safety <= 3:
        raise ValueError('safetyFactor must be 1..3')
    starts, ends, retention = [datetime.fromisoformat(request[k].replace('Z', '+00:00'))
                               for k in ('startsAt', 'endsAt', 'downloadsUntil')]
    if any(d.tzinfo is None for d in (starts, ends, retention)) or not starts < ends < retention:
        raise ValueError('use ordered timestamps with explicit UTC offsets')
    rate = math.ceil(expected / 10 * safety)
    per_ip = request['peakNewGuestsPerIpPerMinute']
    if type(per_ip) is not int or not 1 <= per_ip <= 10000:
        raise ValueError('peakNewGuestsPerIpPerMinute must be 1..10000; include busiest Wi-Fi/carrier NAT')
    ip_limit = request.get('guestIpLimit', 30)
    window = request.get('guestIpWindowSeconds', 60)
    if type(ip_limit) is not int or not 1 <= ip_limit <= 10000 or type(window) is not int or not 1 <= window <= 3600:
        raise ValueError('invalid guest IP quota')
    # Conservative model: each visitor creates one fill and uploads four photos;
    # 20s think time + 40s I/O headroom. Measure with representative images.
    required_vus = max(2, rate)
    if required_vus > limit:
        raise ValueError('requested traffic exceeds the test-runner VU ceiling; do not silently reduce the test')
    fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return {
        'requestSha256': fingerprint, 'status': 'NEEDS_MEASUREMENT',
        'load': {'PROFILE': 'capacity', 'ARRIVALS_PER_MINUTE': rate, 'MAX_VUS': required_vus},
        'suggestedGuestLimitPerMinute': min(10000, math.ceil(rate * 1.2)),
        'guestAdmission': {'ipLimit': ip_limit, 'windowSeconds': window,
            'peakNewGuestsPerIpPerMinute': per_ip,
            'expectedGuestsPerIpWindowWithMargin': math.ceil(per_ip * safety * window / 60),
            'status': 'POLICY_REVIEW_REQUIRED' if math.ceil(per_ip * safety * window / 60) > ip_limit else 'NEEDS_SHARED_IP_MEASUREMENT',
            'minimumDistinctRunnerEgressIps': math.ceil(rate / (ip_limit * 60 / window)),
            'automaticallyChangeLimit': False},
        'assumptions': {'fillsPerVisitor': 1, 'uploadsPerFill': 4, 'thinkSeconds': 20, 'ioHeadroomSeconds': 40},
        'schedule': {'startsAt': starts.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z'),
                     'endsAt': ends.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z'),
                     'downloadsUntil': retention.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')},
        'requiresBeforePublicRouting': ['matching-image-digest', 'matching-resource-profile',
            'readiness', 'load-thresholds-pass', 'no-dropped-iterations', 'shared-ip-policy-and-recovery',
            't3-credit-and-memory-evidence', 'four-slot-browser-download'],
    }

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('request', type=Path)
    arguments = parser.parse_args()
    print(json.dumps(plan(json.loads(arguments.request.read_text(encoding='utf-8'))), ensure_ascii=False, indent=2))
