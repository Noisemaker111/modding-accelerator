"""How long each step of a NeoForge game setup (NeoForm: decompile, inject, recompile, ...) took in one mod folder.

NeoGradle writes each step's output under build/neoForm/<runtime>/steps/<step>/. A step's folder gets its first file
when the step starts, and its files stop changing when it ends, so start = the earliest folder or file time and
end = the latest file time. A step served from NeoGradle's shared cache (~/.gradle/caches/ng_execute) only copies its
output, so it shows up here as about 0 s; `--since` marks the steps whose files are older than a moment (a port's
start) as not run at all. A step NeoGradle restores from that cache gets new files too (in about a second), so the
proof that nothing was decompiled again is the cache itself: with --since, the count of cache entries made since
then is printed, 0 when every step came from an earlier build.

    python -I bench/neoform_steps.py PROJECT [--runtime SUBSTRING] [--since ISO-TIME] [--json]
"""
import argparse
import datetime
import json
import os
import pathlib
import sys


def step_times(steps: pathlib.Path, since: float = None) -> list:
    """With since, a step folder's files from an earlier build (it reuses the folder) are left out of its start."""
    rows = []
    for step in sorted(p for p in steps.iterdir() if p.is_dir()):
        dirs, files = [step.stat().st_mtime], []
        for root, subdirs, names in os.walk(step):
            dirs += [os.stat(os.path.join(root, d)).st_mtime for d in subdirs]
            files += [os.stat(os.path.join(root, n)).st_mtime for n in names]
        if not files:
            continue
        times = dirs + files
        if since is not None and max(files) >= since:
            times = [t for t in times if t >= since]
        start, end = min(times), max(files)
        rows.append({"step": step.name, "start": start, "end": end, "seconds": round(end - start, 1)})
    rows.sort(key=lambda r: r["start"])
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("project")
    parser.add_argument("--runtime", default="", help="only runtimes whose folder name contains this, e.g. 1.21.6")
    parser.add_argument("--since", help="ISO time; steps whose files all predate it did not run in this build")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    base = pathlib.Path(args.project) / "build" / "neoForm"
    runtimes = sorted(p for p in base.glob("*") if (p / "steps").is_dir() and args.runtime in p.name) if base.is_dir() else []
    if not runtimes:
        print(f"no NeoForm steps under {base}" + (f" matching {args.runtime!r}" if args.runtime else ""), file=sys.stderr)
        return 1
    since = datetime.datetime.fromisoformat(args.since).timestamp() if args.since else None
    out = []
    for runtime in runtimes:
        rows = step_times(runtime / "steps", since)
        for row in rows:
            row["ran"] = since is None or row["end"] >= since
        ran = [r for r in rows if r["ran"]]
        wall = round(max(r["end"] for r in ran) - min(r["start"] for r in ran), 1) if ran else 0.0
        out.append({"runtime": runtime.name, "wallSeconds": wall, "stepsRun": len(ran), "steps": rows})
    cache = pathlib.Path(os.environ.get("GRADLE_USER_HOME", pathlib.Path.home() / ".gradle")) / "caches" / "ng_execute"
    made = sum(1 for p in cache.iterdir() if p.stat().st_mtime >= since) if since is not None and cache.is_dir() else None
    if args.json:
        print(json.dumps({"runtimes": out, "cacheEntriesMade": made}, indent=1))
        return 0
    if made is not None:
        print(f"NeoGradle cache entries made since {args.since}: {made} ({cache})")
    for runtime in out:
        print(f"{runtime['runtime']}: {runtime['stepsRun']} steps run, {runtime['wallSeconds']} s from first start to last end")
        for row in runtime["steps"]:
            at = datetime.datetime.fromtimestamp(row["start"]).strftime("%H:%M:%S")
            print(f"  {row['step']:<30} {at}  {row['seconds']:>7.1f} s" + ("" if row["ran"] else "  (not run: older than --since)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
