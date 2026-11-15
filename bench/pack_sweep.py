#!/usr/bin/env python3
"""Runs mmt pack-check on a pinned corpus of public modpacks and ranks what it gets wrong or cannot do.

    python bench/pack_sweep.py run [--only ID,ID] [--jobs 1] [--fix] [--label NAME] [--mmt CHECKOUT] [--record]
    python bench/pack_sweep.py table [--label NAME]

bench/pack-sweep/corpus.json pins each pack: a Modrinth .mrpack by version and SHA-512, or a CurseForge export by
project and file id and length (its SHA-256 is recorded in the corpus on first download). The runner downloads each
into .runs/pack-sweep/packs/ID, runs mmt pack-check --pack FILE --json (with --fix when asked), and records per
pack: whether it ran at all, the server start, the mod the start's failure was blamed on, the new world and the
reopened world, client-only mods left out, every finding by kind and severity, and the stage timings.

Each pack's gaps are classified: the version or loader pack-check could not run (probe compile, loader install, pack
input), a start that failed with or without a named culprit, a world that did not reopen, warnings the game refuted
(false positives) and problems with no proven repair. With --fix, a pack whose shipped server fails but whose repaired one
makes and reopens a world (pack-check's fix.proven) counts as repaired, with what each repair did. The ranked table and pass
rates go to coverage/sweeps/pack.json; --label keeps each sweep's pass rates in its history. A server pack-check leaves behind
is stopped with its process tree.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "bench" / "pack-sweep" / "corpus.json"
OUT = ROOT / "coverage" / "sweeps" / "pack.json"
WORK = ROOT / ".runs" / "pack-sweep"
WINDOWS = os.name == "nt"
MMT = [str(ROOT / ("mmt.cmd" if WINDOWS else "mmt"))]
UA = {"User-Agent": "mmt-pack-sweep"}


def download(pack):
    folder = WORK / "packs" / pack["id"]
    folder.mkdir(parents=True, exist_ok=True)
    name = pack["url"].rsplit("/", 1)[-1] if pack["format"] == "mrpack" else f"{pack['id']}.zip"
    name = urllib.request.unquote(name)
    target = folder / name
    if not target.is_file() or target.stat().st_size != pack["bytes"]:
        data = urllib.request.urlopen(urllib.request.Request(pack["url"], headers=UA), timeout=1800).read()
        target.write_bytes(data)
    data = target.read_bytes()
    if len(data) != pack["bytes"]:
        raise RuntimeError(f"{target.name}: {len(data)} bytes, the corpus pins {pack['bytes']}")
    if pack.get("sha512") and hashlib.sha512(data).hexdigest() != pack["sha512"]:
        raise RuntimeError(f"{target.name}: SHA-512 differs from the corpus")
    sha256 = hashlib.sha256(data).hexdigest()
    if pack.get("sha256") and pack["sha256"] != sha256:
        raise RuntimeError(f"{target.name}: SHA-256 differs from the corpus")
    return target, sha256


def kill_tree(pid):
    if WINDOWS:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)
    else:
        subprocess.run(["pkill", "-TERM", "-P", str(pid)], capture_output=True)


def leftover_servers(instance):
    """Stops any server JVM pack-check left running in this pack's instance folder (each runs with -Xmx6G)."""
    if not instance or not WINDOWS:
        return 0
    script = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*mmt.probe*' -and "
              "$_.CommandLine -like '*" + str(Path(instance).name) + "*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; $_.ProcessId }")
    r = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True)
    return len([l for l in r.stdout.split() if l.strip().isdigit()])


def pack_check(path, args):
    command = MMT + ["pack-check", "--pack", str(path), "--json"] + (["--fix"] if args.fix else [])
    started = time.monotonic()
    # MMT_TRACE: a pack-check that stops with an error prints its stack into the pack's log.
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                               env=dict(os.environ, MMT_TRACE="1"))
    try:
        out, _ = process.communicate(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        kill_tree(process.pid)
        out, _ = process.communicate()
        return 124, {"error": f"pack-check timed out after {args.timeout} s"}, time.monotonic() - started, out
    value = None
    for start in [m.start() for m in re.finditer(r"(?m)^\{", out)][::-1]:
        try:
            value = json.loads(out[start:])
            break
        except json.JSONDecodeError:
            continue
    if value is None:
        value = {"error": out.strip()[-3000:]}
    receipt = value
    if isinstance(value.get("result"), dict):
        value = dict(value["result"], _stages=receipt.get("stages", []))
    return process.returncode, value, time.monotonic() - started, out


def refusal_gap(text):
    rules = [
        (r"probe-compile|MmtPackProbe\.java:\d+: error", "probe does not compile for this game version"),
        (r"[Ii]nstaller|install", "loader server install failed"),
        (r"[Nn]o (?:pack|input)|not a pack|does not look like", "pack input not recognised"),
        (r"Java|JDK|runtime", "Java runtime for this game version"),
        (r"timed out", "pack-check timed out"),
        (r"download|HTTP|Connection|403|404", "a mod download failed"),
    ]
    for pattern, gap in rules:
        if re.search(pattern, text):
            return gap
    return "pack-check stopped: " + text.strip().split("\n")[0][:120]


def analyse(pack, code, result, seconds):
    record = {"id": pack["id"], "name": pack["name"], "format": pack["format"], "loader": pack["loader"], "minecraft": pack["minecraft"],
              "exit": code, "seconds": round(seconds, 1), "gaps": []}
    if "error" in result and "pack" not in result:
        record["outcome"] = "did not run"
        record["error"] = str(result["error"])[-2500:]
        record["gaps"].append(refusal_gap(record["error"]))
        return record
    record["mods"] = result.get("mods")
    record["loaderUsed"] = result.get("loader")
    record["staticChecks"] = result.get("staticChecks")
    game = result.get("game") or {}
    first = game.get("newWorld") or {}
    second = game.get("reopen") or {}
    record["start"] = {"opened": first.get("opened"), "seconds": first.get("seconds"), "failure": first.get("failure"),
                       "suspects": first.get("suspects")}
    record["reopen"] = {"opened": second.get("opened"), "seconds": second.get("seconds"), "failure": second.get("failure"),
                        "suspects": second.get("suspects")} if second else None
    record["clientOnlyLeftOut"] = len(game.get("clientOnly") or [])
    record["leftOutOfServer"] = len(game.get("leftOut") or {})
    record["keptForDependants"] = sorted((game.get("keptForDependants") or {}).keys())
    probe = first.get("probe") or {}
    # Counts, not the probe's own lists: one pack's placements run to megabytes.
    record["probe"] = {k: len(v) if isinstance(v, list) else v for k, v in probe.items() if k in ("placed", "blocks", "items", "dimensions", "errors", "failed")}
    if probe.get("error"):
        record["probe"]["error"] = str(probe["error"]).split("\n")[0][:300]
        record["gaps"].append("the probe stopped before it checked the world")
    findings = result.get("findings") or []
    kinds = {}
    for f in findings:
        key = f"{f.get('severity')}:{f.get('kind')}"
        kinds[key] = kinds.get(key, 0) + 1
    record["findings"] = kinds
    record["problems"] = [{"kind": f.get("kind"), "summary": str(f.get("summary"))[:300], "jars": (f.get("jars") or [])[:4]}
                          for f in findings if f.get("severity") == "problem"]
    refuted = [f for f in findings if f.get("severity") == "note" and re.search(r"loaded (?:it )?anyway|refuted|the game loaded", str(f.get("summary")))]
    record["refutedWarnings"] = len(refuted)
    fix = result.get("fix") or {}
    record["fix"] = {
        "applied": [a.get("id") for a in fix.get("applied") or []],
        "notes": fix.get("notes") or [],
        "proven": fix.get("proven"),
        "rounds": fix.get("rounds") or [],
        "remaining": [{"kind": f.get("kind"), "summary": str(f.get("summary"))[:300]} for f in fix.get("remaining") or []],
        "comparison": fix.get("comparison") or [],
        "fixedExport": fix.get("fixedExport"),
        "recheck": {k: {"opened": (((fix.get("recheck") or {}).get(k)) or {}).get("opened"), "seconds": (((fix.get("recheck") or {}).get(k)) or {}).get("seconds")}
                    for k in ("newWorld", "reopen")},
    } if result.get("fix") else None
    record["ok"] = result.get("ok")
    record["stages"] = [{"name": s.get("name"), "seconds": round(float(s.get("seconds") or 0), 1)} for s in result.get("_stages", [])]
    if not first.get("opened"):
        failure = str(first.get("failure") or "")
        if re.search(r"probe|MmtPackProbe", failure):
            record["gaps"].append("the probe failed on this game version")
        record["gaps"].append("server start failed, culprit named" if first.get("suspects") else "server start failed, no culprit named")
        record["outcome"] = "start failed"
    elif second and not second.get("opened"):
        record["gaps"].append("world did not reopen, culprit named" if second.get("suspects") else "world did not reopen, no culprit named")
        record["outcome"] = "reopen failed"
    else:
        record["outcome"] = "world made and reopened"
    if record["outcome"] != "world made and reopened" and record["fix"] and record["fix"].get("proven"):
        record["outcome"] = "repaired: world made and reopened"
    if refuted:
        record["gaps"].append("false positive: warnings the game refuted")
    if record["problems"] and not (record["fix"] and record["fix"].get("proven")):
        for p in record["problems"]:
            record["gaps"].append(f"problem with no proven repair: {p['kind']}")
    if record["clientOnlyLeftOut"]:
        record["gaps"].append("client-only mods not marked on CurseForge/Modrinth (left out of the server)")
    return record


def summarize(records, label):
    gaps = {}
    for r in records:
        for g in dict.fromkeys(r["gaps"]):
            e = gaps.setdefault(g, {"gap": g, "packs": []})
            e["packs"].append(r["id"])
    ranked = sorted(gaps.values(), key=lambda e: (-len(e["packs"]), e["gap"]))
    for i, e in enumerate(ranked, 1):
        e["rank"] = i
    outcomes = {}
    for r in records:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    rates = {"label": label, "date": datetime.date.today().isoformat(), "packs": len(records),
             "ran": sum(r["outcome"] != "did not run" for r in records),
             "started": sum(bool((r.get("start") or {}).get("opened")) for r in records),
             "world made and reopened": sum(r["outcome"] == "world made and reopened" for r in records),
             "repaired": sum(r["outcome"] == "repaired: world made and reopened" for r in records),
             "passing with repairs": sum(r["outcome"] in ("world made and reopened", "repaired: world made and reopened") for r in records),
             "repairs proven": sum(bool((r.get("fix") or {}).get("proven")) for r in records),
             "failed start with a named culprit": sum(r["outcome"] == "start failed" and bool((r.get("start") or {}).get("suspects")) for r in records),
             "outcomes": outcomes}
    return ranked, rates


def write(records, label):
    ranked, rates = summarize(records, label)
    previous = json.loads(OUT.read_text(encoding="utf-8")) if OUT.is_file() else {}
    history = [h for h in previous.get("history", []) if h.get("label") != label] + [rates]
    data = {"about": "mmt pack-check on the pinned packs in bench/pack-sweep/corpus.json, by python bench/pack_sweep.py. gaps ranks each gap by how many packs show it; history keeps each labelled sweep's pass rates.",
            "corpus": len(json.loads(CORPUS.read_text(encoding="utf-8"))["packs"]), "latest": rates, "history": history, "gaps": ranked,
            "runs": sorted(records, key=lambda r: r["id"])}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return ranked, rates


def table(ranked, rates):
    lines = [f"Pack sweep ({rates['label']}): {rates['packs']} packs; ran {rates['ran']}, started {rates['started']}, "
             f"world made and reopened {rates['world made and reopened']}, repaired {rates.get('repaired', 0)} "
             f"(passing with repairs {rates.get('passing with repairs', rates['world made and reopened'])})", "", "| # | Gap | Packs | Which |", "|---|---|---|---|"]
    for e in ranked:
        lines.append(f"| {e['rank']} | {e['gap']} | {len(e['packs'])} | {', '.join(e['packs'])[:120]} |")
    return "\n".join(lines)


def load_records():
    runs = WORK / "runs"
    records = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(runs.glob("*.json"))] if runs.is_dir() else []
    for r in records:
        if isinstance(r.get("probe"), dict):
            r["probe"] = {k: len(v) if isinstance(v, list) else v for k, v in r["probe"].items()}
    return records


def run_one(pack, args, corpus):
    try:
        path, sha256 = download(pack)
    except Exception as e:
        record = {"id": pack["id"], "name": pack["name"], "format": pack["format"], "loader": pack["loader"], "minecraft": pack["minecraft"],
                  "outcome": "did not run", "error": f"download: {e}", "gaps": ["pack download failed"]}
    else:
        if not pack.get("sha512") and not pack.get("sha256"):
            pack["sha256"] = sha256
            CORPUS.write_text(json.dumps(corpus, indent=2) + "\n", encoding="utf-8")
        code, result, seconds, raw = pack_check(path, args)
        record = analyse(pack, code, result, seconds)
        record["leftoverServersStopped"] = leftover_servers(((result.get("game") or {}).get("instance")) if isinstance(result, dict) else None)
        logs = WORK / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        (logs / f"{pack['id']}.out").write_text(raw, encoding="utf-8")
    record["started"] = datetime.datetime.now().isoformat(timespec="seconds")
    record["label"] = args.label
    runs = WORK / "runs"
    runs.mkdir(parents=True, exist_ok=True)
    (runs / f"{pack['id']}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--only", default="")
    run.add_argument("--jobs", type=int, default=1, help="ignored: one pack at a time, since each server takes up to 6 GB")
    run.add_argument("--fix", action="store_true", help="pack-check --fix: a repaired server beside the shipped one")
    # pack-check judges each start by its log (a stuck start stops itself), so this only bounds a whole --fix run of the biggest packs.
    run.add_argument("--timeout", type=int, default=4 * 3600)
    run.add_argument("--label", default="latest")
    run.add_argument("--resume", action="store_true", help="skip what already has a record under this --label")
    run.add_argument("--mmt", default="")
    run.add_argument("--record", action="store_true", help="write coverage/sweeps/pack.json after a partial (--only) run too")
    t = sub.add_parser("table")
    t.add_argument("--label", default="latest")
    args = parser.parse_args()
    if args.command == "table":
        ranked, rates = write(load_records(), args.label)
        print(table(ranked, rates))
        return 0
    if args.mmt:
        MMT[0] = str(Path(args.mmt).resolve() / ("mmt.cmd" if WINDOWS else "mmt"))
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    packs = corpus["packs"]
    if args.only:
        wanted = set(args.only.split(","))
        packs = [p for p in packs if p["id"] in wanted]
    if args.resume:
        done = {r["id"] for r in load_records() if r.get("label") == args.label}
        packs = [p for p in packs if p["id"] not in done]
    jobs = 1
    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        futures = {pool.submit(run_one, p, args, corpus): p for p in packs}
        for f in concurrent.futures.as_completed(futures):
            p = futures[f]
            try:
                r = f.result()
                print(f"{p['id']}: {r['outcome']} {r['gaps']}", flush=True)
            except Exception as e:
                print(f"{p['id']}: runner error {e!r}", flush=True)
    if records_coverage(args):
        ranked, rates = write(load_records(), args.label)
    else:
        ranked, rates = summarize(load_records(), args.label)
        print(f"Partial run (--only): {OUT.relative_to(ROOT)} is left as it is; pass --record to write it.")
    print(table(ranked, rates))
    return 0


def records_coverage(args):
    """Whether a run writes the tracked coverage file: a run of the whole corpus, or one asked to with --record."""
    return not args.only or args.record


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):  # UTF-8 whatever the console or PYTHONIOENCODING says
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
