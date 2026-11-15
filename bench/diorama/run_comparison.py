"""Run the same-JVM Diorama comparison snippets and keep one run record for them.

    python bench/diorama/run_comparison.py --root DIORAMA_SOURCE --label NAME --diff CANDIDATE.patch
        [--project :1.21.1] [--world DISPOSABLE_WORLD] [--compare compare.java ...] [--gate equivalence.java ...]
        [--session NAME] [--out FOLDER] [--skip-build]

The checkout must already hold the candidate and its reference baker (prepare_comparison.py). This
compiles it, starts the world, runs each --compare snippet (paired baseline/candidate samples, see
compare.java) and each --gate snippet (equivalence*.java, which throw on any difference), and writes
record.json in --out and .runs/perf-records/: phases, machine load, gates, and the paired speedup with a
95% bootstrap interval. Same-JVM timings are descriptive, so the verdict is "observed" when every gate
passed and "rejected" when one did not; perf-optimize decides acceptance. Snippet paths are relative to
this folder. Use modaccel.cmd on Windows; this finds the right launcher itself.
"""
import argparse
import json
import math
import os
import random
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE.parent))
import perf_sessions as P  # noqa: E402


def launcher():
    return str(ROOT / ("modaccel.cmd" if os.name == "nt" else "modaccel"))


def modaccel(args, root, project):
    """Runs one modaccel command with --json; returns (seconds, parsed output)."""
    start = time.monotonic()
    done = subprocess.run([launcher(), *args, "--root", str(root), "--project", project, "--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    text = done.stdout.strip()
    try:
        out = json.loads(text[text.rfind("\n{") + 1:] if "\n{" in text else text)
    except ValueError:
        out = {"ok": False, "result": {"error": (done.stderr or text)[-2000:]}}
    return time.monotonic() - start, out


def speedup(ratios, seed=20261114):
    """Geometric-mean baseline/candidate ratio with a 95% percentile bootstrap, as perf-optimize reports it."""
    logs = [math.log(r) for r in ratios if r > 0]
    if not logs:
        return None
    rng = random.Random(seed)
    boot = sorted(sum(rng.choice(logs) for _ in logs) / len(logs) for _ in range(10000))
    return {"geometricMean": math.exp(sum(logs) / len(logs)), "low": math.exp(boot[249]), "high": math.exp(boot[9749]), "pairs": len(logs)}


def cpu_load():
    """Whole-machine CPU use 0..1 where the platform says, from one second of samples; else None."""
    try:
        if os.name == "nt":
            out = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average"],
                                 capture_output=True, text=True, timeout=30).stdout.strip()
            return float(out) / 100 if out else None
        before = [int(v) for v in Path("/proc/stat").read_text().split("\n")[0].split()[1:]]
        time.sleep(1)
        after = [int(v) for v in Path("/proc/stat").read_text().split("\n")[0].split()[1:]]
        idle = (after[3] + after[4]) - (before[3] + before[4])
        total = sum(after) - sum(before)
        return 1 - idle / total if total else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True)
    ap.add_argument("--project", default=":1.21.1")
    ap.add_argument("--world")
    ap.add_argument("--label", required=True)
    ap.add_argument("--diff", required=True, help="the candidate patch, hashed into the record")
    ap.add_argument("--compare", action="append", default=[])
    ap.add_argument("--gate", action="append", default=[])
    ap.add_argument("--session", default="")
    ap.add_argument("--out", default=None)
    ap.add_argument("--skip-build", action="store_true")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    out = Path(args.out or ROOT / ".runs" / "diorama-comparison" / time.strftime("%Y%m%d-%H%M%S")).resolve()
    out.mkdir(parents=True, exist_ok=True)
    record = P.make("comparison", "diorama", "", "fabric", session=args.session, project=args.project, label=args.label, diff=args.diff)
    record["machine"] = {"os": sys.platform, "logicalCores": os.cpu_count(), "load": [], "jit": "C1 only (hidden test client)"}
    record["source"] = str(out)
    record["machine"]["load"].append({"when": "start", "systemCpu": cpu_load()})
    failures = []
    if not args.skip_build:
        seconds, result = modaccel(["compile"], root, args.project)
        P.step(record, "build", "compile", seconds)
        if not result.get("ok"):
            failures.append("compile failed: " + str(result.get("result", {}).get("error", ""))[:300])
    if not failures:
        seconds, result = modaccel(["world-check", "--world", args.world] if args.world else ["game-status"], root, args.project)
        P.step(record, "launch", "world-check" if args.world else "game-status", seconds)
        mods = (result.get("result") or {}).get("modsLoaded") or {}
        for mod in mods.get("mods") or []:
            if mod.get("id") == "diorama":
                record["mod"]["version"] = mod.get("loadedVersion") or mod.get("declaredVersion") or ""
        if not result.get("ok"):
            failures.append("no game: " + str((result.get("result") or {}).get("error", ""))[:300])
    ratios, values = [], {}
    for snippet in ([] if failures else args.compare):
        seconds, result = modaccel(["eval", "--file", str((HERE / snippet).resolve()), "--timeout", "120"], root, args.project)
        P.step(record, "measure", snippet, seconds)
        value = (result.get("result") or {}).get("value")
        if not result.get("ok") or value is None:
            failures.append(f"{snippet}: {str((result.get('result') or {}).get('error', ''))[:300]}")
            continue
        data = json.loads(value)
        values[snippet] = {k: v for k, v in data.items() if k != "samples"}
        ratios += [s["baselineMs"] / s["candidateMs"] for s in data.get("samples", []) if s.get("candidateMs")]
        record["gates"].append({"name": snippet + " outputs", "compare": "exact", "passed": True, "failures": []})
    for snippet in ([] if failures else args.gate):
        seconds, result = modaccel(["eval", "--file", str((HERE / snippet).resolve()), "--timeout", "120"], root, args.project)
        P.step(record, "gates", snippet, seconds)
        passed = bool(result.get("ok"))
        reason = [] if passed else [str((result.get("result") or {}).get("error", ""))[:300]]
        record["gates"].append({"name": snippet, "compare": "exact", "passed": passed, "failures": reason})
        if not passed:
            failures.append(f"gate {snippet} failed: {reason[0]}")
    record["machine"]["load"].append({"when": "end", "systemCpu": cpu_load()})
    record["values"] = values
    warm = speedup(ratios)
    record["speedup"] = {"warm": warm, "cold": None, "basis": "paired same-JVM bakes after warmup rounds; descriptive"} if warm else None
    P.finish(record, "rejected" if failures else "observed", "; ".join(failures) or None)
    problems = P.validate(record)
    if problems:
        record["problems"] = problems
    for path in (out / "record.json", P.LOCAL / f"{record['id']}.json"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record, indent=1), encoding="utf-8")
    print(json.dumps({"record": str(out / "record.json"), "verdict": record["verdict"], "speedup": warm, "rejection": record["rejection"]}, indent=1))
    return 0 if not failures else 1


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):  # UTF-8 whatever the console or PYTHONIOENCODING says
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
