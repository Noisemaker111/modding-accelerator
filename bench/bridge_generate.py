"""Generates the NeoForge-on-Fabric bridge's missing game-class methods from NeoForge's own published jars.

    python bench/bridge_generate.py            write the generated files and their mixin entries (26.3)
    python bench/bridge_generate.py --check    say what would be generated, write nothing

NeoForge adds methods to the game's classes two ways: its patches declare them on the class (BlockEntity, Entity ...),
and its extension interfaces (IBlockExtension, IItemExtension ...) give them to the classes it patches to implement
them. Every such method the bridge lacks (bench/bridge_coverage.py) is written here, for each game class, as a default
method of one interface (generated/<Class>Generated.java) that a mixin (generated/<Class>GeneratedMixin.java) adds
to the game class and Loom injects for the compile. A mod's override of the method then compiles and a mod's call of
it compiles.

Signatures come from NeoForge's compiled classes (javap), never from its source. Bodies are written by this script:
  - a method that is a richer form of a game method of the same name and return type (more parameters, the game
    method's parameters among them in order) calls that game method, as NeoForge's own default does;
  - otherwise a void method does nothing, and any other method throws, naming itself, so a call that needs real
    behaviour fails where it happens instead of answering wrongly. Overrides are unaffected.
A method whose signature names a NeoForge class the bridge does not mirror is left out and listed.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bridge_coverage as bc  # noqa: E402
import loader_api  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
FOLDER = ROOT / "knowledge/bridges/neoforge-on-fabric"
GENERATED = "generated"
# (game version, its folder of generated files, where they go: the bridge folder or its layer, the plain game version)
VERSIONS = [("26.3", "generated", "", "26.3"), ("26.1", "generated261", "26.1", "26.1.2")]


def patched_jar(version):
    """A NeoForge-patched game jar of that game version from any original build under .runs (ModDevGradle keeps one)."""
    prefix = {"26.3": "26.3.", "26.1": "26.1."}.get(version, version)
    jars = (ROOT / ".runs/convert-sweep/work").glob(f"*/build/moddev/artifacts/minecraft-patched-{prefix}*.jar")
    jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-merged.jar"))]
    # The newest release, a full release before a beta.
    jars.sort(key=lambda j: ("beta" not in j.name, [int(x) for x in re.findall(r"\d+", j.name.split("patched-")[1])]))
    if not jars:
        raise SystemExit(f"no NeoForge-patched game jar for {version} under .runs/convert-sweep/work: build a NeoForge original first")
    return jars[-1]


METHOD = re.compile(r"^\s*public (?:default |static |final |abstract |synchronized )*(?:<([^>]+(?:<[^>]*>)?[^>]*)> )?(.+?) (\w+)\((.*)\)(?: throws [\w.$, ]+)?;$")


def javap(classpath, classes):
    """{class: [(type params, return type, name, [parameter types], is static)]} of public methods."""
    out = subprocess.run(["javap", "-public", "-cp", str(classpath)] + classes, capture_output=True, text=True).stdout
    found, current = {}, None
    for line in out.splitlines():
        head = re.match(r".*?(?:class|interface|enum) ([\w.$]+)", line)
        if head and line.rstrip().endswith("{"):
            current = head.group(1)
            found[current] = []
            continue
        m = METHOD.match(line)
        if current and m:
            params = split_params(m.group(4))
            found[current].append((m.group(1), m.group(2), m.group(3), params, " static " in f" {line} "))
    return found


def public_classes(classpath, classes):
    """The classes, of those named, that the jar declares public (a nested class may be private on the plain game)."""
    out = subprocess.run(["javap", "-cp", str(classpath)] + classes, capture_output=True, text=True).stdout
    return {m.group(1) for line in out.splitlines() if (m := re.match(r"^public .*?(?:class|interface|enum|record) ([\w.$]+)", line))}


def split_params(text):
    out, depth, cur = [], 0, ""
    for c in text:
        depth += c == "<"
        depth -= c == ">"
        if c == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += c
    if cur.strip():
        out.append(cur.strip())
    return out


def source_type(t, mirrored, package):
    """A javap type as Java source in the bridge (nested classes dotted, NeoForge classes as the bridge's), or None."""
    t = t.replace("$", ".")
    for neoforge in re.findall(r"net\.neoforged\.[\w.]+", t):
        target = mirrored.get(neoforge)
        if target is None:
            return None
        t = t.replace(neoforge, package + "." + target)
    return t


def fabric_injected_names(rules):
    """Method names of Fabric API's Fabric* interfaces (FabricBlock, FabricItem ...), which Fabric injects into game
    classes: a generated default of the same name would clash with Fabric's ("inherits unrelated defaults")."""
    import io
    import zipfile
    _, _, data = loader_api.fetch("fabric-api", rules["published"]["fabric-api"])
    folder = loader_api.CACHE / "fabric-api-modules"
    folder.mkdir(parents=True, exist_ok=True)
    jars, classes = [], []
    with zipfile.ZipFile(io.BytesIO(data)) as outer:
        for name in outer.namelist():
            if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                path = folder / name.rsplit("/", 1)[1]
                if not path.is_file():
                    path.write_bytes(outer.read(name))
                jars.append(path)
                with zipfile.ZipFile(path) as inner:
                    classes += [n[:-6].replace("/", ".") for n in inner.namelist()
                                if re.match(r"net/fabricmc/fabric/api/.*/Fabric[A-Z]\w*\.class$", n)]
    listing = subprocess.run(["javap", "-public", "-cp", ";".join(map(str, jars)) if sys.platform == "win32" else ":".join(map(str, jars))] + classes,
                             capture_output=True, text=True).stdout
    names, inside = set(), False
    for line in listing.splitlines():
        if line.rstrip().endswith("{"):
            inside = " interface " in f" {line} "
        elif inside and (m := re.search(r"\s(\w+)\(", line)):
            names.add(m.group(1))
    return names


PRIMITIVES = {"void", "boolean", "byte", "char", "short", "int", "long", "float", "double"}


def unnameable(t, declared, public_nested):
    """Whether a javap type uses a class's own type parameter or a nested class that is not public."""
    for token in re.findall(r"[\w.$]+", t):
        if token in PRIMITIVES or token in declared or token in ("extends", "super"):
            continue
        if "." not in token:
            return True
        if "$" in token and token not in public_nested:
            return True
    return False


def body(game, ret, name, params, vanilla):
    """The default method's body: the game's own method of that name when this is a richer form of it, else nothing or a throw."""
    for v_ret, v_params in vanilla.get(name, []):
        if v_ret != ret or len(v_params) >= len(params):
            continue
        picks, i = [], 0
        for p in v_params:
            while i < len(params) and params[i] != p:
                i += 1
            if i == len(params):
                break
            picks.append(f"arg{i}")
            i += 1
        else:
            call = f"(({game}) (Object) this).{name}({', '.join(picks)})"
            return f"{'' if ret == 'void' else 'return '}{call};"
    if ret == "void":
        return ""
    return f'throw new UnsupportedOperationException("NeoForge\'s {game.split(".")[-1]}.{name} has no Fabric counterpart in the bridge yet");'


def generate(rules, version, folder_name, game_version):
    GENERATED = folder_name
    version, key, layer = next(r for r in bc.RELEASES if r[0] == version)
    entry = rules["published"][key]
    _, _, universal = loader_api.fetch(key, entry)
    _, _, userdev = loader_api.fetch(key + "-userdev", dict(entry, coordinate=entry["coordinate"].rsplit(":", 1)[0] + ":userdev"))
    additions = bc.patch_additions(userdev)
    extensions = bc.extended_game_classes(userdev)
    bridge, files = bc.bridge_files(rules, layer)
    # What the hand-written bridge has, and what Fabric API itself injects into game classes, is not generated again.
    have = set(fabric_injected_names(rules))
    for rel, text in files.items():
        if not rel.startswith(("generated", "mixin/generated")):
            have |= set(bc.DECLARED.findall(text))
    # Only classes this game version's bridge has (a layer-only class is not there).
    mirrored = {k: v for k, v in bridge["classes"].items() if v.replace(".", "/") + ".java" in files}
    package = "${package}"
    universal_jar = loader_api.CACHE / next(p.name for p in loader_api.CACHE.glob(key + "-neoforge-*-universal.jar"))
    game_jar = patched_jar(version)
    loader_api.fetch(f"minecraft-{game_version}", {"manifest": True, "version": game_version})
    vanilla_jar = loader_api.CACHE / f"minecraft-{game_version}-client.jar"
    games = sorted(set(additions) | set(extensions))
    game_methods = javap(game_jar, games)
    vanilla_methods = javap(vanilla_jar, games)
    listing = subprocess.run(["javap", "-cp", str(vanilla_jar)] + games, capture_output=True, text=True).stdout
    interfaces = set(re.findall(r"(?m)^\S.*?\binterface ([\w.$]+)", listing))
    interface_methods = javap(universal_jar, sorted({x for xs in extensions.values() for x in xs}))
    nested = sorted({n for ms in list(game_methods.values()) + list(interface_methods.values()) for m in ms for t in [m[1]] + m[3]
                     for n in re.findall(r"[\w.]+\$[\w$]+", t)})
    public_nested = public_classes(vanilla_jar, nested) | public_classes(universal_jar, nested)
    out, blocked, mixins = {}, {}, []
    for game in games:
        vanilla, vanilla_sigs = {}, set()
        for _tp, ret, name, params, static in vanilla_methods.get(game, []):
            vanilla_sigs.add((name, tuple(params)))
            if not static:
                vanilla.setdefault(name, []).append((ret, params))
        wanted = []
        for _tp, ret, name, params, static in game_methods.get(game, []):
            if name in additions.get(game, set()) and name not in have and not static and (name, tuple(params)) not in vanilla_sigs:
                wanted.append((_tp, ret, name, params))
        for interface in extensions.get(game, []):
            for _tp, ret, name, params, static in interface_methods.get(interface, []):
                if name not in have and not static:
                    wanted.append((_tp, ret, name, params))
        methods, seen = [], set()
        for tp, ret, name, params in wanted:
            sig = (name, tuple(params))
            if sig in seen:
                continue
            seen.add(sig)
            types = [source_type(t, mirrored, package) for t in [ret] + params + ([tp] if tp else [])]
            declared = set(re.findall(r"\b([A-Z]\w*)\b(?: extends|,|$)", tp or ""))
            if None not in types and any(unnameable(t, declared, public_nested) for t in [ret] + params):
                types.append(None)
            if None in types:
                blocked.setdefault(game, []).append(name)
                continue
            src_ret, *src_params = types[:1 + len(params)]
            generic = f"<{types[-1]}> " if tp else ""
            # Bodies compare javap's own spelling of the types (the game's methods are all in javap's spelling).
            text = body(game.replace("$", "."), ret.replace("$", "."), name, params, {k: [(r.replace("$", "."), [p.replace("$", ".") for p in ps]) for r, ps in v] for k, v in vanilla.items()})
            args = ", ".join(f"{t} arg{i}" for i, t in enumerate(src_params))
            methods.append(f"    default {generic}{src_ret} {name}({args}) {{\n" + (f"        {text}\n" if text else "") + "    }")
        if not methods:
            continue
        simple = game.split(".")[-1].replace("$", "")
        iface = f"{simple}Generated"
        target = game.replace(".", "/")
        out[f"{GENERATED}/{iface}.java"] = (
            f"package {package}.{GENERATED};\n\n"
            f"/**\n * Generated by bench/bridge_generate.py from NeoForge {entry['coordinate'].split(':')[2]}: the methods NeoForge adds to\n"
            f" * {game.replace('$', '.')} that the hand-written bridge lacks, so mods' overrides and calls compile. Do not edit.\n */\n"
            f"public interface {iface} {{\n\n" + "\n\n".join(methods) + "\n}\n"
        )
        mixin = f"{iface}Mixin"
        out[f"mixin/{GENERATED}/{mixin}.java"] = (
            f"package {package}.mixin.{GENERATED};\n\n"
            f"import {package}.{GENERATED}.{iface};\n"
            f"import org.spongepowered.asm.mixin.Mixin;\n\n"
            f"/** Generated by bench/bridge_generate.py: gives {game.replace('$', '.')} the methods of {iface}. Do not edit. */\n"
            f"@Mixin(targets = \"{game}\")\n"
            + (f"public interface {mixin} extends {iface} {{}}\n" if game in interfaces else f"public abstract class {mixin} implements {iface} {{}}\n")
        )
        names = sorted({m.split("(")[0].split()[-1] for m in methods})
        mixins.append({
            "mixin": f"{GENERATED}.{mixin}",
            "generated": True,
            "client": game.startswith("net.minecraft.client."),
            "uses": [["\\b(?:" + "|".join(names) + ")\\s*\\("]],
            "injects": {target: f"{GENERATED}.{iface}"},
        })
    count = sum(t.count("    default ") for p, t in out.items() if p.startswith(GENERATED))
    print(f"{version}: {count} methods on {len(mixins)} game classes; left out (a NeoForge type the bridge lacks): "
          f"{sum(len(v) for v in blocked.values())} on {len(blocked)} classes")
    return out, mixins


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    rules = loader_api.load_rules()
    results = [(v, generate(rules, v, name, game)) for v, name, _where, game in VERSIONS]
    if args.check:
        return 0
    data = json.loads((ROOT / "knowledge/loaders.json").read_text(encoding="utf-8"))
    b = next(x for x in data["bridges"] if x["id"] == bc.BRIDGE)
    entries = []
    for (version, (out, mixins)), (_v, name, where, _game) in zip(results, VERSIONS):
        root = FOLDER / where if where else FOLDER
        for old in list(root.glob(f"{name}/*.java")) + list(root.glob(f"mixin/{name}/*.java")):
            old.unlink()
        for rel, text in out.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        entries += mixins
    # Each version's own generated files only: a layer leaves out the other versions' (its drops).
    names = [name for _v, name, _w, _g in VERSIONS]
    for layer in b.get("layers", []):
        own = next((name for _v, name, where, _g in VERSIONS if where == layer["minecraft"]), None)
        drops = [d for d in layer.get("drops", []) if not any(d in (f"{n}/", f"mixin/{n}/") for n in names)]
        drops += [d for n in names if n != own for d in (f"{n}/", f"mixin/{n}/")]
        layer["drops"] = drops
    b["mixins"] = [m for m in b["mixins"] if not m.get("generated")] + entries
    (ROOT / "knowledge/loaders.json").write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
