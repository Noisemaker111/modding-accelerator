"""One headline number per goal, each from behaviour fingerprints (FINGERPRINT.md), in coverage/scoreboard.json.

    python bench/scoreboard.py          rebuild coverage/scoreboard.json from coverage/sweeps/*.json
    python bench/scoreboard.py --print  print it

Each sweep writes its own headline (coverage/sweeps/port.json, convert.json, pack.json, perf.json); this collects them.
A sweep that has not run with fingerprints yet scores 0 of its corpus, never a number from another definition.
"""
import argparse
import datetime
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "coverage" / "scoreboard.json"
SWEEPS = {
    "port": ("bench/port_board.py --fingerprint", "bench/port-cases.json", "cases", "ports whose behaviour fingerprint matches across the jump"),
    "convert": ("bench/convert_sweep.py run", "bench/convert-sweep/corpus.json", "mods", "conversions with the same behaviour fingerprint as the original"),
    "pack": ("bench/pack_sweep.py run", "bench/pack-sweep/corpus.json", "packs", "packs whose fingerprint world opens again unchanged"),
    "perf": ("bench/perf_sweep.py run", "bench/perf-corpus.json", None, "perf diffs the generic fingerprint gate decides as the hand gates did"),
}


def corpus_size(path, key):
    data = json.loads((ROOT / path).read_text(encoding="utf-8"))
    if key:
        return len(data[key])
    return sum(len(m.get("shipped", [])) + len(m.get("wrong", [])) for m in data["mods"])


def build():
    goals = {}
    for goal, (runner, corpus, key, name) in SWEEPS.items():
        size = corpus_size(corpus, key)
        sweep = ROOT / "coverage" / "sweeps" / f"{goal}.json"
        latest = json.loads(sweep.read_text(encoding="utf-8")).get("latest", {}) if sweep.is_file() else {}
        headline = latest.get("headline") if isinstance(latest.get("headline"), dict) else None
        entry = {"name": name, "value": 0, "of": size, "runner": f"python {runner}", "source": f"coverage/sweeps/{goal}.json", "date": latest.get("date")}
        if headline and headline.get("name") == name:
            entry["value"] = int(headline.get("value") or 0)
            entry["of"] = int(headline.get("of") or size)
            entry["ran"] = latest.get("runs", latest.get("packs", latest.get("diffs run")))
        else:
            entry["note"] = "not yet run with behaviour fingerprints"
        goals[goal] = entry
    return {"schema": 1, "about": "One headline per goal, each defined by the behaviour fingerprint (FINGERPRINT.md); rebuilt by python bench/scoreboard.py from coverage/sweeps.",
            "built": datetime.date.today().isoformat(), "goals": goals}


def validate(data):
    """The shape every reader relies on; raises on anything else."""
    assert data.get("schema") == 1, "schema 1"
    goals = data.get("goals")
    assert isinstance(goals, dict) and set(goals) == set(SWEEPS), f"goals are {sorted(SWEEPS)}"
    for goal, entry in goals.items():
        for key in ("name", "value", "of", "runner", "source"):
            assert key in entry, f"{goal} has {key}"
        assert isinstance(entry["value"], int) and isinstance(entry["of"], int) and 0 <= entry["value"] <= entry["of"], f"{goal}: 0 <= value <= of"
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--print", action="store_true")
    args = ap.parse_args()
    if args.print:
        data = json.loads(OUT.read_text(encoding="utf-8"))
    else:
        data = build()
        validate(data)
        OUT.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    for goal, entry in data["goals"].items():
        print(f"{goal:8} {entry['value']:>3} of {entry['of']:<3} {entry['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
