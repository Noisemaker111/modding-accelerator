#!/usr/bin/env python3
"""Describe measured sample shares and bake budgets without inferring wall time from samples."""
import argparse
import json
import math
import statistics
from pathlib import Path


SELECTED = ('dev.diorama.bake.TileSpots.passedWhenSaved(', 'dev.diorama.bake.TileBaker.trace(', 'dev.diorama.bake.VoxelReader.solid(')


def finite_number(value, name, *, minimum=0, strict=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f'{name} must be a finite number')
    if value < minimum or (strict and value == minimum):
        raise ValueError(f'{name} must be {">" if strict else ">="} {minimum}')
    return value


def ceiling(fraction):
    finite_number(fraction, 'fraction')
    if fraction > 1:
        raise ValueError('fraction must be between 0 and 1')
    return math.inf if fraction == 1 else 1 / (1 - fraction)


def study_groups(study):
    if not isinstance(study, dict):
        raise ValueError('study must be a JSON object')
    data = study.get('bake', study)
    if not isinstance(data, dict) or not isinstance(data.get('samples'), list) or not data['samples']:
        raise ValueError('study must have a nonempty samples array (at top level or under bake)')
    groups = {}
    kind = None
    for row in data['samples']:
        if not isinstance(row, dict) or not isinstance(row.get('tile'), str) or not isinstance(row.get('fast'), bool):
            raise ValueError('each sample requires string tile and boolean fast')
        row_kind = 'paired' if 'baselineMs' in row or 'candidateMs' in row else 'bake'
        if kind and kind != row_kind:
            raise ValueError('study mixes paired and bake samples')
        kind = row_kind
        keys = ('baselineMs', 'candidateMs') if kind == 'paired' else ('ms',)
        for key in keys:
            finite_number(row.get(key), key, strict=True)
        if kind == 'bake':
            known = finite_number(row.get('knownPixels'), 'knownPixels')
            pixels = finite_number(row.get('pixels'), 'pixels', strict=True)
            if known > pixels:
                raise ValueError('knownPixels cannot exceed pixels')
        groups.setdefault((row['tile'], row['fast']), []).append(row)
    return kind, groups


def describe_study(study, target):
    kind, groups = study_groups(study)
    print('Within-JVM elapsed bake medians; not cold/start-to-finished wall time:')
    for (tile, fast), rows in sorted(groups.items()):
        quality = 'Fast' if fast else 'Fancy'
        if kind == 'paired':
            baseline = statistics.median(row['baselineMs'] for row in rows)
            candidate = statistics.median(row['candidateMs'] for row in rows)
            budget = baseline / target
            print(f'  {tile} {quality}: n={len(rows)}, baseline={baseline:.3f} ms, candidate={candidate:.3f} ms, baseline target={budget:.3f} ms, candidate-to-target={candidate/budget:.3f}x')
        else:
            med = statistics.median(row['ms'] for row in rows)
            coverage = statistics.median(row['knownPixels'] / row['pixels'] for row in rows)
            print(f'  {tile} {quality}: n={len(rows)}, known={coverage*100:.2f}%, median={med:.3f} ms, target={med/target:.3f} ms')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture', type=Path, help='modaccel capture report.json')
    p.add_argument('--study', type=Path, help='study.json or paired final-data.json with bake samples')
    p.add_argument('--target', type=float, default=100.0)
    p.add_argument('--fraction', type=float, help='hypothetical optimizable serial fraction from 0 to 1')
    a = p.parse_args()
    try:
        finite_number(a.target, '--target', minimum=1, strict=True)
        if a.fraction is not None:
            fraction_ceiling = ceiling(a.fraction)
        c = json.loads(a.capture.read_text())
        n = c['executionSamples']
        if isinstance(n, bool) or not isinstance(n, int) or n <= 0:
            raise ValueError('capture executionSamples must be a positive integer')
        seconds = finite_number(c['seconds'], 'capture seconds', strict=True)
        methods = c['hotMethods']
        if not isinstance(methods, list):
            raise ValueError('capture hotMethods must be an array')
        counts = []
        for name in SELECTED:
            matches = [m for m in methods if isinstance(m, dict) and isinstance(m.get('name'), str) and m['name'].startswith(name)]
            count = sum(m['samples'] for m in matches)
            if not isinstance(count, int) or count < 0:
                raise ValueError('method sample counts must be nonnegative integers')
            counts.append(count)
        if sum(counts) > n:
            raise ValueError('selected method samples exceed executionSamples')
        study = json.loads(a.study.read_text()) if a.study else None
        if study is not None:
            study_groups(study)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as e:
        p.error(str(e))
    print(f'Capture: {seconds:.3f} s; {n} Java execution samples; target {a.target:g}x')
    print(f'Budget: {100/a.target:.3f}% of baseline work; reduction required {100*(1-1/a.target):.3f}%')
    print('Self samples (statistical; not wall time):')
    for name, count in zip(SELECTED, counts):
        print(f'  {name[:-1]}: {count}/{n} = {count/n*100:.2f}%')
    total = sum(counts)
    print(f'  selected total: {total}/{n} = {total/n*100:.2f}%')
    print(f'  illustrative ceiling: {ceiling(total/n):.3f}x')
    print('  Assumes samples represent fixed serial CPU work and selected work vanishes; not an FPS or wall-time ceiling.')
    if a.fraction is not None:
        print(f'Hypothetical serial fraction {a.fraction:.3f}: ceiling {fraction_ceiling:.3f}x if eliminated and all other work is unchanged.')
    if study is not None:
        describe_study(study, a.target)


if __name__ == '__main__':
    main()
