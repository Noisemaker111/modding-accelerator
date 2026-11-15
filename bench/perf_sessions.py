"""Summarise optimization-session run records: where the time went and what was decided.

    python bench/perf_sessions.py [--session NAME] [--local] [--json] [PATH ...]

Every `modaccel perf` capture, every `modaccel perf-optimize` decision and every mod's own comparison
script writes one run record (record.json in its output, a copy in .runs/perf-records/). Kept runs are
in coverage/perf-runs.json (`python bench/record.py perf RECORD`). This reads the ledger, plus
.runs/perf-records with --local, plus any record files or folders named, and prints:

- the wall time spent per phase (setup, build, launch, warmup, measure, gates, other), in all and per kind;
- accepted, rejected, observed and failed runs, with each rejection's reason;
- the slowest steps, which are where tooling time goes first.

The record shape is checked by `validate`, which mirrors tools/cli/PerfRecord.java; a comparison script
builds its record with `make` and `finish` so all three writers agree.
"""
import argparse
import datetime
import hashlib
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LEDGER = ROOT / "coverage" / "perf-runs.json"
LOCAL = ROOT / ".runs" / "perf-records"
KINDS = ("perf", "perf-optimize", "comparison")
PHASES = ("setup", "build", "launch", "warmup", "measure", "gates", "other")
VERDICTS = ("accepted", "rejected", "observed", "error")


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def make(kind, mod_id, version, loader, session="", project="", label=None, diff=None):
    """A new record for a run that is about to start. diff is the candidate's diff file, if any."""
    candidate = None
    if label is not None or diff is not None:
        sha = hashlib.sha256(Path(diff).read_bytes()).hexdigest() if diff else ""
        candidate = {"label": label or (Path(diff).stem if diff else ""), "diffSha256": sha, "changedFiles": []}
    return {
        "schema": 1, "id": now().replace(":", "-") + "-" + uuid.uuid4().hex[:8], "kind": kind, "session": session,
        "started": now(), "mod": {"id": mod_id, "version": version, "loader": loader, "project": project},
        "phases": {p: 0.0 for p in PHASES}, "steps": [], "machine": {"load": []}, "candidate": candidate,
        "gates": [], "speedup": None, "verdict": "error", "rejection": None,
    }


def step(record, phase, name, seconds):
    phase = phase if phase in PHASES else "other"
    record["phases"][phase] += seconds
    record["steps"].append({"phase": phase, "name": name, "seconds": seconds})


def finish(record, verdict, rejection=None):
    record["finished"] = now()
    record["verdict"] = verdict
    record["rejection"] = rejection
    record["seconds"] = sum(record["phases"].values())
    return record


def validate(r):
    """Problems with one record; empty when it has every field in the expected shape."""
    problems = []
    if r.get("schema") != 1:
        problems.append("schema must be 1")
    if not r.get("id"):
        problems.append("id is required")
    if r.get("kind") not in KINDS:
        problems.append(f"kind must be one of {KINDS}")
    if r.get("verdict") not in VERDICTS:
        problems.append(f"verdict must be one of {VERDICTS}")
    if not r.get("started"):
        problems.append("started is required")
    mod = r.get("mod")
    if not isinstance(mod, dict) or not all(k in mod for k in ("id", "version", "loader")):
        problems.append("mod needs id, version and loader")
    phases = r.get("phases") if isinstance(r.get("phases"), dict) else {}
    for p in PHASES:
        if not isinstance(phases.get(p), (int, float)):
            problems.append(f"phases.{p} must be seconds")
    problems += [f"unknown phase {k}" for k in phases if k not in PHASES]
    if not isinstance(r.get("machine"), dict):
        problems.append("machine is required")
    if not isinstance(r.get("gates"), list):
        problems.append("gates must be a list")
    if r.get("kind") in ("perf-optimize", "comparison"):
        c = r.get("candidate")
        if not isinstance(c, dict) or not c.get("label") or not c.get("diffSha256"):
            problems.append("a comparison names its candidate: label and diffSha256")
    if r.get("verdict") == "accepted":
        speed = r.get("speedup") or {}
        for phase in ("cold", "warm"):
            low = (speed.get(phase) or {}).get("low")
            if not isinstance(low, (int, float)) or low <= 1:
                problems.append(f"an accepted run needs speedup.{phase}.low above 1")
    if r.get("verdict") == "rejected" and not r.get("rejection"):
        problems.append("a rejected run says why")
    return problems


def read(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict) and isinstance(data.get("runs"), list):
        return data["runs"]
    return [data]


def load(paths=(), local=False, ledger=LEDGER):
    """Records from the ledger, .runs/perf-records (local) and the named files or folders, each once."""
    found, seen = [], set()
    sources = [Path(ledger)] if Path(ledger).is_file() else []
    if local and LOCAL.is_dir():
        sources += sorted(LOCAL.glob("*.json"))
    for p in paths:
        p = Path(p)
        sources += sorted(p.rglob("record.json")) + (sorted(p.glob("*.json")) if p.name == "perf-records" else []) if p.is_dir() else [p]
    for source in sources:
        for r in read(source):
            if r.get("id") and r["id"] not in seen:
                seen.add(r["id"])
                found.append(r)
    return found


def summarise(records, session=None):
    # A capture perf-optimize ran for one of its launches names that run as its parent; its time is in the parent's phases.
    records = [r for r in records if (session is None or r.get("session") == session) and not r.get("parent")]
    out = {"runs": len(records), "phases": {p: 0.0 for p in PHASES}, "byKind": {}, "verdicts": {v: 0 for v in VERDICTS},
           "rejections": [], "accepted": [], "slowest": [], "invalid": []}
    steps = []
    for r in records:
        problems = validate(r)
        if problems:
            out["invalid"].append({"id": r.get("id"), "problems": problems})
        kind = out["byKind"].setdefault(r.get("kind", "?"), {"runs": 0, "seconds": 0.0, "phases": {p: 0.0 for p in PHASES}})
        kind["runs"] += 1
        for p, s in (r.get("phases") or {}).items():
            if p in out["phases"] and isinstance(s, (int, float)):
                out["phases"][p] += s
                kind["phases"][p] += s
                kind["seconds"] += s
        out["verdicts"][r.get("verdict", "error")] = out["verdicts"].get(r.get("verdict", "error"), 0) + 1
        label = ((r.get("candidate") or {}).get("label")) or r.get("kind")
        speed = r.get("speedup") or {}
        line = {"id": r.get("id"), "label": label, "mod": (r.get("mod") or {}).get("id"),
                "cold": interval(speed.get("cold")), "warm": interval(speed.get("warm"))}
        if r.get("verdict") == "rejected":
            out["rejections"].append({**line, "reason": r.get("rejection")})
        if r.get("verdict") == "accepted":
            out["accepted"].append(line)
        for s in r.get("steps") or []:
            steps.append({"run": label, "phase": s.get("phase"), "name": s.get("name"), "seconds": s.get("seconds", 0)})
    out["seconds"] = sum(out["phases"].values())
    out["slowest"] = sorted(steps, key=lambda s: -s["seconds"])[:10]
    return out


def interval(s):
    if not isinstance(s, dict) or not isinstance(s.get("geometricMean"), (int, float)):
        return None
    if not isinstance(s.get("low"), (int, float)) or not isinstance(s.get("high"), (int, float)):
        return f"{s['geometricMean']:.2f}x (no interval)"
    return f"{s['geometricMean']:.2f}x ({s['low']:.2f}-{s['high']:.2f})"


def minutes(seconds):
    return f"{seconds / 60:.1f} min"


def text(summary):
    lines = [f"{summary['runs']} runs, {minutes(summary['seconds'])} of wall time."]
    total = summary["seconds"] or 1
    lines.append("Phase      time        share")
    for p in PHASES:
        s = summary["phases"][p]
        lines.append(f"{p:<10} {minutes(s):>10}  {100 * s / total:5.1f}%")
    for kind, k in summary["byKind"].items():
        lines.append(f"{kind}: {k['runs']} runs, {minutes(k['seconds'])}")
    v = summary["verdicts"]
    lines.append(f"Accepted {v['accepted']}, rejected {v['rejected']}, observed {v['observed']}, failed {v['error']}.")
    for a in summary["accepted"]:
        lines.append(f"  accepted {a['label']} ({a['mod']}): cold {a['cold']}, warm {a['warm']}")
    for r in summary["rejections"]:
        lines.append(f"  rejected {r['label']} ({r['mod']}): {r['reason']}")
    lines.append("Slowest steps:")
    for s in summary["slowest"]:
        lines.append(f"  {s['seconds']:7.1f} s  {s['phase']:<8} {s['name']}  [{s['run']}]")
    for bad in summary["invalid"]:
        lines.append(f"Invalid record {bad['id']}: {'; '.join(bad['problems'])}")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", help="record files, or folders holding record.json files")
    ap.add_argument("--session", help="only runs of this --session")
    ap.add_argument("--local", action="store_true", help="also read .runs/perf-records, every run on this machine")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    summary = summarise(load(args.paths, args.local), args.session)
    print(json.dumps(summary, indent=1) if args.json else text(summary))
    return 1 if summary["invalid"] else 0


if __name__ == "__main__":
    sys.exit(main())
