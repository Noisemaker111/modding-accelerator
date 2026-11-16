"""How much of NeoForge's public API the NeoForge-on-Fabric bridge covers, per NeoForge release the bridge serves.

    python bench/bridge_coverage.py                 the coverage of each release, and its largest gaps
    python bench/bridge_coverage.py --json          the same as JSON
    python bench/bridge_coverage.py --gaps CLASS    every method of one NeoForge class the bridge lacks

The API is read from NeoForge's own published jars (knowledge/loaders.json "published": the universal jar for its
classes, the userdev jar for the patches that add methods to the game's classes). Two numbers per release:
  - mirrored methods: the public methods of the NeoForge classes the bridge mirrors (bridges[].classes) that the
    bridge's file for that game version declares, by name;
  - game-class additions: the public methods NeoForge's patches add to the game's classes that some bridge file for
    that game version declares, by name (the bridge adds them through mixins and interface injection).
Names only: an overload the bridge lacks still counts as present, so the numbers are an upper bound.
"""
import argparse
import io
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import loader_api  # noqa: E402  (fetches and caches the published jars)

ROOT = Path(__file__).resolve().parent.parent
BRIDGE = "neoforge-on-fabric"
# The NeoForge release each game version of the bridge serves ("published" key), and that version's layer, if any.
RELEASES = [("26.3", "neoforge", None), ("26.1", "neoforge-26.1", "26.1"), ("1.21.1", "neoforge-21.1", "1.21.1")]
PUBLIC, SYNTHETIC, BRIDGE_FLAG = 0x0001, 0x1000, 0x0040


def public_methods(data):
    """(class name, is public, {public method names}) from one .class file, constructors and synthetic methods left out."""
    count = struct.unpack(">H", data[8:10])[0]
    pool, i, index = {}, 10, 1
    while index < count:
        tag = data[i]
        if tag == 1:
            size = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[index] = data[i + 3:i + 3 + size].decode("utf-8", "replace")
            i += 3 + size
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = struct.unpack(">H", data[i + 1:i + 3])[0]
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            index += 1
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    access = struct.unpack(">H", data[i:i + 2])[0]
    name = pool[pool[struct.unpack(">H", data[i + 2:i + 4])[0]]].replace("/", ".")
    i += 6
    i += 2 + 2 * struct.unpack(">H", data[i:i + 2])[0]
    methods = set()
    for kind in ("field", "method"):
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            flags = struct.unpack(">H", data[i:i + 2])[0]
            member = pool[struct.unpack(">H", data[i + 2:i + 4])[0]]
            attributes = struct.unpack(">H", data[i + 6:i + 8])[0]
            i += 8
            for _ in range(attributes):
                i += 6 + struct.unpack(">I", data[i + 2:i + 6])[0]
            if kind == "method" and flags & PUBLIC and not flags & (SYNTHETIC | BRIDGE_FLAG) and not member.startswith("<"):
                methods.add(member)
    return name, bool(access & PUBLIC), methods


def neoforge_api(data):
    """{class: {public methods}} for NeoForge's public classes outside its internal packages."""
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in jar.namelist():
            if not (entry.startswith("net/neoforged/neoforge/") and entry.endswith(".class")) or "/internal/" in entry:
                continue
            name, public, methods = public_methods(jar.read(entry))
            if public:
                out[name] = methods
    return out


PATCH_METHOD = re.compile(r"^\+\s+public (?!class |interface |enum |record )[^=;(]*?\b(\w+)\s*\(")


def patch_additions(data):
    """{game class: {public method names NeoForge's patches add}} from the userdev jar's patch files."""
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in jar.namelist():
            if entry.startswith("patches/") and entry.endswith(".java.patch"):
                added = {m.group(1) for line in jar.read(entry).decode("utf-8", "replace").splitlines() if (m := PATCH_METHOD.match(line))}
                if added:
                    out[entry[len("patches/"):-len(".java.patch")].replace("/", ".")] = added
    return out


def bridge_files(rules, layer_version):
    """{relative path: source} of the bridge as convert installs it for that game version (layer files, drops, rewrites)."""
    bridge = next(b for b in rules["bridges"] if b["id"] == BRIDGE)
    folder = ROOT / bridge["folder"]
    layer = next((l for l in bridge.get("layers", []) if l["minecraft"] == layer_version), None) if layer_version else None
    out = {}
    for root in [folder] + ([ROOT / layer["folder"]] if layer else []):
        for f in root.rglob("*.java"):
            rel = f.relative_to(root).as_posix()
            if root == folder and "/" in rel and rel[0].isdigit():
                continue
            out[rel] = f.read_text(encoding="utf-8")
    for rel in [r for r in out if any(r.startswith(d) for d in (layer or {}).get("drops", []))]:
        del out[rel]
    return bridge, out


DECLARED = re.compile(r"\b([a-zA-Z_]\w*)\s*\(")


def measure(rules, version, key, layer_version):
    entry = rules["published"][key]
    _, _, universal = loader_api.fetch(key, entry)
    userdev_entry = dict(entry, coordinate=entry["coordinate"].rsplit(":", 1)[0] + ":userdev")
    _, _, userdev = loader_api.fetch(key + "-userdev", userdev_entry)
    api = neoforge_api(universal)
    additions = patch_additions(userdev)
    bridge, files = bridge_files(rules, layer_version)
    everything = set()
    for text in files.values():
        everything |= set(DECLARED.findall(text))
    mirrored = {}
    for neoforge, rel in bridge["classes"].items():
        path = rel.replace(".", "/") + ".java"
        if neoforge in api and path in files:
            mirrored[neoforge] = (api[neoforge], set(DECLARED.findall(files[path])))
    methods = sum(len(want) for want, _ in mirrored.values())
    present = sum(len(want & have) for want, have in mirrored.values())
    added = sum(len(m) for m in additions.values())
    added_present = sum(len(m & everything) for m in additions.values())
    gaps = sorted(((len(want - have), c, sorted(want - have)) for c, (want, have) in mirrored.items() if want - have), reverse=True)
    game_gaps = sorted(((len(m - everything), c, sorted(m - everything)) for c, m in additions.items() if m - everything), reverse=True)
    return {
        "minecraft": version,
        "neoforge": entry["coordinate"],
        "classes": len(api),
        "mirroredClasses": len(mirrored),
        "mirroredMethods": methods,
        "mirroredMethodsPresent": present,
        "gameClassAdditions": added,
        "gameClassAdditionsPresent": added_present,
        "largestGaps": [{"class": c, "missing": n, "methods": m} for n, c, m in gaps[:15]],
        "largestGameClassGaps": [{"class": c, "missing": n, "methods": m} for n, c, m in game_gaps[:15]],
    }


def class_refs(data):
    """(superclass, {(owner, method) the class calls}, {methods it declares}) from one .class file."""
    count = struct.unpack(">H", data[8:10])[0]
    pool, refs, i, index = {}, [], 10, 1
    while index < count:
        tag = data[i]
        if tag == 1:
            size = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[index] = data[i + 3:i + 3 + size].decode("utf-8", "replace")
            i += 3 + size
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = struct.unpack(">H", data[i + 1:i + 3])[0]
            i += 3
        elif tag in (10, 11):
            refs.append(struct.unpack(">HH", data[i + 1:i + 5]))
            i += 5
        elif tag == 12:
            pool[index] = struct.unpack(">HH", data[i + 1:i + 5])
            i += 5
        elif tag == 15:
            i += 4
        elif tag in (3, 4, 9, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            index += 1
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    calls = set()
    for owner, nat in refs:
        calls.add((pool[pool[owner]].replace("/", "."), pool[pool[nat][0]]))
    i += 4
    super_index = struct.unpack(">H", data[i:i + 2])[0]
    superclass = pool[pool[super_index]].replace("/", ".") if super_index else None
    i += 2
    i += 2 + 2 * struct.unpack(">H", data[i:i + 2])[0]
    declared = set()
    for kind in ("field", "method"):
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            if kind == "method":
                declared.add(pool[struct.unpack(">H", data[i + 2:i + 4])[0]])
            attributes = struct.unpack(">H", data[i + 6:i + 8])[0]
            i += 8
            for _ in range(attributes):
                i += 6 + struct.unpack(">I", data[i + 2:i + 6])[0]
    return superclass, calls, declared


EXTENSION = re.compile(r"^\+.*\bimplements\b.*?(net\.neoforged\.neoforge\.[\w.]*extensions\.I\w+Extension)")


def extended_game_classes(userdev):
    """{game class: [NeoForge extension interfaces its patch makes it implement]}."""
    out = {}
    with zipfile.ZipFile(io.BytesIO(userdev)) as jar:
        for entry in jar.namelist():
            if entry.startswith("patches/") and entry.endswith(".java.patch"):
                found = [m.group(1) for line in jar.read(entry).decode("utf-8", "replace").splitlines() if (m := EXTENSION.match(line))]
                if found:
                    out[entry[len("patches/"):-len(".java.patch")].replace("/", ".")] = found
    return out


def release_of(folder):
    """The bridge release (RELEASES entry) for a mod's game version, read from its gradle.properties, or None."""
    props = folder / "gradle.properties"
    text = props.read_text(encoding="utf-8", errors="replace") if props.is_file() else ""
    game = re.search(r"(?m)^\s*minecraft_version\s*=\s*(\S+)", text)
    neo = re.search(r"(?m)^\s*neo(?:forge)?_version\s*=\s*(\S+)", text)
    version = game.group(1) if game else ""
    if not version and neo:
        major, minor = neo.group(1).split(".")[:2]
        version = f"1.{major}.{minor}" if int(major) < 26 else f"{major}.{minor}"
    if version.startswith("1.21.1") and not version.startswith("1.21.10"):
        return RELEASES[2]
    if version.startswith(("26.1", "26.2")):
        return RELEASES[1]
    if version.startswith("26.3"):
        return RELEASES[0]
    return None


def corpus_use(rules, folders, release=RELEASES[0]):
    """{(class, method): {mods}} for one release's API the bridge lacks that the compiled originals in folders call or override."""
    version, key, layer_version = release
    entry = rules["published"][key]
    _, _, universal = loader_api.fetch(key, entry)
    _, _, userdev = loader_api.fetch(key + "-userdev", dict(entry, coordinate=entry["coordinate"].rsplit(":", 1)[0] + ":userdev"))
    api = neoforge_api(universal)
    additions = patch_additions(userdev)
    extensions = extended_game_classes(userdev)
    bridge, files = bridge_files(rules, layer_version)
    everything = set()
    for text in files.values():
        everything |= set(DECLARED.findall(text))
    lacking = {}
    for neoforge, rel in bridge["classes"].items():
        path = rel.replace(".", "/") + ".java"
        have = set(DECLARED.findall(files[path])) if path in files else set()
        if neoforge in api:
            lacking[neoforge] = api[neoforge] - have
    for game, methods in additions.items():
        lacking[game] = methods - everything
    # A game class's extension interfaces' methods count as its own (Block gets IBlockExtension's).
    overridable = {}
    for game, interfaces in extensions.items():
        overridable[game] = set().union(*(lacking.get(x, api.get(x, set())) for x in interfaces)) | lacking.get(game, set())
    # What convert's rules rewrite counts as covered: each rule names its symbols ("artifact|class#member").
    ruled = set()
    for symbols in loader_api.symbols(rules).values():
        for symbol in symbols:
            name = symbol.split("|", 1)[-1]
            if "#" in name:
                ruled.add(tuple(name.split("#", 1)))

    def handled(owner, name):
        return (owner, name) in ruled or any((x, name) in ruled for x in extensions.get(owner, []))

    use = {}
    for folder in folders:
        mod = folder.name.removesuffix("-original")
        for f in folder.glob("build/classes/**/*.class"):
            try:
                superclass, calls, declared = class_refs(f.read_bytes())
            except (ValueError, KeyError, IndexError, struct.error):
                continue
            for owner, name in calls:
                hit = lacking.get(owner, set()) | overridable.get(owner, set())
                if name in hit and not handled(owner, name):
                    use.setdefault((owner, name), set()).add(mod)
            if superclass in overridable:
                for name in declared & overridable[superclass]:
                    if not handled(superclass, name):
                        use.setdefault((superclass, name), set()).add(mod)
    return use


def percent(part, whole):
    return f"{100 * part / whole:.0f}%" if whole else "-"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--gaps", metavar="CLASS", help="every method of one NeoForge or game class the bridge lacks (26.3)")
    ap.add_argument("--use", action="store_true",
                    help="rank what the bridge lacks by how many compiled NeoForge originals (.runs/convert-sweep/work/*-original) call or override it")
    args = ap.parse_args(argv)
    rules = loader_api.load_rules()
    if args.use:
        corpus = json.loads((ROOT / "bench/convert-sweep/corpus.json").read_text(encoding="utf-8"))
        ids = {m["id"] for m in (corpus["mods"] if isinstance(corpus, dict) else corpus) if m.get("loader") == "neoforge"}
        folders = [ROOT / ".runs/convert-sweep/work" / f"{i}-original" for i in sorted(ids)]
        folders = [f for f in folders if any(f.glob("build/classes/**/*.class"))]
        use, measured = {}, []
        for release in RELEASES:
            mine = [f for f in folders if release_of(f) == release]
            measured += mine
            for key, mods in corpus_use(rules, mine, release).items():
                use.setdefault(key, set()).update(mods)
        skipped = sorted(f.name.removesuffix("-original") for f in folders if f not in measured)
        print(f"{len(measured)} compiled NeoForge originals, each against its own release (no bridge release for: {', '.join(skipped) or 'none'}); "
              f"{len(use)} methods the bridge lacks are called or overridden by them")
        for (owner, name), mods in sorted(use.items(), key=lambda x: (-len(x[1]), x[0]))[:40]:
            print(f"  {len(mods):2}  {owner.split('.')[-1]}.{name}  ({', '.join(sorted(mods))})")
        return 0
    if args.gaps:
        result = measure(rules, *RELEASES[0])
        for gap in result["largestGaps"] + result["largestGameClassGaps"]:
            if gap["class"] == args.gaps or gap["class"].endswith("." + args.gaps):
                print("\n".join(gap["methods"]))
                return 0
        print(f"{args.gaps}: no gap among the largest, or not a class the bridge mirrors")
        return 1
    results = [measure(rules, *release) for release in RELEASES]
    if args.json:
        print(json.dumps(results, indent=1))
        return 0
    for r in results:
        print(f"Minecraft {r['minecraft']} ({r['neoforge']}): mirrored methods {r['mirroredMethodsPresent']}/{r['mirroredMethods']} "
              f"({percent(r['mirroredMethodsPresent'], r['mirroredMethods'])}), game-class additions "
              f"{r['gameClassAdditionsPresent']}/{r['gameClassAdditions']} ({percent(r['gameClassAdditionsPresent'], r['gameClassAdditions'])})")
    print("\nLargest gaps (26.3):")
    for gap in results[0]["largestGaps"][:8] + results[0]["largestGameClassGaps"][:8]:
        print(f"  {gap['missing']:3}  {gap['class']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
