"""Validate performance evidence and compare paired, independently restarted runs.

No game data is synthesized or collected by this module. See performance.md.
"""
import argparse
import hashlib
import json
import math
import random
import statistics
import sys
from pathlib import Path

# Unit and preferred direction are fixed so reports cannot invert a regression.
METRICS = {
    'tick_ms': ('ms', 'lower'),
    'frame_ms': ('ms', 'lower'),
    'cpu_ms_per_tick': ('ms/tick', 'lower'),
    'allocation_bytes_per_second': ('bytes/s', 'lower'),
    'heap_used_bytes': ('bytes', 'lower'),
    'rss_bytes': ('bytes', 'lower'),
    'gc_pause_ms': ('ms', 'lower'),
    'blocked_ms': ('ms', 'lower'),
    'network_bytes_per_second': ('bytes/s', 'lower'),
    'disk_bytes_per_second': ('bytes/s', 'lower'),
    'startup_ms': ('ms', 'lower'),
    'reload_ms': ('ms', 'lower'),
    'chunk_generation_ms': ('ms/chunk', 'lower'),
    'chunk_mesh_ms': ('ms/chunk', 'lower'),
    'gpu_frame_ms': ('ms', 'lower'),
    'transfer_units_per_second': ('units/s', 'higher'),
    'cpu_ns_per_transfer': ('ns/unit', 'lower'),
}
CONDITIONS = ('hardware', 'os', 'java', 'jvm_flags', 'minecraft', 'loader',
              'world_sha256', 'background_mods_sha256', 'config_sha256',
              'scenario', 'workload', 'side', 'cache', 'warmup_seconds',
              'measurement_seconds', 'collector')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate(data):
    require(isinstance(data, dict) and data.get('schema') == 1, 'expected schema 1')
    require(isinstance(data.get('subject'), dict), 'subject must identify the mod/pack artifact')
    for key in ('name', 'artifact_sha256'):
        require(isinstance(data['subject'].get(key), str) and data['subject'][key].strip(),
                f'subject.{key} is required')
    conditions = data.get('conditions', {})
    require(isinstance(conditions, dict), 'conditions must be an object')
    for key in CONDITIONS:
        require(key in conditions and conditions[key] is not None and conditions[key] != '',
                f'conditions.{key} is required')
    for key in ('warmup_seconds', 'measurement_seconds'):
        require(number(conditions[key]) and conditions[key] >= 0, f'invalid {key}')
    require(conditions['measurement_seconds'] > 0, 'measurement_seconds must be positive')
    require(conditions['side'] in ('client', 'server', 'integrated'), 'invalid side')
    require(conditions['cache'] in ('cold', 'warm'), 'invalid cache')
    for digest in (data['subject']['artifact_sha256'], *(conditions[k] for k in
                   ('world_sha256', 'background_mods_sha256', 'config_sha256'))):
        require(isinstance(digest, str) and len(digest) == 64 and
                all(c in '0123456789abcdef' for c in digest), 'expected lowercase SHA-256')
    runs = data.get('runs')
    require(isinstance(runs, list) and runs, 'runs must be a nonempty list')
    ids = set()
    pairs = set()
    coverage = None
    for run in runs:
        require(isinstance(run, dict), 'run must be an object')
        for key, seen in (('id', ids), ('pair', pairs)):
            value = run.get(key)
            require(isinstance(value, str) and value.strip() and value not in seen,
                    f'run {key} must be nonempty and unique')
            seen.add(value)
        require(run.get('correctness_passed') is True, 'run must pass workload correctness checks')
        require(isinstance(run.get('evidence'), str) and run['evidence'].strip(), 'run evidence is required')
        metrics = run.get('metrics')
        require(isinstance(metrics, dict) and metrics, 'run metrics must be nonempty')
        require(set(metrics) <= METRICS.keys(), 'unknown metric')
        if coverage is None:
            coverage = set(metrics)
        require(set(metrics) == coverage, 'metric coverage must match across runs')
        for name, series in metrics.items():
            require(isinstance(series, list) and series, f'{name}: expected nonempty samples')
            require(all(number(v) and v >= 0 for v in series), f'{name}: samples must be finite and nonnegative')
    return data


def percentile(values, fraction):
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lo = math.floor(index)
    hi = math.ceil(index)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (index - lo)


def summary(values):
    return {'n': len(values), 'mean': statistics.mean(values),
            'p50': percentile(values, .5), 'p95': percentile(values, .95),
            'p99': percentile(values, .99), 'max': max(values)}


def report(data):
    validate(data)
    covered = set(data['runs'][0]['metrics'])
    return {'schema': 1, 'subject': data['subject'], 'conditions': data['conditions'],
            'unmeasured': sorted(METRICS.keys() - covered),
            'runs': [{'id': r['id'], 'pair': r['pair'], 'evidence': r['evidence'],
                      'metrics': {k: summary(v) for k, v in r['metrics'].items()}}
                     for r in data['runs']]}


def compare(before, after, metric, statistic='mean', threshold_percent=5.0):
    validate(before)
    validate(after)
    require(metric in METRICS, 'unknown metric')
    require(statistic in ('mean', 'p50', 'p95', 'p99', 'max'), 'unknown statistic')
    require(number(threshold_percent) and threshold_percent >= 0, 'invalid threshold')
    # Compare every supplied condition, including extension fields, not just required ones.
    mismatches = sorted(k for k in before['conditions'].keys() | after['conditions'].keys()
                        if before['conditions'].get(k) != after['conditions'].get(k))
    require(not mismatches, 'incomparable conditions: ' + ', '.join(mismatches))
    a = {r['pair']: r for r in before['runs']}
    b = {r['pair']: r for r in after['runs']}
    require(a.keys() == b.keys(), 'paired run IDs must match')
    require(len(a) >= 5, 'at least five independent run pairs are required')
    require(not ({r['id'] for r in a.values()} & {r['id'] for r in b.values()}),
            'baseline and candidate must have distinct run IDs')
    require(all(metric in r['metrics'] for r in list(a.values()) + list(b.values())),
            'selected metric was not measured in every run')
    av = [summary(a[k]['metrics'][metric])[statistic] for k in sorted(a)]
    bv = [summary(b[k]['metrics'][metric])[statistic] for k in sorted(a)]
    deltas = [y - x for x, y in zip(av, bv)]
    baseline = statistics.mean(av)
    candidate = statistics.mean(bv)
    # Resample whole run pairs. Adjacent ticks/frames are not independent trials.
    rng = random.Random(0)
    boot = sorted(statistics.mean(rng.choices(deltas, k=len(deltas))) for _ in range(10000))
    low, high = percentile(boot, .025), percentile(boot, .975)
    direction = 1 if METRICS[metric][1] == 'lower' else -1
    bad_low, bad_high = (low, high) if direction == 1 else (-high, -low)
    margin = baseline * threshold_percent / 100
    verdict = 'inconclusive'
    if baseline == 0:
        verdict = 'zero_baseline'
    elif bad_low > margin:
        verdict = 'regression'
    elif bad_high < -margin:
        verdict = 'improvement'
    elif bad_low >= -margin and bad_high <= margin:
        verdict = 'within_budget'
    return {'schema': 1, 'metric': metric, 'unit': METRICS[metric][0], 'statistic': statistic,
            'preferred': METRICS[metric][1], 'pairs': len(a),
            'baseline': baseline, 'candidate': candidate,
            'delta': candidate - baseline,
            'delta_percent': (candidate - baseline) / baseline * 100 if baseline else None,
            'paired_bootstrap_95_ci_delta': [low, high],
            'threshold_percent': threshold_percent, 'verdict': verdict,
            'warnings': ['Interval is exploratory; not corrected for multiple comparisons.',
                         'Independence, equal useful work and collector accuracy require external validation.']}


def read(path):
    raw = Path(path).read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    show = sub.add_parser('report')
    show.add_argument('capture')
    diff = sub.add_parser('compare')
    diff.add_argument('baseline')
    diff.add_argument('candidate')
    diff.add_argument('--metric', required=True, choices=sorted(METRICS))
    diff.add_argument('--statistic', default='mean', choices=['mean', 'p50', 'p95', 'p99', 'max'])
    diff.add_argument('--threshold-percent', type=float, default=5)
    args = parser.parse_args(argv)
    try:
        if args.command == 'report':
            capture, digest = read(args.capture)
            result = report(capture)
            result['source_sha256'] = digest
        else:
            baseline, ah = read(args.baseline)
            candidate, bh = read(args.candidate)
            result = compare(baseline, candidate, args.metric, args.statistic, args.threshold_percent)
            result['source_sha256'] = {'baseline': ah, 'candidate': bh}
        print(json.dumps(result, indent=2, allow_nan=False))
        return {'regression': 1, 'inconclusive': 3, 'zero_baseline': 3}.get(result.get('verdict'), 0)
    except (ValueError, OSError, TypeError) as error:
        print(f'performance: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
