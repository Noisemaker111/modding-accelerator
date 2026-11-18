"""The porting worklist for the whole corpus, from what each mod uses, not from its compiler errors.

For every corpus mod checked out by bench/sweep.py, `mmt uses` records each game member the mod names on its own
version (the compiler resolves them), and that list is joined with the game's change map of every jump the corpus
ports it across. The result ranks changed game members by how many ports they block, labelled by what the map says
(renamed, moved, re-typed, replaced: the game's own code shows how; removed: nothing to follow). One row fixed is one
fix for every mod that uses it.

    python bench/uses_corpus.py run [--only ID,ID]     record uses (undoes each clone's port first) and join
    python bench/uses_corpus.py report                 the ranked worklist from the last run
"""

import argparse
import collections
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLONES = ROOT / ".runs/sweep/clones"
OUT = ROOT / ".runs/uses"
MMT = [str(ROOT / ("mmt.cmd" if sys.platform == "win32" else "mmt"))]


def corpus():
    data = json.loads((ROOT / "bench/sweep/corpus.json").read_text(encoding="utf-8"))
    return data["mods"] if isinstance(data, dict) else data


def change_index(path):
    """The map's verdict on a member, keyed as UsedMembers records them (dotted owner, $ nested, JVM descriptor)."""
    m = json.loads(path.read_text(encoding="utf-8"))
    change = {}
    for section, what in (("methods", "renamed"), ("methodsMoved", "moved"), ("signatures", "re-typed")):
        for e in m.get(section, []):
            change.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", what)
    for section in ("rewrites", "chains", "argDefaults", "siblings"):
        for e in m.get(section, []):
            change.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", "replaced")
    for e in m.get("methodsRemoved", []):
        change.setdefault(f"{e.get('owner')}.{e.get('from')}{e.get('desc', '')}", "removed")
    for e in m.get("fields", []):
        change.setdefault(f"{e.get('owner')}.{e.get('from')}", "renamed")
    for e in m.get("fieldsRemoved", []):
        change.setdefault(f"{e.get('owner')}.{e.get('from')}", "removed")
    classes = {}
    for e in m.get("classes", []):
        classes[e.get("from")] = "moved"
    for e in m.get("classRewrites", []):
        classes.setdefault(e.get("from"), "replaced")
    for c in m.get("classesRemoved", []):
        classes.setdefault(c, "removed")
    return change, classes


def verdict(use, change, classes):
    owner, name, kind = use["owner"], use["name"], use["kind"]
    if kind == "type":
        return owner, classes.get(owner)
    member = f"{owner}.{name}" + ("" if kind == "field" else use["desc"])
    what = change.get(member)
    if what is None and owner in classes:
        what = "owner " + classes[owner]
    return member, what


def maps_for(clone, mod_from):
    """Each jump's game map the porter already built in this clone (Mojang names): {to: path}."""
    found = {}
    for p in (clone / ".mmt/portable/maps").glob(f"game-*-mojmap-{mod_from}-*.json"):
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
        cmd = MMT + ["uses", "--root", str(clone), "--json"] + (["--project", mod["project"]] if mod.get("project") else [])
        done = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        uses_file = clone / ".mmt/portable/uses.json"
        if done.returncode != 0 or not uses_file.is_file():
            tail = (done.stdout + done.stderr).strip().splitlines()[-1:] or ["no output"]
            print(f"{mod['id']}: uses not recorded: {tail[0][:160]}", flush=True)
            continue
        uses = json.loads(uses_file.read_text(encoding="utf-8"))["uses"]
        rows = {}
        for to, path in sorted(maps.items()):
            change, classes = change_index(path)
            changed = collections.Counter()
            members = collections.Counter()
            for u in uses:
                member, what = verdict(u, change, classes)
                if what:
                    changed[what] += 1
                    members[(member, what)] += 1
            rows[to] = {"uses": len(uses), "changed": dict(changed),
                        "members": [{"member": m, "change": w, "uses": n} for (m, w), n in members.most_common()]}
        (OUT / f"{mod['id']}.json").write_text(json.dumps({"id": mod["id"], "from": mod["from"], "jumps": rows}, indent=1), encoding="utf-8")
        summary = ", ".join(f"{to}: {sum(r['changed'].values())} changed" for to, r in rows.items())
        print(f"{mod['id']}: {len(uses)} game uses; {summary}", flush=True)


def report(args):
    ports = 0
    member_ports = collections.defaultdict(set)
    member_change = {}
    kinds = collections.Counter()
    for f in sorted(OUT.glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for to, r in d["jumps"].items():
            ports += 1
            for m in r["members"]:
                member_ports[m["member"]].add(f"{d['id']}->{to}")
                member_change[m["member"]] = m["change"]
                kinds[m["change"]] += m["uses"]
    print(f"{ports} ports; changed uses by what the map says: {dict(kinds.most_common())}")
    print("| ports | change | member |\n|---|---|---|")
    for member, where in sorted(member_ports.items(), key=lambda kv: -len(kv[1]))[: args.top]:
        print(f"| {len(where)} | {member_change[member]} | {member[:120]} |")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--only")
    r.set_defaults(func=run)
    s = sub.add_parser("report")
    s.add_argument("--top", type=int, default=40)
    s.set_defaults(func=report)
    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
