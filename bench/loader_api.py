"""Which loader calls stand in for each other, read from mods that ship for more than one loader.

    python bench/loader_api.py fetch                  check out the pinned sources (network)
    python bench/loader_api.py extract                offline: write coverage/loader/api.json
    python bench/loader_api.py show fabric neoforge   the known counterparts, then the calls mods use most that have none

Goal 4 (change loader) needs to know that FabricLoader.isModLoaded on Fabric is ModList.isLoaded on NeoForge.
Mods that ship for several loaders have already written that down, in two shapes:

    switch   a Stonecutter switch with one loader's code on each side (coverage/stonecutter/switches)
    method   the same method written once per loader, in files that differ only by the loader's name
             (platform/fabric/PlatformImpl.java and platform/neoforge/PlatformImpl.java)

A call is a loader class plus the member used on it (net.fabricmc.loader.api.FabricLoader#isModLoaded). Two calls
are counterparts when, at one switch or one method, each loader's side makes exactly that one loader call ("one"),
or when they are the only two that share a class name and member ("name": Forge and NeoForge mostly differ by
package). A side that makes several loader calls proves nothing about which answers which, so it is not recorded.

The sources are the mined Stonecutter mods (coverage/stonecutter/mods.json, fetched by bench/stonecutter_mine.py)
and the extra ones in coverage/loader/sources.json. The checkouts are untrusted data: only their text is read.
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stonecutter_mine as SM  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "coverage" / "loader"
EXTRA = OUT / "sources.json"
EXTRA_SRC = ROOT / ".runs" / "loader-src"
PREFIX = {"fabric": "net.fabricmc.", "neoforge": "net.neoforged.", "forge": "net.minecraftforge.", "quilt": "org.quiltmc."}
LOADERS = tuple(PREFIX)
IMPORT = re.compile(r"^[ \t]*(?:/\*|//)?[ \t]*import[ \t]+(?:static[ \t]+)?([\w.]+?)(?:\.\*)?[ \t]*(?:;|$|[ \t]+as[ \t]+(\w+))", re.M)
HOPS = {"getInstance", "get", "getCurrent", "INSTANCE"}     # FabricLoader.getInstance().isModLoaded: the call is isModLoaded
NAME_IN_PATH = re.compile(r"neoforge|minecraftforge|fabric|forge|quilt")
MAX_SITES = 3


def loader_of(call):
    return next((l for l, p in PREFIX.items() if call.startswith(p)), None)


def imports(text):
    """{name as the file writes it: loader class} for every loader class the file imports, switched-off imports included."""
    out = {}
    for m in IMPORT.finditer(text):
        parts = m.group(1).split(".")
        if not loader_of(m.group(1)):
            continue
        first = next((i for i, p in enumerate(parts) if p[:1].isupper()), None)
        if first is None:
            continue
        last = first
        while last + 1 < len(parts) and parts[last + 1][:1].isupper():
            last += 1                                       # a nested class: Mod.EventBusSubscriber
        out[m.group(2) or parts[last]] = ".".join(parts[:last + 1])
    return out


def member(code, i):
    """What is used on a class name that ends at i, as text to add to the class: .Nested for a nested class, then
    #member for the first member in the chain that is not just a way to the instance."""
    nested = ""
    while i < len(code) and code[i] == ".":
        m = re.compile(r"\.\s*(\w+)").match(code, i)
        if not m:
            break
        i = m.end()
        name = m.group(1)
        if name == "class":
            break
        if name[:1].isupper() and name != name.upper() and name not in HOPS:
            nested += "." + name                            # Mod.EventBusSubscriber is a class; FMLPaths.CONFIGDIR is a member
            continue
        if name not in HOPS:
            return nested + "#" + name
        if i < len(code) and code[i] == "(":
            depth = 0
            while i < len(code):
                depth += (code[i] == "(") - (code[i] == ")")
                i += 1
                if depth == 0:
                    break
        while i < len(code) and code[i] in " \t\r\n":
            i += 1
    return nested


def calls(code, names):
    """Every loader call in a piece of code: class#member, or the class alone where it is only named."""
    found = set()
    for m in IMPORT.finditer(code):
        hit = imports(m.group(0))
        found.update(hit.values())
    body = IMPORT.sub("", code)
    for name, cls in names.items():
        for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\b", body):
            found.add(cls + member(body, m.end()))
    return {c for c in found if "#" in c or not any(o.startswith((c + "#", c + ".")) for o in found)}


def simple(call):
    cls, _, mem = call.partition("#")
    return cls.rsplit(".", 1)[-1] + "#" + mem


def matches(sides):
    """[(call, call, "one" | "name")] proven at one place, given each side's set of loader calls."""
    out = []
    for i in range(len(sides)):
        for j in range(i + 1, len(sides)):
            a, b = sides[i], sides[j]
            if len(a) == 1 and len(b) == 1:
                x, y = next(iter(a)), next(iter(b))
                if loader_of(x) != loader_of(y):
                    out.append((x, y, "one"))
                continue
            for x in a:
                same = [y for y in b if simple(y) == simple(x) and loader_of(y) != loader_of(x)]
                if len(same) == 1 and sum(1 for o in a if simple(o) == simple(x)) == 1:
                    out.append((x, same[0], "name"))
    return out


# ---- the two kinds of place -----------------------------------------------------------------------
def switch_sides(row, mod_loaders):
    """[(loaders, code)] for a switch whose branches are told apart by loader alone; None for any other switch."""
    out, seen = [], set()
    for b in row["branches"]:
        cond = b["condition"]
        if cond is None:
            admits = set(mod_loaders) - seen
        else:
            words = [w.lower() for w in re.findall(r"[A-Za-z_]+", cond)]
            if re.search(r"\d|&&", cond) or not words or any(w not in LOADERS + ("forgelike",) for w in words):
                return None
            admits = set()
            for w in words:
                admits |= {"forge", "neoforge"} if w == "forgelike" else {w}
            if cond.strip().startswith("!"):
                admits = set(mod_loaders) - admits
        seen |= admits
        if admits:
            out.append((admits, SM.clean_branch(b["code"])))
    return out if len(out) > 1 else None


def loader_in_path(rel):
    low = rel.lower()
    hit = [l for l in LOADERS if re.search(r"(^|[/._-])" + l + r"([/._-]|$)", low)] or [l for l in ("neoforge", "fabric", "forge", "quilt") if l in Path(low).stem]
    return ("neoforge" if "neoforge" in hit else hit[0]) if hit else None


def twins(files):
    """{path with the loader's name taken out: {loader: path}} for files that exist once per loader."""
    groups = defaultdict(dict)
    for rel in files:
        loader = loader_in_path(rel)
        if loader:
            key = re.sub(r"^[^/]*\*[^/]*/", "", NAME_IN_PATH.sub("*", rel.lower()))     # a leading module folder (fabric/, neoforge/) goes too
            groups[key].setdefault(loader, rel)
    return {k: v for k, v in groups.items() if len(v) > 1}


def source_files(root):
    out = []
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root).as_posix()
        if p.suffix in (".java", ".kt") and p.is_file() and "/build/" not in "/" + rel and not rel.startswith("."):
            out.append(rel)
    return out


def read(root, rel):
    try:
        return (root / rel).read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")
    except OSError:
        return ""


def mine(repo, root, switch_rows=(), mod_loaders=()):
    """(pairs proven in one mod as [(call, call, how, site)], every loader call the mod makes anywhere)."""
    proven, used, texts = [], set(), {}
    files = source_files(root)
    for rel in files:
        text = texts[rel] = read(root, rel)
        names = imports(text)
        if names:
            used |= calls(SM.blank(text), names)
    for row in switch_rows:
        if row["file"] not in texts:
            continue
        names = imports(texts[row["file"]])
        for b in row["branches"]:                           # the branch that is switched off is code the mod ships too
            used |= calls(SM.blank(SM.clean_branch(b["code"])), names)
        sides = switch_sides(row, mod_loaders)
        if not sides:
            continue
        per = [{c for c in calls(code, names) if loader_of(c) in admits} for admits, code in sides]
        for x, y, how in matches(per):
            proven.append((x, y, how, {"repo": repo, "file": row["file"], "where": f"switch at line {row['line']}"}))
    for key, by_loader in sorted(twins(files).items()):
        methods = {}
        for loader, rel in by_loader.items():
            spans, code = SM.method_spans(texts[rel])
            names = imports(texts[rel])
            for start, end, name in spans:
                methods.setdefault(name, {}).setdefault(loader, set()).update(calls(code[start:end], names))
        for name, per in sorted(methods.items()):
            if len(per) > 1:
                for x, y, how in matches(list(per.values())):
                    proven.append((x, y, how, {"repo": repo, "file": by_loader.get(loader_of(x)) or sorted(by_loader.values())[0], "where": f"method {name}"}))
    return proven, used


# ---- sources --------------------------------------------------------------------------------------
def sources():
    """[(repo, commit, checkout, switch rows, loaders)] for every pinned source."""
    out = []
    for mod in SM.load(SM.MANIFEST)["mods"]:
        mined = SM.OUT / "switches" / (SM.slug(mod["repo"]) + ".json")
        data = SM.load(mined) if mined.is_file() else {}
        out.append((mod["repo"], mod.get("commit"), SM.SRC / SM.slug(mod["repo"]), data.get("rows", []), data.get("loaders", mod.get("loaders", []))))
    for extra in SM.load(EXTRA)["sources"]:
        out.append((extra["repo"], extra["commit"], EXTRA_SRC / SM.slug(extra["repo"]), [], extra.get("loaders", [])))
    return out


def fetch():
    SM.fetch()
    EXTRA_SRC.mkdir(parents=True, exist_ok=True)
    for extra in SM.load(EXTRA)["sources"]:
        dest = EXTRA_SRC / SM.slug(extra["repo"])
        if (dest / ".git").is_dir() and SM.git("rev-parse", "HEAD", cwd=dest) == extra["commit"]:
            continue
        if not (dest / ".git").is_dir():
            dest.mkdir(parents=True, exist_ok=True)
            SM.git("init", "-q", cwd=dest)
            SM.git("remote", "add", "origin", f"https://github.com/{extra['repo']}", cwd=dest)
        SM.git("fetch", "-q", "--depth", "1", "origin", extra["commit"], cwd=dest)
        SM.git("checkout", "-q", "--force", "FETCH_HEAD", cwd=dest)
        print("checked out", extra["repo"], extra["commit"][:10])


def catalogue(mined):
    """The file's content from [(repo, commit, proven, used)]."""
    pairs, users = {}, defaultdict(set)
    for repo, _commit, proven, used in mined:
        for call in used:
            users[call].add(repo)
        for x, y, how, site in proven:
            a, b = sorted((x, y), key=lambda c: (LOADERS.index(loader_of(c)), c))
            p = pairs.setdefault((a, b), {"mods": set(), "how": set(), "sites": []})
            p["mods"].add(repo)
            p["how"].add(how)
            if site not in p["sites"]:
                p["sites"].append(site)
    counterparts = [{"a": a, "b": b, "loaders": [loader_of(a), loader_of(b)], "mods": len(p["mods"]), "how": sorted(p["how"]),
                     "sites": sorted(p["sites"], key=lambda s: (s["repo"], s["file"], s["where"]))[:MAX_SITES]}
                    for (a, b), p in pairs.items()]
    counterparts.sort(key=lambda c: (c["loaders"], -c["mods"], c["a"], c["b"]))
    known = defaultdict(set)
    for c in counterparts:
        known[c["a"]].add(c["loaders"][1])
        known[c["b"]].add(c["loaders"][0])
    used = {l: [{"call": call, "mods": len(repos), "counterpart_on": sorted(known[call])}
                for call, repos in sorted(users.items(), key=lambda kv: (-len(kv[1]), kv[0])) if loader_of(call) == l and len(repos) > 1]
            for l in LOADERS}
    return {
        "about": "Loader calls that stand in for each other, read from mods that ship for more than one loader (bench/loader_api.py). "
                 "counterparts: two calls proven at one switch or one method, where each loader's side made that one loader call (how: one) or they alone share "
                 "a class name and member (how: name). used: every loader call two or more of these mods make, and the loaders a counterpart is known on.",
        "sources": [{"repo": repo, "commit": commit} for repo, commit, _p, _u in mined],
        "counterparts": counterparts,
        "used": {l: v for l, v in used.items() if v},
    }


def extract():
    mined, missing = [], []
    for repo, commit, root, rows, mod_loaders in sources():
        if not root.is_dir():
            missing.append(repo)
            continue
        proven, used = mine(repo, root, rows, mod_loaders)
        mined.append((repo, commit, proven, used))
    if missing:
        print(f"Not checked out, run fetch first: {', '.join(missing)}", file=sys.stderr)
        return 1
    data = catalogue(mined)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "api.json").write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"coverage/loader/api.json: {len(data['counterparts'])} counterparts from {len(mined)} sources.")
    return 0


def coverage(data, frm, to):
    """(calls on frm that two or more mods make, how many of them have a counterpart on to)."""
    calls_ = data["used"].get(frm, [])
    return len(calls_), sum(1 for c in calls_ if to in c["counterpart_on"])


def show(frm, to, limit=25):
    data = SM.load(OUT / "api.json")
    short = lambda c: c[len(PREFIX[loader_of(c)]):]
    rows = [(c["a"], c["b"], c) if c["loaders"] == [frm, to] else (c["b"], c["a"], c) for c in data["counterparts"] if set(c["loaders"]) == {frm, to}]
    for title, group in ((f"{frm} -> {to}, written the same way in two or more mods:", [r for r in rows if r[2]["mods"] > 1]),
                         ("Seen in one mod. A lead to check against that mod's code, not yet a fact:", [r for r in rows if r[2]["mods"] == 1])):
        print(f"{title} {len(group)}")
        for a, b, c in sorted(group, key=lambda r: (-r[2]["mods"], r[0])):
            print(f"  {short(a)}  ->  {short(b)}   ({c['mods']} mod{'s' if c['mods'] > 1 else ''}, {c['sites'][0]['repo']}: {c['sites'][0]['where']})")
    total, covered = coverage(data, frm, to)
    print(f"\nOf the {total} {frm} calls that two or more mods make, {covered} have a {to} counterpart above. The most used without one:")
    for c in [c for c in data["used"].get(frm, []) if to not in c["counterpart_on"]][:limit]:
        print(f"  {short(c['call'])}   ({c['mods']} mods)")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch")
    sub.add_parser("extract")
    s = sub.add_parser("show")
    s.add_argument("frm", choices=LOADERS)
    s.add_argument("to", choices=LOADERS)
    s.add_argument("--limit", type=int, default=25)
    args = ap.parse_args(argv)
    if args.cmd == "fetch":
        return fetch() or 0
    if args.cmd == "extract":
        return extract()
    return show(args.frm, args.to, args.limit)


if __name__ == "__main__":
    sys.exit(main())
