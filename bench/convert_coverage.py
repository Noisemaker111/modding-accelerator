#!/usr/bin/env python3
"""How much of each corpus mod's loader API the convert stand-ins and rules already cover, in seconds, without a build.

    python bench/convert_coverage.py refs CLASSES_DIR [--out FILE]   what one compiled mod uses (run by convert_sweep --references)
    python bench/convert_coverage.py                                coverage per mod and the ranked to-do list
    python bench/convert_coverage.py --json                         the same as JSON

A mod's references come from its own compiled classes (the untouched original, built once on the cloud runner by
bench/convert_sweep.py run --references): every class, method and field of the loader (Fabric API and Loader, or NeoForge,
FancyModLoader and its event bus) the bytecode names, and every method it calls on a game class. They are kept in
coverage/convert-references.json, with the commit each was read from. What the other side provides is read from the repo:
the bridge for the mod's game version (knowledge/bridges, through knowledge/loaders.json bridges[] and their layers) and the
classes and members the loader rules rewrite (knowledge/loaders.json symbols). A member counts as covered when its class is
rewritten by a rule, or the bridge declares a member of that name on that class (or the class is a bridge class whose
members are not listed). The result ranks every missing class and member by how many mods need it.

Covered is a prediction, not a result: on the round-5 corpus, 9 of the 10 mods whose loader classes were all covered
compiled after conversion. The game check (convert_sweep) remains the proof.
"""
import argparse
import json
import re
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import loader_api  # noqa: E402

REFERENCES = ROOT / "coverage" / "convert-references.json"
CORPUS = ROOT / "bench" / "convert-sweep" / "corpus.json"
LOADER = {
    "fabric": ("net/fabricmc/fabric/api/", "net/fabricmc/loader/api/", "net/fabricmc/api/"),
    "neoforge": ("net/neoforged/neoforge/", "net/neoforged/fml/", "net/neoforged/bus/", "net/neoforged/api/"),
}
GAME = ("net/minecraft/", "com/mojang/")


# ---- what a compiled mod references -----------------------------------------------------------------

def references(data):
    """(classes, members) one class file names: {owner}, {(owner, name, kind)} with kind m(ethod) or f(ield)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set(), set()
    count = struct.unpack(">H", data[8:10])[0]
    pool, refs, i, index = {}, [], 10, 1
    while index < count:
        tag = data[i]
        if tag == 1:
            size = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[index] = data[i + 3:i + 3 + size].decode("utf-8", "replace")
            i += 3 + size
        elif tag == 7:
            pool[index] = ("class", struct.unpack(">H", data[i + 1:i + 3])[0])
            i += 3
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (9, 10, 11):
            owner, nat = struct.unpack(">HH", data[i + 1:i + 5])
            refs.append(("f" if tag == 9 else "m", owner, nat))
            i += 5
        elif tag == 12:
            pool[index] = ("nat", *struct.unpack(">HH", data[i + 1:i + 5]))
            i += 5
        elif tag in (3, 4, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            index += 1
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    classes, members = set(), set()
    for v in pool.values():
        if isinstance(v, tuple) and v[0] == "class" and isinstance(pool.get(v[1]), str):
            classes.add(pool[v[1]].split("[L")[-1].rstrip(";"))
    for kind, owner, nat in refs:
        o, n = pool.get(owner), pool.get(nat)
        if isinstance(o, tuple) and isinstance(n, tuple) and isinstance(pool.get(o[1]), str):
            members.add((pool[o[1]], pool[n[1]], kind))
    return classes, members


def mod_references(folder):
    """What every class file under folder (a mod's build/classes, or jars there) names of the loaders and the game."""
    classes, members = set(), set()
    for f in Path(folder).rglob("*"):
        datas = []
        # Windows refuses paths over 260 characters unless they are given in their \\?\ form.
        long = Path("\\\\?\\" + str(f.resolve())) if sys.platform == "win32" else f
        if f.suffix == ".class":
            datas = [long.read_bytes()]
        elif f.suffix == ".jar":
            with zipfile.ZipFile(f) as jar:
                datas = [jar.read(e) for e in jar.namelist() if e.endswith(".class")]
        for data in datas:
            try:
                c, m = references(data)
            except (ValueError, IndexError, struct.error):
                continue
            classes |= c
            members |= m
    keep = lambda name: name.startswith(LOADER["fabric"] + LOADER["neoforge"] + GAME)
    return {
        "classes": sorted(c for c in classes if keep(c)),
        "members": sorted(f"{o}#{n}:{k}" for o, n, k in members if keep(o)),
    }


# ---- what the other side provides -------------------------------------------------------------------

def version(v):
    nums = [int(x) for x in re.findall(r"\d+", v or "")[:3]]
    return tuple(nums) if nums else (0,)


DECLARED = re.compile(r"(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|protected)\s+(?:[\w<>\[\],.?\s]+?\s+)?(\w+)\s*(?:\(|=|;)")


def bridge_provides(rules, frm, minecraft):
    """{class (slash form): set of member names, or None when unknown} the bridge for (from-loader, game version) has; or None."""
    for b in rules["bridges"]:
        if b["from"] != frm:
            continue
        folders = [ROOT / b["folder"]]
        if version(minecraft) < version(b["minecraft"]):
            layers = [l for l in b.get("layers", []) if version(minecraft) >= version(l["minecraft"]) and (not l.get("below") or version(minecraft) < version(l["below"]))]
            if not layers:
                return None
            folders.append(ROOT / max(layers, key=lambda l: version(l["minecraft"]))["folder"])
        names = {}
        if frm == "neoforge":
            names = {simple.replace(".", "/"): full.replace(".", "/") for full, simple in b.get("classes", {}).items()}
        out, supers = {}, {}
        for folder in folders:
            for p in folder.rglob("*.java"):
                rel = p.relative_to(folder).as_posix()[:-5]
                if "/" not in rel and frm == "fabric" or rel.startswith("mixin/"):
                    continue
                if frm == "fabric":
                    cls = ("net/fabricmc/" + rel) if rel.split("/")[0] in ("fabric", "loader", "api") else rel
                else:
                    cls = names.get(rel.replace("/", "."), names.get(rel)) or names.get(rel.rsplit("/", 1)[-1])
                    if cls is None:
                        continue
                text = p.read_text(encoding="utf-8", errors="replace")
                out.setdefault(cls, set()).update(DECLARED.findall(text))
                header = re.search(r"\b(?:class|interface|record|enum)\s+\w+[^{]*\{", text)
                supers.setdefault(cls, set()).update(re.findall(r"\b([A-Z]\w*)\b", re.sub(r"<[^{]*?>", "", header.group(0)).split(" ", 2)[-1]) if header else [])
        # A member declared by another bridge class it extends or implements (DeferredItem's get is DeferredHolder's).
        by_simple = {c.rsplit("/", 1)[-1].split("$")[-1]: c for c in out}
        for cls in out:
            seen, todo = set(), list(supers.get(cls, ()))
            while todo:
                s = by_simple.get(todo.pop())
                if s and s not in seen and s != cls:
                    seen.add(s)
                    out[cls] |= out[s]
                    todo.extend(supers.get(s, ()))
        return out
    return None


def rule_provides(rules, frm):
    """Classes the loader rules rewrite away (slash form): their uses need no bridge."""
    out = set()
    for kind, r in loader_api.rules_of(rules):
        if kind == "bridges":
            continue
        for s in r.get("symbols", {}).get(frm, []):
            out.add(s.split("|", 1)[1].split("#")[0].replace(".", "/"))
    return out


# ---- coverage ---------------------------------------------------------------------------------------

def coverage(refs, rules, corpus):
    mods = {m["id"]: m for m in corpus["mods"]}
    rows, need = [], defaultdict(set)
    rewritten = {side: rule_provides(rules, side) for side in LOADER}
    for mod_id, r in sorted(refs["mods"].items()):
        mod = mods.get(mod_id)
        if not mod:
            continue
        frm = mod["loader"]
        bridge = bridge_provides(rules, frm, r.get("minecraft", ""))
        prefixes = LOADER[frm]
        used = [c for c in r["classes"] if c.startswith(prefixes)]
        missing = []
        for c in used:
            outer = c.split("$")[0]
            if any(outer == k or outer.startswith(k + "/") or outer.startswith(k + "$") for k in rewritten[frm]):
                continue
            if bridge is not None and (c in bridge or outer in bridge):
                continue
            missing.append(c)
        for m in r["members"]:
            owner, rest = m.split("#", 1)
            name = rest.split(":")[0]
            if not owner.startswith(prefixes) or owner in missing or owner.split("$")[0] in missing:
                continue
            outer = owner.split("$")[0]
            if any(outer == k or outer.startswith(k + "/") for k in rewritten[frm]):
                continue
            declared = (bridge or {}).get(owner)
            if declared is not None and declared and name not in declared and name not in ("<init>", "<clinit>", "values", "valueOf"):
                missing.append(f"{owner}#{name}")
        for x in missing:
            need[x].add(mod_id)
        total = len(used) + sum(1 for m in r["members"] if m.startswith(prefixes))
        rows.append({"mod": mod_id, "from": frm, "minecraft": r.get("minecraft"), "bridge": bridge is not None,
                     "used": total, "missing": len(missing), "covered": round(100 * (total - len(missing)) / total) if total else 100})
    ranked = sorted(((x, sorted(m)) for x, m in need.items()), key=lambda e: (-len(e[1]), e[0]))
    return {"mods": rows, "missing": [{"symbol": x.replace("/", "."), "mods": m} for x, m in ranked]}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", nargs="?", default="check")
    p.add_argument("folder", nargs="?")
    p.add_argument("--out")
    p.add_argument("--json", action="store_true")
    p.add_argument("--top", type=int, default=30)
    a = p.parse_args(argv)
    if a.command == "refs":
        out = mod_references(a.folder)
        text = json.dumps(out, indent=1)
        Path(a.out).write_text(text, encoding="utf-8") if a.out else print(text)
        return 0
    if a.command == "collect":
        # The references the sweep's records hold (run --references), merged into coverage/convert-references.json.
        refs = json.loads(REFERENCES.read_text(encoding="utf-8")) if REFERENCES.is_file() else {"mods": {}}
        refs["about"] = ("The loader API each corpus mod's own compiled classes name (bench/convert_coverage.py refs), read from "
                         "the pinned commit of bench/convert-sweep/corpus.json by convert_sweep.py run --references.")
        added = 0
        for f in Path(a.folder).rglob("*-to-*.json"):
            r = json.loads(f.read_text(encoding="utf-8"))
            if r.get("references", {}).get("compiled"):
                refs["mods"][r["id"]] = {k: v for k, v in r["references"].items() if k in ("commit", "minecraft", "classes", "members")}
                added += 1
        refs["mods"] = dict(sorted(refs["mods"].items()))
        REFERENCES.write_text(json.dumps(refs, indent=1) + "\n", encoding="utf-8")
        print(f"{added} mods' references written to {REFERENCES.relative_to(ROOT)} ({len(refs['mods'])} in all)")
        return 0
    refs = json.loads(REFERENCES.read_text(encoding="utf-8"))
    result = coverage(refs, loader_api.load_rules(), json.loads(CORPUS.read_text(encoding="utf-8")))
    if a.json:
        print(json.dumps(result, indent=1))
        return 0
    for r in sorted(result["mods"], key=lambda r: -r["covered"]):
        print(f"{r['mod']:24} {r['from']:9} {str(r['minecraft']):10} {'bridge' if r['bridge'] else 'NO BRIDGE':9} "
              f"{r['covered']:3}% covered, {r['missing']} missing of {r['used']}")
    print(f"\n{len(result['missing'])} missing classes and members, by how many mods need them:")
    for e in result["missing"][:a.top]:
        print(f"  {len(e['mods']):2}  {e['symbol']}  ({', '.join(e['mods'][:4])}{'...' if len(e['mods']) > 4 else ''})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
