"""A run proves only its own loader and its own updates (bench/ledger_rule.py).

One NeoForge mod with no mixins (ZombieApocalypseAddon, 1.21.1 -> 1.21.4) used to colour
1.21.1 -> 1.21.4 "Works" for Fabric too. Run: python bench/test_ledger_rule.py
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ledger_rule  # noqa: E402

VERSIONS = ["1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6"]
RUNS = [
    {"mod": "ZombieApocalypseAddon", "loader": "neoforge", "from": "1.21.1", "to": "1.21.4", "status": "load"},
    {"mod": "F3Nope", "loader": "fabric", "from": "1.21.6", "to": "1.21.5", "status": "load"},
]
checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


every = ledger_rule.all_statuses(VERSIONS, set(), RUNS, [])
neo, fabric = every["neoforge"], every["fabric"]
for a, b in [("1.21.1", "1.21.2"), ("1.21.2", "1.21.3"), ("1.21.3", "1.21.4")]:
    check(neo[f"{a}__{b}"]["fwd"]["c"] == "green", f"NeoForge {a} -> {b} works: the run crossed it")
    check("1 NeoForge mod ported across" in neo[f"{a}__{b}"]["fwd"]["why"], "the reason names the loader and how many mods")
    check(fabric[f"{a}__{b}"]["fwd"]["c"] == "red" and fabric[f"{a}__{b}"]["fwd"]["word"] == "Not tried", f"Fabric {a} -> {b} is not tried: a NeoForge run proves nothing for Fabric")
    check(neo[f"{a}__{b}"]["back"]["c"] == "red", f"the backport {b} -> {a} was not run")
check(neo["1.21.4__1.21.5"]["fwd"]["word"] == "Not tried", "a run proves only the updates it crossed")
check(fabric["1.21.5__1.21.6"]["back"]["c"] == "green" and neo["1.21.5__1.21.6"]["back"]["c"] == "red", "the Fabric backport is green for Fabric only")
check(ledger_rule.hop_status(VERSIONS, fabric, "1.21.1", "1.21.4")["c"] == "red", "a hub hop on Fabric takes Fabric's colours")
doing = [{"step": "17", "from": "1.21.4", "to": "1.21.6", "text": "AppleSkin NeoForge 1.21.4 → 1.21.6"}]
check(ledger_rule.step_statuses(VERSIONS, set(), RUNS, doing, "neoforge")["1.21.4__1.21.5"]["fwd"]["word"] == "Being worked on", "a NeoForge ROADMAP step is yellow on NeoForge")
check(ledger_rule.step_statuses(VERSIONS, set(), RUNS, doing, "fabric")["1.21.4__1.21.5"]["fwd"]["word"] == "Not tried", "and not on Fabric")

# A committed "behaviour" row stands on a check in tools/tests that exists, can fail, and
# breaks the mod on purpose to prove it (not on the wording of the row's note).
ROOT = Path(__file__).resolve().parent.parent
ledger = json.loads((ROOT / "coverage" / "ledger.json").read_text(encoding="utf-8"))
for h in ledger["hops"]:
    if h["status"] == "behaviour":
        label = f"{h['mod']} {h['from']} -> {h['to']}"
        named = sorted(set(re.findall(r"tools/tests/\w+\.java", h["detail"])))
        check(named, f"{label} is behaviour only when its note names a check under tools/tests")
        for rel in named:
            path = ROOT / rel
            check(path.is_file(), f"{label}: {rel} exists")
            source = path.read_text(encoding="utf-8")
            check("throw new AssertionError" in source and "on purpose" in source, f"{label}: {rel} can fail and breaks the mod on purpose")
apple = next(h for h in ledger["hops"] if h["mod"] == "AppleSkin" and h["from"] == "1.21.5" and h["to"] == "1.21.6")
check(apple["remarked"]["was"] == "behaviour", "AppleSkin 1.21.5 -> 1.21.6 keeps the record of its re-mark to load, with the reason")
check(apple["status"] == "behaviour" and apple.get("promoted", {}).get("test") == "tools/tests/AppleSkinHudCheck.java"
      and (Path(__file__).resolve().parent.parent / apple["promoted"]["test"]).is_file(),
      "AppleSkin 1.21.5 -> 1.21.6 is behaviour again only through a named test that exists")
print(checks, "ledger rule checks passed")
