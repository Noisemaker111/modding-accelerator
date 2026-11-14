#!/usr/bin/env python3
"""Check the budget model against small known inputs and invalid data."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('budget', Path(__file__).with_name('budget.py'))
budget = importlib.util.module_from_spec(spec)
spec.loader.exec_module(budget)


class BudgetTest(unittest.TestCase):
    def test_paired_baseline_budget_and_remaining_speedup(self):
        study = {'samples': [
            {'tile': 'A', 'fast': False, 'baselineMs': 100, 'candidateMs': 50},
            {'tile': 'A', 'fast': False, 'baselineMs': 120, 'candidateMs': 60},
        ]}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            budget.describe_study(study, 10)
        self.assertIn('baseline=110.000 ms, candidate=55.000 ms, baseline target=11.000 ms, candidate-to-target=5.000x', out.getvalue())

    def test_original_bake_samples_and_fraction_ceiling(self):
        study = {'bake': {'samples': [{'tile': 'B', 'fast': False, 'ms': 200, 'knownPixels': 1, 'pixels': 2}]}}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            budget.describe_study(study, 100)
        self.assertIn('known=50.00%, median=200.000 ms, target=2.000 ms', out.getvalue())
        self.assertAlmostEqual(budget.ceiling(.75), 4)
        self.assertEqual(budget.ceiling(1), float('inf'))
        for value in (-1, 1.1, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                budget.ceiling(value)

    def test_invalid_study(self):
        for study in ({}, {'samples': []}, {'samples': [{'tile': 'A', 'fast': False, 'baselineMs': 1}]},
                      {'bake': {'samples': [{'tile': 'B', 'fast': False, 'ms': 1, 'knownPixels': 2, 'pixels': 1}]}}):
            with self.subTest(study=study), self.assertRaises(ValueError):
                budget.study_groups(study)

    def test_cli_rejects_nonfinite_target_and_prints_caveat(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / 'capture.json'
            capture.write_text(json.dumps({'seconds': 1, 'executionSamples': 10, 'hotMethods': []}))
            cli = [sys.executable, str(Path(__file__).with_name('budget.py')), str(capture)]
            for target in ('nan', 'inf', '1'):
                run = subprocess.run(cli + ['--target', target], capture_output=True, text=True)
                self.assertNotEqual(run.returncode, 0)
                self.assertIn('--target', run.stderr)
            run = subprocess.run(cli + ['--fraction', '0.75'], capture_output=True, text=True, check=True)
            self.assertIn('ceiling 4.000x', run.stdout)
            self.assertIn('not an FPS or wall-time ceiling', run.stdout)


if __name__ == '__main__':
    unittest.main()
