"""Kept perf-optimize runs agree with their own evidence, and every shipped sabotage has a kept rejection.

A verifier checked both by hand in four rounds on 2026-11-15: that an accepted run passed every gate and was faster,
and that each deliberately wrong candidate (bench/*/sabotage-*.diff) was rejected by a gate. No game; under a second.

    python bench/test_perf_verdicts.py
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# A sabotage diff whose rejection was seen but not kept. Each entry must name why; the test fails once a kept run exists,
# so the entry cannot outlive the gap. Record the run with python bench/record.py perf, then delete the entry.
UNRECORDED = {
    "bench/pipez/sabotage-stale-cache.diff": "#76 says the rewire gate rejected it in game; that run was not kept in coverage/perf-runs.json",
}

failures = []
runs = json.loads((ROOT / "coverage/perf-runs.json").read_text(encoding="utf-8"))["runs"]

for run in runs:
    gates = run.get("gates") or []
    speedup = run.get("speedup") or {}
    if run.get("verdict") == "accepted":
        if not gates or not all(g.get("passed") is True for g in gates):
            failures.append(f"{run['id']}: accepted with a gate that did not pass")
        for phase in ("cold", "warm"):
            low = (speedup.get(phase) or {}).get("low")
            if not isinstance(low, (int, float)) or low <= 1:
                failures.append(f"{run['id']}: accepted with a {phase} speedup interval that reaches 1 ({low})")
    if run.get("verdict") == "rejected" and gates and all(g.get("passed") is True for g in gates):
        low = min(((speedup.get(p) or {}).get("low") or 0) for p in ("cold", "warm"))
        if low > 1:
            failures.append(f"{run['id']}: rejected, yet every gate passed and both speedups are above 1")

kept = {}
for run in runs:
    sha = (run.get("candidate") or {}).get("diffSha256")
    if sha:
        kept.setdefault(sha, []).append(run)

diffs = sorted(ROOT.glob("bench/*/sabotage-*.diff"))
if not diffs:
    failures.append("no bench/*/sabotage-*.diff found")
for diff in diffs:
    name = diff.relative_to(ROOT).as_posix()
    sha = hashlib.sha256(diff.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
    gate_names = set()
    # Beside the diff or below it: Diorama's experiments are in bench/diorama/play/.
    for experiment in diff.parent.rglob("experiment*.json"):
        gate_names |= {g["name"] for g in json.loads(experiment.read_text(encoding="utf-8")).get("gates", [])}
    rejected = [r for r in kept.get(sha, []) if r.get("verdict") == "rejected"
                and any(g.get("passed") is False and g.get("name") in gate_names for g in r.get("gates") or [])]
    if rejected and name in UNRECORDED:
        failures.append(f"{name}: has a kept rejection now; delete its UNRECORDED entry")
    elif not rejected and name not in UNRECORDED:
        failures.append(f"{name} (sha256 {sha[:12]}): no kept run rejects it by one of its experiment's gates")
    elif not rejected:
        print(f"UNRECORDED {name}: {UNRECORDED[name]}")

if failures:
    print("\n".join(failures))
    sys.exit(1)
print(f"{len(runs)} kept runs agree with their gates and speedups; {len(diffs) - len(UNRECORDED)} of {len(diffs)} sabotage diffs have a kept rejection")
