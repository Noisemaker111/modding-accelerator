"""Checks the run-record summariser, the perf ledger it reads and record.py perf. No game, no network.

    python bench/test_perf_sessions.py
"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import perf_sessions as P  # noqa: E402
import record as R  # noqa: E402

failures = []


def check(cond, what):
    if not cond:
        failures.append(what)


def optimize_record(verdict="accepted", low=1.4):
    r = P.make("perf-optimize", "diorama", "0.10.1", "fabric", session="s", label="tints", diff=__file__)
    P.step(r, "build", "baseline-launch", 90.0)
    P.step(r, "launch", "baseline-launch", 30.0)
    P.step(r, "measure", "baseline-cold", 70.0)
    P.step(r, "warmup", "baseline-warm", 25.0)
    P.step(r, "gates", "baseline-gate-bakes", 12.0)
    r["speedup"] = {"cold": {"geometricMean": 1.6, "low": low, "high": 1.8, "pairs": 4},
                    "warm": {"geometricMean": 1.5, "low": low, "high": 1.7, "pairs": 4}}
    return P.finish(r, verdict, None if verdict == "accepted" else "cold speedup 1.0x, interval includes 1")


good = optimize_record()
check(P.validate(good) == [], f"a complete record validates: {P.validate(good)}")
check(good["seconds"] == 227.0, "seconds sums the phases")
bad = optimize_record("accepted", low=0.9)
check(any("speedup.cold.low" in p for p in P.validate(bad)), "an accepted run must have its interval above 1")
rejected = optimize_record("rejected", low=0.8)
check(P.validate(rejected) == [], "a rejected run with a reason validates")
no_reason = dict(rejected, rejection=None)
check(any("says why" in p for p in P.validate(no_reason)), "a rejected run must say why")
no_candidate = dict(good, candidate=None)
check(any("candidate" in p for p in P.validate(no_candidate)), "a comparison must name its candidate")
odd_phase = json.loads(json.dumps(good))
odd_phase["phases"]["thinking"] = 3
check(any("unknown phase" in p for p in P.validate(odd_phase)), "phases are a closed set")

observed = P.finish(P.make("perf", "diorama", "", "", session="s"), "observed")
P.step(observed, "measure", "capture", 50.0)
summary = P.summarise([good, rejected, observed], session="s")
check(summary["runs"] == 3, "summary counts runs in the session")
check(summary["verdicts"]["accepted"] == 1 and summary["verdicts"]["rejected"] == 1, "summary counts verdicts")
check(summary["phases"]["build"] == 180.0, "summary sums phases across runs")
check(summary["slowest"][0]["seconds"] == 90.0, "slowest steps come first")
check(summary["rejections"][0]["reason"].startswith("cold speedup"), "rejections keep their reason")
check(P.summarise([good], session="other")["runs"] == 0, "a session filter excludes other sessions")
child = dict(observed, id="child", parent=good["id"])
check(P.summarise([good, child], session="s")["runs"] == 1, "a capture run for a perf-optimize launch counts in its parent only")
check(P.interval({"geometricMean": 0.95, "low": None, "high": None}) == "0.95x (no interval)", "a point estimate without an interval still summarises")
check("Accepted 1, rejected 1" in P.text(summary), "the text summary states the counts")

with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    ledger = tmp / "perf-runs.json"
    ledger.write_text(json.dumps({"note": "", "runs": []}), encoding="utf-8")
    run_dir = tmp / "opt" / "run"
    run_dir.mkdir(parents=True)
    (run_dir / "record.json").write_text(json.dumps(good), encoding="utf-8")
    added, skipped = R.add_perf([run_dir / "record.json"], ledger_path=ledger)
    check(len(added) == 1 and not skipped, "record.py perf keeps a new record")
    added, skipped = R.add_perf([run_dir / "record.json"], ledger_path=ledger)
    check(not added and skipped == [good["id"]], "record.py perf skips a kept id")
    (tmp / "bad.json").write_text(json.dumps(bad), encoding="utf-8")
    try:
        R.add_perf([tmp / "bad.json"], ledger_path=ledger)
        check(False, "record.py perf refuses a malformed record")
    except ValueError:
        pass
    check(len(P.load([tmp / "opt"], ledger=ledger)) == 1, "load reads a folder's record.json files and the ledger once each")

local = {"source": str(R.ROOT / ".runs" / "x"), "steps": [{"name": str(Path.home() / "game.log")}]}
moved = R.portable(local)
check(moved["source"].startswith(".") and moved["steps"][0]["name"].startswith("~"), f"kept records name no local paths: {moved}")
kept = P.load()
check(all(not P.validate(r) for r in kept), "every record in coverage/perf-runs.json validates")

if failures:
    print("\n".join("FAIL " + f for f in failures))
    sys.exit(1)
print("perf session checks passed")
