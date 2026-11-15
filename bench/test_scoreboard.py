"""coverage/scoreboard.json parses and has one fingerprint headline per goal; the sweeps' headlines and the perf
sweep's verdicts work offline. Run: python bench/test_scoreboard.py"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scoreboard as S  # noqa: E402
import perf_sweep as F  # noqa: E402
import pack_sweep as P  # noqa: E402
import convert_sweep as C  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


# The tracked file parses and has the shape readers rely on.
data = json.loads(S.OUT.read_text(encoding="utf-8"))
check(S.validate(data), "coverage/scoreboard.json is valid")
check(set(data["goals"]) == {"port", "convert", "pack", "perf"}, f"one headline per goal: {sorted(data['goals'])}")

# Rebuilding gives the same shape; a sweep never run with fingerprints scores 0 of its corpus.
built = S.build()
check(S.validate(built), "a rebuilt scoreboard is valid")
for goal, entry in built["goals"].items():
    check(entry["of"] > 0, f"{goal} counts its corpus")

# A broken file fails validation.
broken = json.loads(json.dumps(built))
broken["goals"]["port"]["value"] = broken["goals"]["port"]["of"] + 1
try:
    S.validate(broken)
    check(False, "a value above its corpus is refused")
except AssertionError:
    check(True, "")

# The perf sweep: a shipped diff the gate passes and a wrong one it fails are both decided right.
check(F.decide("pass", {"passed": True}) is True and F.decide("fail", {"passed": False}) is True, "expected verdicts are right")
check(F.decide("pass", {"passed": False}) is False and F.decide("fail", {"passed": True}) is False, "the others are wrong")
check(F.decide("pass", None) is None, "no gate decides nothing")
check(F.gate_verdict({"gates": [{"compare": "exact"}, {"compare": "fingerprint", "passed": True}]})["passed"] is True, "the fingerprint gate is found")
check(not F.records_coverage(type("A", (), {"only": "cyclic", "record": False})()), "a partial perf run does not write the coverage file")

# The convert sweep: a fingerprint diff with nothing compared is no pass.
check(C.verdict_from_diff({"same": True, "compared": 0})["equal"] is False, "nothing compared is not the same behaviour")
check(C.verdict_from_diff({"same": True, "compared": 12})["equal"] is True, "the same leaves pass")
check(C.verdict_from_diff({"same": False, "compared": 12})["equal"] is False, "different leaves fail")

# The pack sweep: no fingerprint answer is not taken.
check(P.fingerprint_record(None) == {"taken": False, "same": None, "error": None}, "no answer, no fingerprint")

print(f"{checks} scoreboard checks passed")
