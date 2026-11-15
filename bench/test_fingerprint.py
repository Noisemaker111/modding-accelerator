"""The behaviour fingerprint's comparisons on recorded fingerprints (bench/fingerprint-fixtures), offline: no game.
Run: python bench/test_fingerprint.py

The fixtures are trimmed from real runs of one mod (FINGERPRINT.md); the cases a local run did not record carry a
labelled synthetic edit in their "about". The in-game proofs are bench/perf_sweep.py's jobs for the cloud runner.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIX = ROOT / "bench" / "fingerprint-fixtures"
MODACCEL = [str(ROOT / ("modaccel.cmd" if os.name == "nt" else "modaccel"))]
checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def diff(*args):
    """modaccel fingerprint-diff with --json: (exit code, its result)."""
    r = subprocess.run(MODACCEL + ["fingerprint-diff", *map(str, args), "--json"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    line = next((l for l in reversed(r.stdout.splitlines()) if l.startswith("{")), None)
    if line is None:
        raise AssertionError(f"fingerprint-diff printed no result: {r.stdout[-800:]} {r.stderr[-800:]}")
    return r.returncode, json.loads(line)["result"]


def files(*names):
    return ",".join(str(FIX / f"{n}.json") for n in names)


for name in sorted(p.stem for p in FIX.glob("*.json")):
    data = json.loads((FIX / f"{name}.json").read_text(encoding="utf-8"))
    check(data.get("about"), f"{name} says where it came from")

# A record compared with itself: the same, every leaf counted.
code, result = diff(FIX / "world-baseline-0.json", FIX / "world-baseline-0.json")
check(code == 0 and result["same"] and result["compared"] > 100, f"a record is the same as itself: {result.get('compared')}")

# Same version, one leaf changed: different, and the diff names the leaf.
code, result = diff(FIX / "world-baseline-0.json", FIX / "world-baseline-1.json")
check(code == 1 and not result["same"] and any("inventories" in k for k in result["changed"]), f"a changed inventory is named: {result['sections']}")

# perf-optimize's gate: what the two baselines disagree on is left out; a candidate that differs only there passes.
code, result = diff("--baseline", files("world-baseline-0", "world-baseline-1"), "--candidate", files("world-candidate-same-0", "world-candidate-same-1"))
check(code == 0 and result["passed"] and result["unstable"] >= 1, f"the gate leaves out unstable leaves and passes: {result.get('failures')} {result.get('unstable')}")

# The old Storage Network failure mode: lookups that stopped renewing their chunks' tickets. The gate fails it on tickets.
code, result = diff("--baseline", files("world-baseline-0", "world-baseline-1"), "--candidate", files("world-candidate-lost-tickets-0", "world-candidate-lost-tickets-1"))
check(code == 1 and not result["passed"], f"a candidate that loses the lookups' tickets fails: {result.get('failures')}")
check(any(s.startswith("ticketsDuringRun") or s.endswith("tickets") for s in result["differingSections"]), f"and the gate says it was the tickets: {result['differingSections']}")

# A launch whose world ticked before it froze starts from another state: the gate decides nothing and says why.
late = ROOT / ".runs" / "fingerprint-test"
late.mkdir(parents=True, exist_ok=True)
record = json.loads((FIX / "world-candidate-same-0.json").read_text(encoding="utf-8"))
record["checkpoints"][0]["levels"]["minecraft:overworld"]["gameTime"] += 11
(late / "late-freeze.json").write_text(json.dumps(record), encoding="utf-8")
code, result = diff("--baseline", files("world-baseline-0", "world-baseline-1"), "--candidate", str(late / "late-freeze.json") + "," + files("world-candidate-same-1"))
check(code == 1 and any("did not start from the same state" in f for f in result["failures"]), f"a late freeze fails the gate: {result.get('failures')}")

# A mod that orders equal targets by identity puts items in other barrels in every launch. The baselines disagree on one
# barrel, so every barrel is left to the conserved totals: a candidate whose items land in another barrel passes, while a
# cable's changed data (a type the baselines agree on) and a lost item total still fail.
def variant(name, edit):
    record = json.loads((FIX / "world-candidate-same-1.json").read_text(encoding="utf-8"))
    edit(record["checkpoints"][-1]["levels"]["minecraft:overworld"])
    (late / f"{name}.json").write_text(json.dumps(record), encoding="utf-8")
    return files("world-candidate-same-0") + "," + str(late / f"{name}.json")


def other_barrel(level):
    level["inventories"]["5,-60,-3"] = "0:minecraft:gold_ingot*12"
    level["blockEntities"]["2,-60,-3"] = level["blockEntities"]["2,-60,-3"].replace("count:64", "count:12")


def cable(level):
    level["blockEntities"]["1,-60,-2"] = level["blockEntities"]["1,-60,-2"].replace("needsRedstone:0b", "needsRedstone:1b")


def lost_total(level):
    first = next(iter(level["items"]))
    level["items"][first] -= 1


BASE = files("world-baseline-0", "world-baseline-1")
code, result = diff("--baseline", BASE, "--candidate", variant("other-barrel", other_barrel))
check(code == 0 and any("#minecraft:barrel" in f for f in result["unstableFamilies"]), f"items in another barrel pass when barrels are unstable: {result.get('failures')}")
code, result = diff("--baseline", BASE, "--candidate", variant("cable", cable))
check(code == 1 and "checkpoints/blockEntities" in result["differingSections"], f"a cable's changed data still fails: {result.get('failures')}")
code, result = diff("--baseline", BASE, "--candidate", variant("lost-total", lost_total))
check(code == 1 and "checkpoints/items" in result["differingSections"], f"a lost item total still fails: {result.get('failures')}")

# A port: across versions only what the mod owns counts, after the game's renames; saved-data formats do not.
code, result = diff(FIX / "content-1.21.1.json", FIX / "content-1.21.4-same.json")
check(code == 0 and result["same"] and result["normalisation"].startswith("across versions"), f"a port with only format changes keeps the mod's behaviour: {result.get('sections')}")
check(result["compared"] > 10, f"and the mod's own leaves were compared: {result['compared']}")
code, result = diff(FIX / "content-1.21.1.json", FIX / "content-1.21.4-lost-block.json")
check(code == 1 and any("storagenetwork:kabel" in k for k in result["onlyInA"]), f"a port that loses one of the mod's blocks fails and names it: {result['onlyInA']}")

# Naming the mod's namespaces gives the same verdict as reading them from the records.
code, result = diff(FIX / "content-1.21.1.json", FIX / "content-1.21.4-same.json", "--namespaces", "storagenetwork")
check(result["same"], "--namespaces narrows the mod's own leaves without changing the verdict")

# Paths with spaces reach the command whole.
spaced = ROOT / ".runs" / "fingerprint test with spaces"
spaced.mkdir(parents=True, exist_ok=True)
copy = spaced / "a record.json"
copy.write_text((FIX / "content-1.21.1.json").read_text(encoding="utf-8"), encoding="utf-8")
code, result = diff(copy, FIX / "content-1.21.1.json")
check(code == 0 and result["same"], "a fingerprint in a folder with spaces is read")

print(f"{checks} fingerprint checks passed")
