"""The porting scoreboard: run every pinned port and say what changed.

    python bench/port_board.py                    every case in bench/port-cases.json
    python bench/port_board.py --only ID,ID       some of them
    python bench/port_board.py --list             the cases and what is recorded for each, running nothing
    python bench/port_board.py --record           also write a ledger row and a measure point for each result that changed

Each case is a mod's source at a pinned commit, cloned once into .runs/port-board/<id>, put back to that
commit before every run, and ported with `modaccel port --fix`: nothing is written by hand. The table says,
for each port, what the measures and the ledger have recorded, what the last board run got, and what this
run got. Below it, the errors still left across every mod are grouped by the game update that caused them,
biggest first: that is the next rule to write.

Change a rule in knowledge/fixes.json or the porter, run the board, and read the difference. It exits 1 when
a port got worse than what is recorded or could not run. Ports run one at a time, because NeoForge mods share
one decompiled game per version and the second finds the first one's setup. A port only reaches "builds";
whether it loads and behaves in the game is a game check (see the port-a-mod skill).
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import measures as M  # noqa: E402
import record  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "bench" / "port-cases.json"
CACHE = ROOT / ".runs" / "port-board"
KEEP = (".modaccel", ".gradle", "build", "run")   # a mod's own caches survive the reset, so the next port is warm
RANK = {"stop": 0, "build": 1, "load": 2, "behaviour": 3}


def sh(cmd, cwd=None, timeout=None):
    return subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)


def modaccel_cmd(path):
    path = Path(path)
    return [sys.executable, str(path)] if path.suffix == ".py" else [str(path)]


def default_modaccel():
    return ROOT / ("modaccel.cmd" if os.name == "nt" else "modaccel")


def checkout(case, cache, modaccel):
    """The case's source at its pinned commit, with whatever the last port wrote taken back."""
    work = cache / case["id"]
    if not (work / ".git").exists():
        work.parent.mkdir(parents=True, exist_ok=True)
        r = sh(["git", "clone", "--quiet", "--no-checkout", case["repo"], work])
        if r.returncode:
            raise RuntimeError(f"git clone {case['repo']} failed: {r.stderr.strip()[-300:]}")
    else:
        sh(modaccel + ["port", "--root", work, "--undo"])   # let modaccel close its own transaction first; none is fine
    if sh(["git", "cat-file", "-e", case["commit"] + "^{commit}"], cwd=work).returncode:
        r = sh(["git", "fetch", "--quiet", "origin", case["commit"]], cwd=work)
        if r.returncode:
            r = sh(["git", "fetch", "--quiet", "origin"], cwd=work)
        if r.returncode:
            raise RuntimeError(f"git fetch {case['commit'][:10]} failed: {r.stderr.strip()[-300:]}")
    r = sh(["git", "checkout", "--quiet", "--force", "--detach", case["commit"]], cwd=work)
    if r.returncode:
        raise RuntimeError(f"git checkout {case['commit'][:10]} failed: {r.stderr.strip()[-300:]}")
    sh(["git", "clean", "-fdq"] + [x for k in KEEP for x in ("-e", k)], cwd=work)
    return work


def source_version(work):
    """The Minecraft version the source says it is on (gradle.properties minecraft_version), or None when it does not say."""
    props = work / "gradle.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "minecraft_version" and value.strip():
                return value.strip()
    return None


def run_case(case, cache, modaccel, timeout):
    """Port one case and return what it reached. Never raises: a port that cannot run is a result too."""
    started = time.time()
    try:
        work = checkout(case, cache, modaccel)
        pinned = source_version(work)
        if pinned and pinned != case["from"]:
            raise RuntimeError(f"the pinned source is on Minecraft {pinned}, but the case ports from {case['from']}: fix its commit or its from in bench/port-cases.json")
        cmd = modaccel + ["port", "--root", work, "--from", case["from"], "--to", case["to"], "--fix", "--json"]
        if case.get("project"):
            cmd += ["--project", case["project"]]
        r = sh(cmd, timeout=timeout)
        line = next((l for l in reversed(r.stdout.splitlines()) if l.startswith("{")), None)
        if not line:
            raise RuntimeError("modaccel port printed no result: " + " ".join((r.stderr or r.stdout).split())[-300:])
        out = json.loads(line)
        (cache / f"{case['id']}.json").write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
        run = record.read_port(out)
        run["receipt"] = run["receipt"] or str(cache / f"{case['id']}.json")
    except subprocess.TimeoutExpired:
        run = {"built": False, "errors": None, "unresolved": [], "reason": f"no answer in {timeout} s", "receipt": None}
    except (RuntimeError, OSError, json.JSONDecodeError) as e:
        run = {"built": False, "errors": None, "unresolved": [], "reason": str(e), "receipt": None}
    run["seconds"] = round(time.time() - started, 1)
    run["status"] = "build" if run["built"] else "stop"
    return run


# ---- what is already recorded ---------------------------------------------------------------------
def recorded(case, book, ledger):
    """The errors left on the case's measure (None when it has none) and the furthest status the ledger has for the port."""
    errors = None
    m = book.by_id.get(case.get("measure", ""))
    if m and book.points(m):
        errors = book.points(m)[-1]["value"]
    rows = [h for h in ledger["hops"] if (h["mod"], h["loader"], h["from"], h["to"]) == (case["mod"], case["loader"], case["from"], case["to"])]
    status = max((h["status"] for h in rows), key=RANK.get, default=None)
    return errors, status


def verdict(run, rec_errors, rec_status):
    """better | same | worse | new | failed, comparing this run with the record."""
    if run["errors"] is None and not run["built"]:
        return "failed"
    if rec_status is None and rec_errors is None:
        return "new"
    was_built = rec_status in ("build", "load", "behaviour")
    if run["built"]:
        return "same" if was_built else "better"
    if was_built:
        return "worse"
    if rec_errors is None:
        return "same"
    return "better" if run["errors"] < rec_errors else "worse" if run["errors"] > rec_errors else "same"


def words(run):
    if run is None:
        return "—"
    if run.get("built"):
        return "builds"
    if run.get("errors") is None:
        return "did not run"
    return f"{run['errors']} error{'s' if run['errors'] != 1 else ''}"


def board(cases, results, book, ledger, previous):
    lines = ["| Port | Recorded | Last run | Now | | Time |", "|---|---|---|---|---|---|"]
    mark = {"better": "better", "same": "same", "worse": "**worse**", "new": "new", "failed": "**did not run**"}
    for c in cases:
        run = results[c["id"]]
        rec_errors, rec_status = recorded(c, book, ledger)
        rec = (f"{rec_errors} error{'s' if rec_errors != 1 else ''}" if rec_errors is not None and RANK.get(rec_status, 0) < 1 else "") or (rec_status or "—")
        took = f"{run['seconds'] / 60:.1f} min" if run["seconds"] >= 120 else f"{run['seconds']:.0f} s"
        lines.append(f"| {c['mod']}, {c['loader']} {c['from']} → {c['to']} | {rec} | {words(previous.get(c['id']))} | {words(run)} | "
                     f"{mark[verdict(run, rec_errors, rec_status)]} | {took} |")
    failed = [(c, results[c["id"]]) for c in cases if results[c["id"]]["errors"] is None and not results[c["id"]]["built"]]
    if failed:
        lines += ["", "Did not run:"] + [f"- {c['id']}: {' '.join(str(r['reason']).split())[:300]}" for c, r in failed]
    return "\n".join(lines)


def left_by_cause(cases, results):
    """Every error still left, grouped by the update it is blamed on and what it is about, biggest group first."""
    groups = {}
    for c in cases:
        for e in results[c["id"]].get("unresolved", []):
            g = groups.setdefault((record.cause(e), record.subject(e)), {"n": 0, "mods": set()})
            g["n"] += 1
            g["mods"].add(c["mod"])
    if not groups:
        return ""
    lines = ["| Update | About | Errors | Mods |", "|---|---|---|---|"]
    for (update, about), g in sorted(groups.items(), key=lambda kv: (-len(kv[1]["mods"]), -kv[1]["n"], kv[0])):
        lines.append(f"| {update} | {about.replace('|', '/')} | {g['n']} | {', '.join(sorted(g['mods']))} |")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated case ids")
    ap.add_argument("--list", action="store_true", help="print the cases and what is recorded, run nothing")
    ap.add_argument("--record", action="store_true", help="write a ledger row and a measure point for each result that differs from the record")
    ap.add_argument("--modaccel", type=Path, default=default_modaccel(), help="the modaccel to test (default: this checkout's); another worktree's for an A/B run")
    ap.add_argument("--cases", type=Path, default=CASES)
    ap.add_argument("--cache", type=Path, default=CACHE, help="where the clones and the last results live")
    ap.add_argument("--timeout", type=int, default=7200, help="seconds one port may take (a cold NeoForge setup decompiles the game)")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):   # a Windows console is not UTF-8 by default, and the tables use arrows
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    cases = json.loads(args.cases.read_text(encoding="utf-8"))["cases"]
    if args.only:
        want = [x.strip() for x in args.only.split(",")]
        unknown = [x for x in want if x not in {c["id"] for c in cases}]
        if unknown:
            print(f"No case {', '.join(unknown)}. Cases: {', '.join(c['id'] for c in cases)}", file=sys.stderr)
            return 2
        cases = [c for c in cases if c["id"] in want]
    book, ledger = M.Book(), record.load_json(record.LEDGER)
    last = args.cache / "latest.json"
    previous = json.loads(last.read_text(encoding="utf-8")) if last.is_file() else {}

    if args.list:
        print("| Case | Port | Recorded | Last run |\n|---|---|---|---|")
        for c in cases:
            e, s = recorded(c, book, ledger)
            rec = ", ".join(x for x in (s, f"{e} errors" if e is not None and RANK.get(s, 0) < 1 else None) if x) or "—"
            print(f"| {c['id']} | {c['mod']}, {c['loader']} {c['from']} → {c['to']} | {rec} | {words(previous.get(c['id']))} |")
        return 0

    results = {}
    modaccel = modaccel_cmd(args.modaccel)
    for i, c in enumerate(cases, 1):
        print(f"[{i}/{len(cases)}] {c['id']} ...", file=sys.stderr, flush=True)
        results[c["id"]] = run_case(c, args.cache, modaccel, args.timeout)
        print(f"         {words(results[c['id']])} in {results[c['id']]['seconds']:.0f} s", file=sys.stderr, flush=True)
    print(board(cases, results, book, ledger, previous))
    causes = left_by_cause(cases, results)
    if causes:
        print("\nWhat is left, by the update that caused it (the next rules to write):\n\n" + causes)

    args.cache.mkdir(parents=True, exist_ok=True)
    keep = dict(previous)
    keep.update({k: {"built": v["built"], "errors": v["errors"], "seconds": v["seconds"]} for k, v in results.items()})
    last.write_text(json.dumps(keep, indent=1), encoding="utf-8")

    worst = 0
    for c in cases:
        run = results[c["id"]]
        rec_errors, rec_status = recorded(c, book, ledger)
        v = verdict(run, rec_errors, rec_status)
        if v in ("worse", "failed"):
            worst = 1
        if args.record and v in ("better", "worse", "new"):
            row, point = record.add_port(run["receipt"], c["mod"], c["loader"], frm=c["from"], to=c["to"], measure=c.get("measure"))
            print(f"recorded {c['id']}: {row['status']}" + (f", {point['value']} errors on {c['measure']}" if point else ""), file=sys.stderr)
    if args.record:
        print("Recorded only what changed. A row says build at most: add load or behaviour with python bench/record.py port after the game check.", file=sys.stderr)
    return worst


if __name__ == "__main__":
    sys.exit(main())
