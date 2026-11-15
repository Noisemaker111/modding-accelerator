"""The perf corpus through perf-optimize's built-in fingerprint gate alone: does the generic gate decide each diff as the
hand-written gates did? Shipped diffs (accepted with the hand gates) must pass it; known-wrong diffs must fail it.

    python bench/perf_sweep.py run [--only ID,ID] [--clones .runs] [--runs 2] [--prepare] [--record]
    python bench/perf_sweep.py table

The proofs queued for the cloud runner (each launches games; do not run them on a shared desktop):
    python bench/perf_sweep.py run --only storagenetwork --prepare --record   # #79 must pass; the old #75 diff must fail
    python bench/perf_sweep.py run --only cyclic,pipez --prepare --record     # shipped diffs pass; Pipez's stale cache fails

Each mod's clone and its experiment world are prepared as its bench/<mod>/README.md says (a clone at the pinned commit
with the bench commits, the world under .runs/<world folder>); --clones names the folder that holds the clones, by corpus
id. perf-optimize runs with --only fingerprint (no hand gate), --dry-run (nothing applied), sequential arms and --runs
pairs. A diff given as git:REV:PATH is read from this repository's history (the old #75 Storage Network diff).

Writes coverage/sweeps/perf.json after a full run, or a partial one with --record. Headline: diffs the generic gate
decided as expected, of all diffs in the corpus.
"""
import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "bench" / "perf-corpus.json"
OUT = ROOT / "coverage" / "sweeps" / "perf.json"
WORK = ROOT / ".runs" / "perf-sweep"
WINDOWS = os.name == "nt"
MODACCEL = [str(ROOT / ("modaccel.cmd" if WINDOWS else "modaccel"))]


def diff_file(spec):
    """A diff path, or git:REV:PATH read from this repository's history into the work folder."""
    if not spec.startswith("git:"):
        return ROOT / spec
    _, rev, path = spec.split(":", 2)
    out = WORK / "diffs" / (re.sub(r"[^A-Za-z0-9_.-]", "_", rev + "-" + path))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(subprocess.run(["git", "-C", str(ROOT), "show", f"{rev}:{path}"], capture_output=True, check=True).stdout)
    return out


def gate_verdict(optimize):
    """The fingerprint gate's verdict from a finished perf-optimize run (optimize.json), or None."""
    for gate in optimize.get("gates", []) or []:
        if gate.get("compare") == "fingerprint":
            return gate
    return None


def decide(expect, gate):
    """right when the gate passed a shipped diff or failed a wrong one; None when the gate did not decide."""
    if gate is None:
        return None
    return bool(gate.get("passed")) == (expect == "pass")


def run_diff(mod, root, spec, expect, args):
    label = re.sub(r"[^A-Za-z0-9_.-]", "_", Path(spec.split(":")[-1]).stem + ("-" + spec.split(":")[1] if spec.startswith("git:") else ""))
    out = WORK / mod["id"] / f"{label}-{int(time.time())}"
    command = MODACCEL + ["perf-optimize", "--root", str(root), "--experiment", str(ROOT / mod["experiment"]), "--patch", str(diff_file(spec)),
                          "--runs", str(args.runs), "--min-runs", str(args.runs), "--arms", "sequential", "--dry-run", "--only", "fingerprint",
                          "--out", str(out), "--json"]
    started = time.monotonic()
    try:
        subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
    except subprocess.TimeoutExpired:
        pass
    seconds = round(time.monotonic() - started, 1)
    optimize = json.loads((out / "optimize.json").read_text(encoding="utf-8")) if (out / "optimize.json").is_file() else {}
    gate = gate_verdict(optimize)
    return {"diff": spec, "expect": expect, "decidedRight": decide(expect, gate), "gatePassed": None if gate is None else gate.get("passed"),
            "failures": (gate or {}).get("failures", [])[:5], "unstable": (gate or {}).get("unstable"), "compared": (gate or {}).get("compared"),
            "differingSections": (gate or {}).get("differingSections"), "error": optimize.get("error"), "seconds": seconds, "out": str(out)}


def step(args, timeout=3600):
    """One modaccel command; raises with its output when it fails."""
    r = subprocess.run(MODACCEL + args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"modaccel {' '.join(args[:3])} failed: {(r.stdout + r.stderr)[-1500:]}")
    return r.stdout


def prepare(mod, clones):
    """
    The mod's clone and experiment world, as its bench/<mod>/README.md makes them, for an unattended runner: the pinned
    commit, the folder's prepare.diff committed on it (launch only the mod and its libraries, log at INFO), then a hidden
    client makes the world with the folder's create-world.java and scene.java, saves it, and it is copied to worldSource.
    """
    folder = ROOT / Path(mod["experiment"]).parent
    experiment = json.loads((ROOT / mod["experiment"]).read_text(encoding="utf-8"))
    root = Path(clones) / mod["id"]
    if not root.is_dir():
        subprocess.run(["git", "-c", "core.longpaths=true", "clone", mod["repo"], str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "checkout", "-q", mod["commit"]], check=True)
        if (folder / "prepare.diff").is_file():
            subprocess.run(["git", "-C", str(root), "apply", str(folder / "prepare.diff")], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=bench", "-c", "user.email=bench@localhost", "commit", "-qam", "bench: launch only the mod and its libraries, log at INFO"], check=True)
    world = (folder / experiment["worldSource"]).resolve()
    if world.is_dir():
        return root
    save = WORK / "save-world.java"
    save.parent.mkdir(parents=True, exist_ok=True)
    save.write_text("var server = mc.getSingleplayerServer();\nreturn server.submit(() -> server.saveEverything(true, true, true)).join();\n", encoding="utf-8")
    base = ["--root", str(root), "--json"]
    try:
        step(["ui-check", *base, "--jit", "full"])
        step(["eval", *base, "--file", str(folder / "create-world.java")])
        step(["eval", *base, "--until", "mc.player != null", "--timeout", "120"])
        step(["eval", *base, "--file", str(folder / "scene.java"), "--thread", "control"])
        step(["eval", *base, "--file", str(save), "--thread", "control"])
    finally:
        subprocess.run(MODACCEL + ["game-stop", *base], capture_output=True, timeout=600)
    game = root / experiment.get("gameDirectory", "run") / "saves" / experiment["world"]
    shutil.copytree(game, world, ignore=shutil.ignore_patterns("session.lock"))
    return root


def run_mod(mod, args):
    root = Path(args.clones) / mod["id"]
    record = {"id": mod["id"], "name": mod["name"], "experiment": mod["experiment"], "date": datetime.date.today().isoformat(), "diffs": []}
    if args.prepare:
        try:
            prepare(mod, args.clones)
        except Exception as failed:
            record["error"] = f"prepare failed: {failed}"
            return record
    if not root.is_dir():
        record["error"] = f"no prepared clone at {root} (see {Path(mod['experiment']).parent}/README.md)"
        return record
    for spec in mod.get("shipped", []):
        record["diffs"].append(run_diff(mod, root, spec, "pass", args))
    for wrong in mod.get("wrong", []):
        record["diffs"].append(run_diff(mod, root, wrong["diff"], "fail", args))
    return record


def summarize(records):
    total = sum(len(m.get("shipped", [])) + len(m.get("wrong", [])) for m in json.loads(CORPUS.read_text(encoding="utf-8"))["mods"])
    diffs = [d for r in records for d in r.get("diffs", [])]
    right = sum(d.get("decidedRight") is True for d in diffs)
    return {"date": datetime.date.today().isoformat(), "mods": len(records), "diffs run": len(diffs),
            "decided as the hand gates did": right, "decided otherwise": sum(d.get("decidedRight") is False for d in diffs),
            "undecided": sum(d.get("decidedRight") is None for d in diffs),
            "headline": {"name": "perf diffs the generic fingerprint gate decides as the hand gates did", "value": right, "of": total}}


def redecide(record):
    """
    Each diff of a recorded run decided again by the current gate on the fingerprints its launches recorded
    (fingerprint-diff --baseline --candidate): the gate is a function of those records, so no game is launched.
    """
    for d in record.get("diffs", []):
        runs = Path(d["out"]) / "runs"
        pairs = sorted({p.name.split("-")[0] for p in runs.glob("*-candidate") if (p / "fingerprint.json").is_file()}) if runs.is_dir() else []
        pairs = [n for n in pairs if (runs / f"{n}-baseline" / "fingerprint.json").is_file()]
        if not pairs:
            continue
        base = ",".join(str(runs / f"{n}-baseline" / "fingerprint.json") for n in pairs)
        cand = ",".join(str(runs / f"{n}-candidate" / "fingerprint.json") for n in pairs)
        r = subprocess.run(MODACCEL + ["fingerprint-diff", "--baseline", base, "--candidate", cand, "--json"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=600)
        line = next((l for l in reversed(r.stdout.splitlines()) if l.startswith("{")), None)
        if line is None:
            continue
        gate = json.loads(line)["result"]
        d.update({"decidedRight": decide(d["expect"], gate), "gatePassed": gate.get("passed"), "failures": gate.get("failures", [])[:5],
                  "unstable": gate.get("unstable"), "unstableFamilies": gate.get("unstableFamilies"), "compared": gate.get("compared"),
                  "differingSections": gate.get("differingSections"), "pairs": len(pairs), "decidedFrom": "recorded launches"})
    return record


def records_coverage(args):
    return not args.only or args.record


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run")
    run.add_argument("--only", default="")
    run.add_argument("--clones", default=str(ROOT / ".runs"))
    run.add_argument("--runs", type=int, default=2)
    run.add_argument("--timeout", type=int, default=7200)
    run.add_argument("--record", action="store_true", help="write coverage/sweeps/perf.json after a partial (--only) run too")
    run.add_argument("--prepare", action="store_true", help="make each missing clone and experiment world first (prepare), for an unattended runner")
    sub.add_parser("table")
    sub.add_parser("decide", help="decide every recorded run again with the current gate, from its recorded fingerprints (no game)")
    args = ap.parse_args()
    for stream in (sys.stdout, sys.stderr):   # a Windows console is not UTF-8 by default
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))["mods"]
    if args.cmd == "decide":
        data = json.loads(OUT.read_text(encoding="utf-8"))
        data["runs"] = [redecide(r) for r in data["runs"]]
        data["latest"] = summarize(data["runs"])
        OUT.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        args.cmd = "table"
    if args.cmd == "table":
        data = json.loads(OUT.read_text(encoding="utf-8"))
        for r in data["runs"]:
            for d in r.get("diffs", []):
                print(f"{r['id']:16} {d['diff'][:60]:60} expect {d['expect']:4} gate {d['gatePassed']} -> {d['decidedRight']}")
        print(json.dumps(data["latest"]["headline"]))
        return 0
    want = {x.strip() for x in args.only.split(",") if x.strip()}
    chosen = [m for m in corpus if not want or m["id"] in want]
    records = [run_mod(m, args) for m in chosen]
    for r in records:
        print(json.dumps({k: r[k] for k in ("id", "diffs") if k in r} | ({"error": r["error"]} if "error" in r else {}), indent=1))
    if records_coverage(args):
        previous = json.loads(OUT.read_text(encoding="utf-8")) if OUT.is_file() else {"runs": []}
        runs = {r["id"]: r for r in previous.get("runs", [])}
        runs.update({r["id"]: r for r in records})
        rows = sorted(runs.values(), key=lambda r: r["id"])
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps({"about": "perf-optimize's built-in fingerprint gate alone on the perf corpus (bench/perf-corpus.json), by python bench/perf_sweep.py: "
                                            "shipped diffs must pass it and known-wrong diffs must fail it.",
                                   "corpus": len(corpus), "latest": summarize(rows), "runs": rows}, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
