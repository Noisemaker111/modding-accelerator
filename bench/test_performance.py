"""Synthetic evidence tests; these are not measurements of any Minecraft mod."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import performance as P


def capture(prefix='a', value=10):
    conditions = {k: 'fixture' for k in P.CONDITIONS}
    for k in ('world_sha256', 'background_mods_sha256', 'config_sha256'):
        conditions[k] = '0' * 64
    conditions.update(side='server', cache='warm', warmup_seconds=30, measurement_seconds=60)
    return {'schema': 1, 'subject': {'name': prefix, 'artifact_sha256': '0' * 64},
            'conditions': conditions,
            'runs': [{'id': f'{prefix}-{i}', 'pair': str(i), 'correctness_passed': True,
                      'evidence': 'synthetic-test-only', 'metrics': {'tick_ms': [value] * 100}}
                     for i in range(5)]}


class PerformanceTests(unittest.TestCase):
    def test_summaries_and_coverage(self):
        self.assertEqual(P.summary([1, 2, 3, 4])['p50'], 2.5)
        self.assertAlmostEqual(P.summary([1, 2, 3, 4])['p99'], 3.97)
        report = P.report(capture())
        self.assertIn('gpu_frame_ms', report['unmeasured'])
        self.assertNotIn('tick_ms', report['unmeasured'])
        self.assertEqual(report['runs'][0]['metrics']['tick_ms']['mean'], 10)

    def test_verdicts_and_units(self):
        for value, expected in [(20, 'regression'), (5, 'improvement'), (10, 'within_budget')]:
            result = P.compare(capture(), capture('b', value), 'tick_ms')
            self.assertEqual(result['verdict'], expected)
            self.assertEqual(result['unit'], 'ms')
            self.assertEqual(result['pairs'], 5)
        self.assertIsNone(P.compare(capture(value=0), capture('b'), 'tick_ms')['delta_percent'])

    def test_throughput_direction(self):
        a, b = capture(), capture('b', 20)
        for data in (a, b):
            for r in data['runs']:
                r['metrics']['transfer_units_per_second'] = r['metrics'].pop('tick_ms')
        self.assertEqual(P.compare(a, b, 'transfer_units_per_second')['verdict'], 'improvement')

    def test_noise_is_inconclusive_and_repeatable(self):
        b = capture('b')
        for r, value in zip(b['runs'], [1, 5, 10, 20, 30]):
            r['metrics']['tick_ms'] = [value]
        first = P.compare(capture(), b, 'tick_ms')
        self.assertEqual(first['verdict'], 'inconclusive')
        self.assertEqual(first, P.compare(capture(), b, 'tick_ms'))

    def test_reject_each_changed_condition_including_extensions(self):
        for key in (*P.CONDITIONS, 'extension'):
            b = capture('b')
            b['conditions'][key] = 'changed'
            with self.subTest(key=key), self.assertRaises(ValueError):
                P.compare(capture(), b, 'tick_ms')

    def test_missing_null_condition_is_not_equal(self):
        a, b = capture(), capture('b')
        b['conditions']['extension'] = None
        with self.assertRaisesRegex(ValueError, 'incomparable'):
            P.compare(a, b, 'tick_ms')

    def test_bad_evidence(self):
        mutations = [
            lambda x: x.update(schema=2),
            lambda x: x.update(schema=True),
            lambda x: x.update(schema=1.0),
            lambda x: x.update(runs=[]),
            lambda x: x['subject'].update(artifact_sha256='bad'),
            lambda x: x['runs'][0].update(correctness_passed=False),
            lambda x: x['runs'][0].update(evidence=''),
            lambda x: x['runs'][1].update(id='a-0'),
            lambda x: x['runs'][1].update(pair='0'),
            lambda x: x['runs'][0].update(metrics={}),
            lambda x: x['runs'][0]['metrics'].update(made_up=[1]),
        ]
        for bad in ([], [float('nan')], [float('inf')], [-1], [True], ['1']):
            mutations.append(lambda x, bad=bad: x['runs'][0]['metrics'].update(tick_ms=bad))
        for mutation in mutations:
            data = capture()
            mutation(data)
            with self.assertRaises(ValueError):
                P.validate(data)

    def test_pairs_are_unit_of_replication(self):
        a, b = capture(), capture('b')
        a['runs'] = a['runs'][:1]
        b['runs'] = b['runs'][:1]
        with self.assertRaisesRegex(ValueError, 'five independent'):
            P.compare(a, b, 'tick_ms')
        with self.assertRaisesRegex(ValueError, 'distinct run IDs'):
            P.compare(capture(), capture(), 'tick_ms')
        b = capture('b')
        b['runs'][0]['pair'] = 'other'
        with self.assertRaisesRegex(ValueError, 'paired run IDs'):
            P.compare(capture(), b, 'tick_ms')

    def test_cli_exit_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'a.json', Path(directory) / 'b.json'
            a.write_text(json.dumps(capture()))
            b.write_text(json.dumps(capture('b', 20)))
            command = [sys.executable, str(Path(P.__file__)), 'compare', str(a), str(b), '--metric', 'tick_ms']
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(len(json.loads(result.stdout)['source_sha256']['baseline']), 64)
            b.write_text(json.dumps(capture('b', 10)))
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            noisy = capture('b')
            for run, value in zip(noisy['runs'], [1, 5, 10, 20, 30]):
                run['metrics']['tick_ms'] = [value]
            b.write_text(json.dumps(noisy))
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 3)
            b.write_text('{}')
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)


if __name__ == '__main__':
    unittest.main()
