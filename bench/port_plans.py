"""The porting worklist for the whole corpus: every corpus mod's port plan, ranked by the game code that blocks the most
ports.

For every corpus mod checked out by bench/sweep.py, `mmt port-plan` finds each place in its code that touches game code
on its own version, and the plan is joined with the game's change map of every jump the corpus ports it across. The
report ranks changed game code by how many ports it blocks, with what the target did to it: renamed, moved to another
class, takes different arguments, the game shows the replacement, or removed with nothing replacing it. One row handled
is handled for every mod that touches it.

    python bench/port_plans.py run [--only ID,ID]     every mod's port plan (undoes each clone's port first)
    python bench/port_plans.py report                 the ranked worklist from the last run
"""

import argparse
import collections
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLONES = ROOT / ".runs/sweep/clones"
OUT = ROOT / ".runs/port-plans"
MMT = [str(ROOT / ("mmt.cmd" if sys.platform == "win32" else "mmt"))]

# The same words tools/cli/PortPlan.java uses.
RENAMED = "renamed"
MOVED = "moved to another class"
ARGUMENTS = "takes different arguments"
KNOWN = "the game shows the replacement"
REMOVED = "removed, nothing replaces it"


def corpus():
    data = json.loads((ROOT / "bench/sweep/corpus.json").read_text(encoding="utf-8"))
    return data["mods"] if isinstance(data, dict) else data


def what_changed(path):
    """What the target did to each method, field and class, from a change map (dotted classes, $ nested, JVM descriptors)."""
    m = json.loads(path.read_text(encoding="utf-8"))
    code = {}
    for section, what in (("methods", RENAMED), ("methodsMoved", MOVED), ("signatures", ARGUMENTS)):
        for e in m.get(section, []):
            code.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", what)
    for section in ("rewrites", "chains", "argDefaults", "siblings"):
        for e in m.get(section, []):
            code.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", KNOWN)
    for e in m.get("methodsRemoved", []):
        code.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", REMOVED)
    for e in m.get("fields", []):
        code.setdefault(f"{e.get('owner')}.{e.get('from')}", RENAMED)
    for e in m.get("fieldsRemoved", []):
        code.setdefault(f"{e.get('owner')}.{e.get('from')}", REMOVED)
    classes = {}
    for e in m.get("classes", []):
        classes[e.get("from")] = MOVED
    for e in m.get("classRewrites", []):
        classes.setdefault(e.get("from"), KNOWN)
    for c in m.get("classesRemoved", []):
        classes.setdefault(c, REMOVED)
    return code, classes


def verdict(place, code, classes):
    owner, name, kind = place["owner"], place["name"], place["kind"]
    if kind == "class":
        return owner, classes.get(owner)
    target = f"{owner}.{name}" + ("" if kind == "field" else place["desc"])
    what = code.get(target)
    if what is None and owner in classes:
        what = "its class: " + classes[owner]
    return target, what


def maps_for(clone, mod_from):
    """Each jump's change map the porter already built in this clone: {target version: path}."""
    found = {}
    maps = clone / ".mmt/portable/maps"
    # A Mojang-named mod's map is the game's own (game-*); a yarn-named mod's is its route map in yarn names.
    candidates = list(maps.glob(f"game-*-mojmap-{mod_from}-*.json")) or [
        p for p in maps.glob(f"route-*-{mod_from}-*.json") if not p.name.endswith(".game.json")]
    for p in candidates:
        to = p.stem.split(f"-{mod_from}-", 1)[1]
        if to not in found or p.stat().st_mtime > found[to].stat().st_mtime:
            found[to] = p
    return found


def run(args):
    OUT.mkdir(parents=True, exist_ok=True)
    wanted = set(args.only.split(",")) if args.only else None
    for mod in corpus():
        if wanted and mod["id"] not in wanted:
            continue
        clone = CLONES / mod["id"]
        maps = maps_for(clone, mod["from"]) if clone.is_dir() else {}
        if not maps:
            continue
        subprocess.run(MMT + ["port", "--root", str(clone), "--undo"], capture_output=True)
        cmd = MMT + ["port-plan", "--root", str(clone), "--json"] + (["--project", mod["project"]] if mod.get("project") else [])
        done = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        plan_file = clone / ".mmt/portable/port-plan.json"
        if done.returncode != 0 or not plan_file.is_file():
            tail = (done.stdout + done.stderr).strip().splitlines()[-1:] or ["no output"]
            print(f"{mod['id']}: no port plan: {tail[0][:160]}", flush=True)
            continue
        places = json.loads(plan_file.read_text(encoding="utf-8"))["places"]
        jumps = {}
        for to, path in sorted(maps.items()):
            code, classes = what_changed(path)
            by_change = collections.Counter()
            targets = collections.Counter()
            for place in places:
                target, what = verdict(place, code, classes)
                if what:
                    by_change[what] += 1
                    targets[(target, what)] += 1
            jumps[to] = {"placesTouchingGameCode": len(places), "byChange": dict(by_change),
                         "changedGameCode": [{"gameCode": t, "change": w, "places": n} for (t, w), n in targets.most_common()]}
        (OUT / f"{mod['id']}.json").write_text(json.dumps({"id": mod["id"], "from": mod["from"], "jumps": jumps}, indent=1), encoding="utf-8")
        summary = ", ".join(f"{to}: {sum(j['byChange'].values())} to change" for to, j in jumps.items())
        print(f"{mod['id']}: {len(places)} places touch game code; {summary}", flush=True)


AUTOMATIC = {RENAMED, MOVED, KNOWN, "its class: " + MOVED, "its class: " + KNOWN}


def rule_coverage():
    """Game code a rule in knowledge/fixes.json names: in its members list, or by method and class name in its match."""
    import re
    rules = json.loads((ROOT / "knowledge/fixes.json").read_text(encoding="utf-8"))["rules"]
    named, texts = set(), []
    for r in rules:
        for m in r.get("members") or []:
            named.add(str(m).replace("/", "."))
        texts.append(r.get("match", "") + " " + json.dumps(r.get("apply", {})))

    def covered(target):
        owner, _, rest = target.partition("(")[0].rpartition(".") if "(" in target else (target, "", "")
        owner_simple = (owner or target).split(".")[-1].split("$")[-1]
        name = rest if "(" in target else ""
        if target.split("(")[0] in named or (owner or target) in named:
            return True
        return any((name and re.search(r"\b" + re.escape(name) + r"\b", t)) and owner_simple in t for t in texts) if name else any(
            re.search(r"\b" + re.escape(owner_simple) + r"\b", t) for t in texts)
    return covered


def report(args):
    if args.left:
        return left(args)
    ports = 0
    blocked = collections.defaultdict(set)
    change = {}
    kinds = collections.Counter()
    for f in sorted(OUT.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for to, j in d["jumps"].items():
            ports += 1
            for row in j["changedGameCode"]:
                blocked[row["gameCode"]].add(f"{d['id']}->{to}")
                change[row["gameCode"]] = row["change"]
                kinds[row["change"]] += row["places"]
    print(f"{ports} ports; places to change, by what the target did: {dict(kinds.most_common())}")
    print("| ports blocked | what the target did | game code |\n|---|---|---|")
    for target, where in sorted(blocked.items(), key=lambda kv: -len(kv[1]))[: args.top]:
        print(f"| {len(where)} | {change[target]} | {target[:120]} |")


def left(args):
    """What the porter does not handle yet: changes it has no automatic step or rule for, by ports they block."""
    covered = rule_coverage()
    blocked = collections.defaultdict(set)
    change = {}
    ports = 0
    handled = collections.Counter()
    for f in sorted(OUT.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for to, j in d["jumps"].items():
            ports += 1
            for row in j["changedGameCode"]:
                how = "automatic" if row["change"] in AUTOMATIC else "rule" if covered(row["gameCode"]) else "left"
                handled[how] += row["places"]
                if how == "left":
                    blocked[row["gameCode"]].add(f"{d['id']}->{to}")
                    change[row["gameCode"]] = row["change"]
    total = sum(handled.values())
    print(f"{ports} ports, {total} places to change: " + ", ".join(f"{k} {v} ({100 * v // max(total, 1)}%)" for k, v in handled.most_common()))
    print(f"{len(blocked)} changes the porter does not handle yet; the ones blocking most ports:")
    print("| ports blocked | what the target did | game code |\n|---|---|---|")
    for target, where in sorted(blocked.items(), key=lambda kv: -len(kv[1]))[: args.top]:
        print(f"| {len(where)} | {change[target]} | {target[:120]} |")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--only")
    r.set_defaults(func=run)
    s = sub.add_parser("report")
    s.add_argument("--top", type=int, default=40)
    s.add_argument("--left", action="store_true", help="only what the porter does not handle yet")
    s.set_defaults(func=report)
    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
