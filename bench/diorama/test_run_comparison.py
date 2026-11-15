"""Checks the comparison driver's interval and the record it writes. No game.

    python bench/diorama/test_run_comparison.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import perf_sessions as P  # noqa: E402
import run_comparison as C  # noqa: E402

failures = []
same = C.speedup([2.0] * 8)
if abs(same["geometricMean"] - 2) > 1e-9 or same["low"] < 1.99:
    failures.append("identical ratios give that speedup with no spread")
mixed = C.speedup([0.8, 1.3, 0.9, 1.2, 1.0, 1.1])
if not (mixed["low"] < 1 < mixed["high"]):
    failures.append("noisy ratios give an interval spanning 1")
if C.speedup([]) is not None:
    failures.append("no samples give no interval")
r = P.make("comparison", "diorama", "0.10.1", "fabric", label="air-runs", diff=__file__)
P.step(r, "measure", "compare.java", 40.0)
r["speedup"] = {"warm": same, "cold": None}
P.finish(r, "observed")
if P.validate(r):
    failures.append(f"an observed comparison record validates: {P.validate(r)}")
P.finish(r, "rejected", None)
if not any("says why" in p for p in P.validate(r)):
    failures.append("a rejected comparison must say why")
if failures:
    print("\n".join("FAIL " + f for f in failures))
    sys.exit(1)
print("comparison driver checks passed")
