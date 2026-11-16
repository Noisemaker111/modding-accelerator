#!/usr/bin/env python3
"""Generates convert bridge mirrors from Fabric API's own published sources, so only the glue is hand-written.

    python bench/bridge_mirror.py CLASS [CLASS ...] [--bridge fabric-on-neoforge] [--closure] [--write] [--force]
    python bench/bridge_mirror.py --unglued          list every bridge file whose events are not posted yet (exit 1 if any)

CLASS is a Fabric API class (net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents). For each one:
  1. finds the Fabric API module that ships it inside the pinned fabric-api jar of knowledge/loaders.json "published"
     (the jar bench/loader_api.py verify downloads), and downloads that module's -sources.jar from Fabric's Maven;
  2. writes the mirror skeleton (bridge_mirror/Skeleton.java, on the JDK's own parser): Fabric's package under
     ${package}, every name and member kept, event fields and callback interfaces as they are, and whatever uses
     Fabric's internals (impl, mixin) stubbed. Each Event field gets an "// UNGLUED:" line and each stub a "// STUBBED:"
     line: the hand-written glue posts the event from NeoForge's nearest event and deletes the line;
  3. adds the class's public members to the bridge's symbols (fabric-api|class#member), which loader_api.py verify
     checks against the published jar, and a before/after test to bridges[].tests: a Fabric source that registers a
     listener on every event, and the same source on the bridge's package (ConvertBridgeTests converts one into the other).
Without --write the files go to .runs/bridge-mirror/ and loaders.json is left alone; --write puts them in
knowledge/bridges/BRIDGE (never over a hand-written file unless --force). Downloads only Fabric's own published files.
"""
import argparse
import io
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import loader_api  # noqa: E402

ROOT = loader_api.ROOT
SKELETON = Path(__file__).resolve().parent / "bridge_mirror" / "Skeleton.java"
OUT = ROOT / ".runs" / "bridge-mirror"
SOURCES = loader_api.CACHE / "sources"
FABRIC_MAVEN = "https://maven.fabricmc.net"
FABRIC_ROOTS = ("fabric", "loader", "api")


def bridge_rule(rules, bridge_id):
    return next(b for b in rules["bridges"] if b["id"] == bridge_id)


def module_of(api_jar, cls):
    """(module id, version) of the Fabric API module whose nested jar holds cls."""
    path = cls.replace(".", "/") + ".class"
    with zipfile.ZipFile(io.BytesIO(api_jar)) as jar:
        for entry in jar.namelist():
            if not (entry.startswith("META-INF/jars/") and entry.endswith(".jar")):
                continue
            with zipfile.ZipFile(io.BytesIO(jar.read(entry))) as inner:
                if path in inner.namelist():
                    meta = json.loads(inner.read("fabric.mod.json"))
                    return meta["id"], meta["version"]
    raise LookupError(f"{cls} is in no module of the pinned Fabric API jar")


def source_of(module, version, cls):
    """The .java text of cls from the module's published sources jar (cached under .runs/loader-api/sources)."""
    SOURCES.mkdir(parents=True, exist_ok=True)
    name = f"{module}-{version}-sources.jar"
    file = SOURCES / name
    if not file.is_file():
        url = f"{FABRIC_MAVEN}/net/fabricmc/fabric-api/{module}/{version.replace('+', '%2B')}/{name.replace('+', '%2B')}"
        file.write_bytes(loader_api.get(url))
    outer = cls.split("$")[0].replace(".", "/") + ".java"
    with zipfile.ZipFile(file) as jar:
        text = jar.read(outer).decode("utf-8")
        folder = outer.rsplit("/", 1)[0] + "/"
        siblings = [e[len(folder):-5] for e in jar.namelist() if e.startswith(folder) and e.endswith(".java") and "/" not in e[len(folder):]]
    # Classes of the same package need no import: the ones the source names are needed as much as its imports.
    code = re.sub(r'(?s)/\*.*?\*/|//[^\n]*|"(?:[^"\\\n]|\\.)*"', "", text)
    same = [folder[:-1].replace("/", ".") + "." + n for n in siblings if n != outer.rsplit("/", 1)[1][:-5] and re.search(rf"(?<![\w$.]){re.escape(n)}(?![\w$])", code)]
    return text, same


def relative(cls):
    """The bridge file of a Fabric class: net.fabricmc.fabric.api.X becomes fabric/api/X.java."""
    rest = cls[len("net.fabricmc."):] if cls.startswith("net.fabricmc.") else cls
    return rest.replace(".", "/") + ".java"


def mirrored(folder):
    """Every class the bridge mirrors now (as convert reads its files)."""
    out = set()
    for f in Path(folder).rglob("*.java"):
        rel = f.relative_to(folder).as_posix()
        if "/" not in rel or rel.startswith("mixin/"):
            continue
        name = rel[:-5].replace("/", ".")
        out.add("net.fabricmc." + name if name.split(".")[0] in FABRIC_ROOTS else name)
    return out


def test_stub(cls, report):
    """A before/after bridge test: a Fabric source that registers a listener on each event, and it on the bridge's package."""
    simple = cls.rsplit(".", 1)[1]
    lines = []
    for event in report["events"]:
        field, etype = event.split(" ", 1)
        callback = etype[len("Event<"):-1].split("<")[0]
        arity = next((n for c, n in report["callbacks"].items() if c.replace("$", ".").endswith("." + callback) or c.endswith("." + callback)), None)
        if arity is None:
            continue
        params = ", ".join(f"a{i}" for i in range(arity))
        lines.append(f"\t\t{field}.register(({params}) -> {{ }});")
    if not lines:
        return None
    body = "\n".join(lines)
    fabric = f"package demo;\n\nimport {cls};\n\nclass Uses{simple} {{\n\tvoid init() {{\n{body}\n\t}}\n}}\n"
    neo = fabric.replace("import net.fabricmc.", "import demo.fabricbridge.")
    return {"name": f"Uses{simple}", "from": fabric, "to": neo}


def generate(classes, bridge_id, write, force, closure=False):
    """Mirrors classes; with closure, also every Fabric API class they need that the bridge does not mirror yet."""
    reports, todo = {}, list(classes)
    while todo:
        batch = [c for c in dict.fromkeys(todo) if c not in reports]
        if not batch:
            break
        reports.update(generate_once(batch, bridge_id, write, force))
        todo = [n for r in reports.values() for n in r["needs"] if n.startswith("net.fabricmc.fabric.api.")] if closure else []
    return reports


def generate_once(classes, bridge_id, write, force):
    rules = loader_api.load_rules()
    b = bridge_rule(rules, bridge_id)
    if b["from"] != "fabric":
        raise SystemExit("the generator mirrors Fabric API classes (bridges from fabric); NeoForge mirrors are not generated yet")
    api = "fabric-api"
    _, _, api_jar = loader_api.fetch(api, rules["published"][api])
    folder = ROOT / b["folder"]
    known = mirrored(folder) | set(classes)
    OUT.mkdir(parents=True, exist_ok=True)
    known_file = OUT / "mirrored.txt"
    known_file.write_text("\n".join(sorted(known)), encoding="utf-8")
    reports = {}
    for cls in classes:
        try:
            module, version = module_of(api_jar, cls)
        except LookupError as gone:
            # A class of an older Fabric API (a mod on an older game): the pinned release has none to mirror.
            print(f"skipped: {gone}", file=sys.stderr)
            continue
        src = OUT / "sources" / relative(cls)
        src.parent.mkdir(parents=True, exist_ok=True)
        text, same = source_of(module, version, cls)
        src.write_text(text, encoding="utf-8")
        target = (folder if write else OUT / bridge_id) / relative(cls)
        if write and target.is_file() and not force:
            raise SystemExit(f"{target} exists (hand-written?); pass --force to replace it")
        run = subprocess.run(["java", str(SKELETON), str(src), str(target), str(known_file)], capture_output=True, text=True)
        if run.returncode != 0:
            raise SystemExit(f"Skeleton failed on {cls}:\n{run.stderr[-3000:]}")
        report = json.loads(run.stdout)
        report["needs"] += [c for c in same if c not in known]
        report["module"] = f"{module} {version}"
        report["file"] = str(target.relative_to(ROOT))
        reports[cls] = report
        if write:
            symbols = b.setdefault("symbols", {}).setdefault("fabric", [])
            for s in report["symbols"]:
                entry = f"{api}|{s}"
                if entry not in symbols:
                    symbols.append(entry)
            stub = test_stub(cls, report)
            if stub and not any(t.get("name") == stub["name"] for t in b.setdefault("tests", [])):
                b["tests"].append(stub)
    if write:
        loader_api.RULES.write_text(json.dumps(rules, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return reports


def unglued(rules=None):
    """Every bridge file that still has an UNGLUED event or a STUBBED member: a mirror whose events never fire."""
    rules = rules or loader_api.load_rules()
    out = []
    for b in rules["bridges"]:
        for f in sorted((ROOT / b["folder"]).rglob("*.java")):
            for line in f.read_text(encoding="utf-8").splitlines():
                if re.match(r"\s*// (UNGLUED|STUBBED): ", line):
                    out.append(f"{f.relative_to(ROOT).as_posix()}: {line.strip()[3:]}")
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("classes", nargs="*")
    p.add_argument("--bridge", default="fabric-on-neoforge")
    p.add_argument("--write", action="store_true")
    p.add_argument("--force", action="store_true")
    p.add_argument("--unglued", action="store_true")
    p.add_argument("--closure", action="store_true", help="also mirror every Fabric API class the named ones need")
    p.add_argument("--from-sources", nargs="+", metavar="DIR",
                   help="mirror every Fabric API class these mods import that no rule rewrites and the bridge lacks (with --closure)")
    a = p.parse_args(argv)
    if a.unglued:
        left = unglued()
        print("\n".join(left) or "every bridge event is posted")
        return 1 if left else 0
    classes = list(a.classes)
    if a.from_sources:
        classes += wanted(a.from_sources, a.bridge)
        a.closure = True
    if not classes:
        p.error("name at least one Fabric API class, or --from-sources DIR")
    reports = generate(classes, a.bridge, a.write, a.force, a.closure)
    print(json.dumps(reports, indent=1))
    events = sum(len(r["events"]) for r in reports.values())
    stubs = sum(len(r["stubbed"]) for r in reports.values())
    print(f"{len(reports)} classes mirrored; {events} events to glue, {stubs} members stubbed", file=sys.stderr)
    return 0


def wanted(folders, bridge_id):
    """The Fabric API classes the Java sources under folders import, less what the bridge mirrors and what a rule rewrites."""
    rules = loader_api.load_rules()
    known = mirrored(ROOT / bridge_rule(rules, bridge_id)["folder"])
    rewritten = {s.split("|", 1)[1].split("#")[0] for rid, syms in loader_api.symbols(rules).items() for s in syms}
    out = set()
    for folder in folders:
        for f in Path(folder).rglob("*.java"):
            text = f.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r"(?m)^import\s+(net\.fabricmc\.fabric\.api\.[\w.]+?)(?:\.\*)?\s*;", text):
                cls = m.group(1)
                if cls in rewritten or any(cls == k or cls.startswith(k + ".") for k in known):
                    continue
                out.add(cls)
    return sorted(out)


if __name__ == "__main__":
    sys.exit(main())
