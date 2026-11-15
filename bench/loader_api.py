"""Checks every class and member the loader-change rules name against the loaders' own published jars.

    python bench/loader_api.py verify     download the pinned jars (network), check every symbol, write coverage/loader/api.json
    python bench/loader_api.py show       the rules, what each moves, and where its symbols were found

knowledge/loaders.json holds the rules modaccel convert applies (written for this project, never read from
other people's mods). Each rule lists its symbols as "artifact|class#member", where artifact is a key of the
file's "published" table: Fabric Loader, Fabric API, NeoForge, FancyModLoader, NeoForge's event bus, or
Minecraft itself. verify downloads those exact releases from their own Maven repositories (Minecraft from
Mojang's version manifest), reads the class files, and records each symbol with the jar it was found in and
that jar's SHA-256. A symbol it cannot find fails the run. The record is coverage/loader/api.json; offline,
python bench/test_loader_api.py checks that the record still covers every rule as it is written now.
"""
import argparse
import hashlib
import io
import json
import struct
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "knowledge" / "loaders.json"
OUT = ROOT / "coverage" / "loader"
CACHE = ROOT / ".runs" / "loader-api"
KINDS = ("entrypoints", "expressions", "types", "events", "statements", "integrations")
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def load_rules(path=RULES):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def rules_of(data):
    """Every rule, in file order, with its kind."""
    return [(kind, rule) for kind in KINDS for rule in data.get(kind, [])]


def symbols(data):
    """{rule id: [artifact|class#member, ...]} for every rule."""
    return {rule["id"]: [s for side in ("fabric", "neoforge") for s in rule.get("symbols", {}).get(side, [])] for _, rule in rules_of(data)}


def split(symbol):
    artifact, rest = symbol.split("|", 1)
    cls, _, member = rest.partition("#")
    return artifact, cls, member


# ---- class files ------------------------------------------------------------------------------------

def class_members(data):
    """(class name, {field and method names}) from one .class file's bytes."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pool, i, index = {}, 10, 1
    while index < count:
        tag = data[i]
        if tag == 1:
            size = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[index] = ("utf8", data[i + 3:i + 3 + size].decode("utf-8", "replace"))
            i += 3 + size
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = ("ref", struct.unpack(">H", data[i + 1:i + 3])[0])
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
    this_class = struct.unpack(">H", data[i + 2:i + 4])[0]
    name = pool[pool[this_class][1]][1].replace("/", ".")
    i += 6
    interfaces = struct.unpack(">H", data[i:i + 2])[0]
    i += 2 + 2 * interfaces
    members = set()
    for _ in range(2):                                   # fields, then methods
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            members.add(pool[struct.unpack(">H", data[i + 2:i + 4])[0]][1])
            attributes = struct.unpack(">H", data[i + 6:i + 8])[0]
            i += 8
            for _ in range(attributes):
                i += 6 + struct.unpack(">I", data[i + 2:i + 6])[0]
    return name, members


def index_jar(data, into, depth=0):
    """Adds every class in a jar (and the jars nested in META-INF/jars, as Fabric API ships its modules) to into."""
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in jar.namelist():
            if entry.endswith(".class") and not entry.startswith("META-INF/versions/"):
                try:
                    name, members = class_members(jar.read(entry))
                except (ValueError, KeyError, IndexError, struct.error):
                    continue
                into.setdefault(name, set()).update(members)
            elif depth == 0 and entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                index_jar(jar.read(entry), into, depth + 1)
    return into


# ---- the published jars -----------------------------------------------------------------------------

def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "modaccel-loader-api"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def jar_url(entry):
    """Where one "published" entry's jar lives: Maven coordinates, or Mojang's manifest for minecraft."""
    if entry.get("manifest"):
        versions = json.loads(get(MANIFEST))["versions"]
        meta = json.loads(get(next(v["url"] for v in versions if v["id"] == entry["version"])))
        return meta["downloads"]["client"]["url"]
    group, artifact, version, *classifier = entry["coordinate"].split(":")
    name = f"{artifact}-{version}" + (f"-{classifier[0]}" if classifier else "") + ".jar"
    return f"{entry['maven']}/{group.replace('.', '/')}/{artifact}/{version.replace('+', '%2B')}/{name}"


def fetch(key, entry):
    url = jar_url(entry)
    CACHE.mkdir(parents=True, exist_ok=True)
    file = CACHE / (key + "-" + url.rsplit("/", 1)[1].replace("%2B", "+"))
    if not file.is_file():
        file.write_bytes(get(url))
    data = file.read_bytes()
    return url, hashlib.sha256(data).hexdigest(), data


def verify():
    data = load_rules()
    published = data["published"]
    wanted = {split(s)[0] for ids in symbols(data).values() for s in ids}
    missing_keys = wanted - set(published)
    if missing_keys:
        raise SystemExit(f"symbols name artifacts that knowledge/loaders.json does not publish: {sorted(missing_keys)}")
    artifacts, indexes = {}, {}
    for key in sorted(wanted):
        url, sha, jar = fetch(key, published[key])
        artifacts[key] = {"coordinate": published[key].get("coordinate", "minecraft:client:" + published[key].get("version", "")), "url": url, "sha256": sha}
        indexes[key] = index_jar(jar, {})
        print(f"{key}: {url} ({len(indexes[key])} classes)")
    found, missing = {}, []
    for rule, names in symbols(data).items():
        found[rule] = []
        for symbol in names:
            artifact, cls, member = split(symbol)
            members = indexes[artifact].get(cls)
            ok = members is not None and (not member or member in members)
            if not ok:
                missing.append(symbol)
            found[rule].append({"symbol": symbol, "found": ok})
    out = {
        "about": "Every class and member a rule in knowledge/loaders.json names, looked up in the loaders' own published jars by python bench/loader_api.py verify. artifacts: the exact jars read, with their SHA-256. rules: each rule's symbols and whether the jar has them.",
        "rules_sha256": hashlib.sha256(RULES.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
        "artifacts": artifacts,
        "rules": found,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "api.json").write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    total = sum(len(v) for v in found.values())
    print(f"coverage/loader/api.json: {total - len(missing)} of {total} symbols found in {len(artifacts)} published jars.")
    if missing:
        raise SystemExit("missing from the published jars:\n  " + "\n  ".join(missing))


def show():
    data = load_rules()
    record = json.loads((OUT / "api.json").read_text(encoding="utf-8")) if (OUT / "api.json").is_file() else {"rules": {}}
    for kind, rule in rules_of(data):
        fabric, neo = rule.get("fabric"), rule.get("neoforge")
        what = fabric.get("event") if isinstance(fabric, dict) and "event" in fabric else fabric if isinstance(fabric, str) else rule["id"]
        to = neo.get("event") if isinstance(neo, dict) and "event" in neo else neo if isinstance(neo, str) else ""
        seen = record["rules"].get(rule["id"], [])
        print(f"{kind:11} {rule['id']:24} {sum(s['found'] for s in seen)}/{len(seen)} symbols found  {what}  ->  {to}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("verify")
    sub.add_parser("show")
    args = ap.parse_args(argv)
    verify() if args.cmd == "verify" else show()


if __name__ == "__main__":
    sys.exit(main())
