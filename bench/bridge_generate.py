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
VERSIONS = [("26.3", "generated", "", "26.3"), ("26.1", "generated261", "26.1", "26.1.2"), ("1.21.1", "generated1211", "1.21.1", "1.21.1")]


def patched_jar(version):
    """A NeoForge-patched game jar of that game version from any original build under .runs (ModDevGradle keeps one)."""
    work = ROOT / ".runs/convert-sweep/work"
    if version == "1.21.1":
        # ModDevGradle 1.x names it neoforge-21.1.N-minecraft.jar.
        jars = [j for j in work.glob("*/build/moddev/artifacts/neoforge-21.1.*-minecraft.jar")]
    else:
        prefix = {"26.3": "26.3.", "26.1": "26.1."}.get(version, version)
        jars = work.glob(f"*/build/moddev/artifacts/minecraft-patched-{prefix}*.jar")
    jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-merged.jar"))]
    # The newest release, a full release before a beta.
    jars.sort(key=lambda j: ("beta" not in j.name, [int(x) for x in re.findall(r"\d+", re.split(r"patched-|neoforge-", j.name)[1])]))
    if not jars:
        raise SystemExit(f"no NeoForge-patched game jar for {version} under .runs/convert-sweep/work: build a NeoForge original first")
    return jars[-1]


def plain_jar(game_version):
    """The game jar in Mojang's names without NeoForge: Mojang's own jar from 26.1 on (unobfuscated); for 1.21.1 the
    Mojang-named jar Loom made for a Fabric build (bench/bridge_check.py --minecraft 1.21.1 makes one)."""
    if not game_version.startswith("1."):
        loader_api.fetch(f"minecraft-{game_version}", {"manifest": True, "version": game_version})
        return loader_api.CACHE / f"minecraft-{game_version}-client.jar"
    jars = sorted((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged").glob(f"{game_version}-loom.mappings.*/*.jar"),
                  key=lambda j: j.stat().st_mtime)
    # A layered mapping can be Yarn's too (another project's build): only a jar with the game's Mojang names will do.
    import zipfile

    def mojang(jar):
        try:
            with zipfile.ZipFile(jar) as z:
                return "net/minecraft/server/level/ServerLevel.class" in z.namelist()
        except (OSError, zipfile.BadZipFile):
            return False
    jars = [j for j in jars if not j.name.endswith("-sources.jar") and mojang(j)]
    if not jars:
        raise SystemExit(f"no Mojang-named {game_version} game jar in Loom's cache: run bench/bridge_check.py --minecraft {game_version} first")
    return jars[-1]


METHOD = re.compile(r"^\s*(?:public|protected) (?:default |static |final |abstract |synchronized )*(?:<([^>]+(?:<[^>]*>)?[^>]*)> )?(.+?) (\w+)\((.*)\)(?: throws [\w.$, ]+)?;$")


PROTECTED = set()


def javap(classpath, classes):
    """{class: [(type params, return type, name, [parameter types], is static)]} of public and protected methods
    (a subclass in a mod calls or overrides a protected one); (class, name) of each protected one goes in PROTECTED."""
    # In batches: a Windows command line holds about 32,000 characters.
    out = "".join(subprocess.run(["javap", "-protected", "-cp", str(classpath)] + classes[i:i + 300], capture_output=True, text=True).stdout
                  for i in range(0, len(classes), 300))
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
            if line.lstrip().startswith("protected "):
                PROTECTED.add((current, m.group(3)))
    return found


def supertypes(classpath, classes):
    """{class: every supertype, transitively} from the jar's own class headers (javap)."""
    direct, todo = {}, list(classes)
    while todo:
        batch = [c for c in dict.fromkeys(todo) if c not in direct]
        todo = []
        if not batch:
            break
        out = "".join(subprocess.run(["javap", "-cp", str(classpath)] + batch[i:i + 300], capture_output=True, text=True).stdout
                      for i in range(0, len(batch), 300))
        for c in batch:
            direct[c] = []
        for line in out.splitlines():
            head = re.match(r"^\S.*?\b(?:class|interface|enum|record) ([\w.$]+)(.*)\{$", line)
            if not head:
                continue
            rest = head.group(2)
            while "<" in rest:
                rest = re.sub(r"<[^<>]*>", "", rest)
            supers = re.findall(r"[\w.$]+", re.sub(r"\b(?:extends|implements|permits)\b", ",", rest.split(" permits ")[0]))
            direct[head.group(1)] = [x for x in supers if x not in ("java.lang.Object",) and not x.startswith("java.")]
            todo += direct[head.group(1)]
    closure = {}

    def walk(c, seen):
        for x in direct.get(c, []):
            if x not in seen:
                seen.append(x)
                walk(x, seen)
        return seen
    for c in classes:
        closure[c] = walk(c, [])
    return closure


def public_classes(classpath, classes):
    """The classes, of those named, that the jar declares public (a nested class may be private on the plain game)."""
    out = "".join(subprocess.run(["javap", "-cp", str(classpath)] + classes[i:i + 300], capture_output=True, text=True).stdout
                  for i in range(0, len(classes), 300))
    return {m.group(1) for line in out.splitlines() if (m := re.match(r"^public .*?(?:class|interface|enum|record) ([\w.$]+)", line))}


def copy_list(layer, neoforge_version):
    """NeoForge's classes convert copies for this version ({NeoForge name: name in the bridge}), the members each one
    loses ({name: {member names}}), and a reader of their source from NeoForge's sources jar."""
    path = (FOLDER / layer if layer else FOLDER) / "neoforge-sources.txt"
    copied, dropped = {}, {}
    for line in path.read_text(encoding="utf-8").splitlines() if path.is_file() else []:
        if not line.strip() or line.startswith("#"):
            continue
        name, _, member = line.strip().partition(" ")
        if member:
            dropped.setdefault(name, set()).add(member.split("#", 1)[-1].split("(")[0])
        else:
            copied[name] = "neoforge." + name[len("net.neoforged.neoforge."):]
    jars = list(loader_api.CACHE.glob(f"*{neoforge_version}-sources.jar"))

    def source(name):
        if not jars:
            return ""
        import zipfile
        with zipfile.ZipFile(jars[0]) as jar:
            try:
                return jar.read(name.replace(".", "/") + ".java").decode("utf-8", "replace")
            except KeyError:
                return ""
    return copied, dropped, source


DECLARATION = re.compile(r"(?m)^\s+(?:(?:public|protected|default|static|abstract|final|synchronized)\s+)*(?:<[^>]+>\s+)?[\w.<>\[\]?, ]+?\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\s*[;{]")


def interface_names(interface, mirrored, files, source, dropped):
    """The method names an extension interface declares on Fabric: the bridge's file, or NeoForge's own copy without
    the members it loses there."""
    if interface in mirrored:
        text = files.get(mirrored[interface].replace(".", "/") + ".java", "")
        return set(DECLARATION.findall(text))
    return set(DECLARATION.findall(source(interface))) - dropped.get(interface, set())


def opened_by_patches(game_jar, games, game_methods, vanilla_methods, patched_protected, vanilla_protected):
    """Access transformer lines ("public CLASS NAME(DESCRIPTOR)") for the methods NeoForge's patches make public that the
    game declares protected, with each one's descriptor from javap -s of the patched jar."""
    wanted = {}
    for game in games:
        vanilla = {(n, tuple(p)) for _tp, _r, n, p, _s in vanilla_methods.get(game, []) if (game, n) in vanilla_protected}
        for _tp, _r, name, params, _s in game_methods.get(game, []):
            if (game, name) not in patched_protected and (name, tuple(params)) in vanilla:
                wanted.setdefault(game, set()).add(name)
    lines = []
    classes = sorted(wanted)
    for i in range(0, len(classes), 200):
        text = subprocess.run(["javap", "-public", "-s", "-cp", str(game_jar)] + classes[i:i + 200], capture_output=True, text=True).stdout
        current, name = None, None
        for line in text.splitlines():
            head = re.match(r".*?(?:class|interface|enum|record) ([\w.$]+)", line)
            if head and line.rstrip().endswith("{"):
                current = head.group(1)
                continue
            m = re.match(r"\s+public .*?(\w+)\(.*\);$", line)
            if m:
                name = m.group(1)
                continue
            d = re.match(r"\s+descriptor: (\(.*)$", line)
            if d and current in wanted and name in wanted[current]:
                lines.append(f"public {current} {name}{d.group(1)}")
            name = None if d else name
    return sorted(set(lines))


def is_interface(name, mirrored, files, source):
    """Whether Fabric's copy of a NeoForge type (the bridge's file or NeoForge's own) is an interface (NeoForge's
    Entity also extends its AttachmentHolder class, which no interface can extend)."""
    text = files.get(mirrored[name].replace(".", "/") + ".java", "") if name in mirrored else source(name)
    return re.search(r"\binterface\s+" + re.escape(name.split(".")[-1]) + r"\b", text) is not None


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


OBJECT_METHODS = {("toString", ()), ("hashCode", ()), ("equals", ("java.lang.Object",))}
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
    # What the hand-written bridge gives a game class or its supertypes, and what Fabric API itself injects into game
    # classes (by name: a generated default of the same name would clash with Fabric's), is not generated again.
    fabric = set(fabric_injected_names(rules))
    hand_written = bc.injected_names(bridge, files, layer, generated=False)
    # Only classes this game version's bridge has (a layer-only class is not there).
    mirrored = {k: v for k, v in bridge["classes"].items() if v.replace(".", "/") + ".java" in files}
    # NeoForge's own classes that compile on Fabric (bench/bridge_import.py), which convert copies into the mod when
    # its code or a generated file it gets names them: their types can be used too.
    copied, dropped, sources = copy_list(layer, entry["coordinate"].split(":")[2])
    available = dict(copied)
    available.update(mirrored)
    package = "${package}"
    universal_jar = loader_api.CACHE / next(p.name for p in loader_api.CACHE.glob(key + "-neoforge-*-universal.jar"))
    game_jar = patched_jar(version)
    vanilla_jar = plain_jar(game_version)
    games = sorted(set(additions) | set(extensions))
    game_methods = javap(game_jar, games)
    ancestors = supertypes(vanilla_jar, sorted(set(games) | set(hand_written)))
    # A hand-written method on a subclass also rules out a generated one on its supertype: the subclass would inherit
    # both defaults (a conflict when the method is called).
    below = {}
    for c in hand_written:
        for a in ancestors.get(c, []):
            below.setdefault(a, set()).update(hand_written[c])
    # The game's own methods, its classes' inherited ones included: a generated default with the signature of an
    # inherited game method would conflict with it (ItemStack's getMaxStackSize comes from ItemInstance).
    patched_protected = set(PROTECTED)
    PROTECTED.clear()
    vanilla_methods = javap(vanilla_jar, sorted(set(games) | {a for xs in ancestors.values() for a in xs}))
    vanilla_protected = set(PROTECTED)
    PROTECTED.update(patched_protected)
    opened = opened_by_patches(game_jar, games, game_methods, vanilla_methods, patched_protected, vanilla_protected)
    listing = "".join(subprocess.run(["javap", "-cp", str(vanilla_jar)] + games[i:i + 300], capture_output=True, text=True).stdout
                      for i in range(0, len(games), 300))
    interfaces = set(re.findall(r"(?m)^\S.*?\binterface ([\w.$]+)", listing))
    interface_methods = javap(universal_jar, sorted({x for xs in extensions.values() for x in xs}))
    nested = sorted({n for ms in list(game_methods.values()) + list(interface_methods.values()) for m in ms for t in [m[1]] + m[3]
                     for n in re.findall(r"[\w.]+\$[\w$]+", t)})
    public_nested = public_classes(vanilla_jar, nested) | public_classes(universal_jar, nested)
    out, blocked, mixins, written = {}, {}, [], {}
    for game in games:
        have = fabric.union(below.get(game, set()), *(hand_written.get(c, set()) for c in [game] + ancestors.get(game, [])))
        vanilla, vanilla_sigs = {}, set()
        for _tp, ret, name, params, static in vanilla_methods.get(game, []):
            # A default can call the game's public method, not its protected one.
            if not static and (game, name) not in vanilla_protected:
                vanilla.setdefault(name, []).append((ret, params))
        for c in [game] + ancestors.get(game, []):
            for _tp, ret, name, params, static in vanilla_methods.get(c, []):
                vanilla_sigs.add((name, tuple(params)))
        # NeoForge's extension interfaces the game class implements, when Fabric has them (the bridge's or NeoForge's own
        # copy), are the generated interface's supertypes, and their methods are not declared again: a mod interface
        # extending IBlockExtension next to Block's generated one would otherwise inherit two unrelated defaults.
        supers, given = [], set()
        hand = fabric.union(*(hand_written.get(c, set()) for c in [game] + ancestors.get(game, [])))
        for interface in extensions.get(game, []):
            if interface not in available:
                continue
            names = interface_names(interface, mirrored, files, sources, dropped)
            if interface not in mirrored:
                continue
            # Only the bridge's own interfaces: NeoForge's copies of several of a class's interfaces declare the same
            # defaults, which two supertypes may not both give.
            if is_interface(interface, mirrored, files, sources) and not names & hand:
                supers.append(source_type(interface, available, package))
            given |= names
        have = have | given
        wanted = []
        for _tp, ret, name, params, static in game_methods.get(game, []):
            if name in additions.get(game, set()) and name not in have and not static and (name, tuple(params)) not in vanilla_sigs:
                wanted.append((_tp, ret, name, params))
        for interface in extensions.get(game, []):
            for _tp, ret, name, params, static in interface_methods.get(interface, []):
                if name not in have and not static and (name, tuple(params)) not in vanilla_sigs:
                    wanted.append((_tp, ret, name, params))
        methods, seen = [], set()
        for tp, ret, name, params in wanted:
            sig = (name, tuple(params))
            # An interface cannot give a default for Object's own methods (toString, equals, hashCode).
            if sig in seen or sig in OBJECT_METHODS:
                continue
            seen.add(sig)
            types = [source_type(t, available, package) for t in [ret] + params + ([tp] if tp else [])]
            declared = set(re.findall(r"\b([A-Z]\w*)\b(?: extends|,|$)", tp or ""))
            if None not in types and any(unnameable(t, declared, public_nested) for t in [ret] + params + ([tp] if tp else [])):
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
        if not methods and not supers:
            continue
        simple = game.split(".")[-1].replace("$", "")
        iface = f"{simple}Generated"
        target = game.replace(".", "/")
        written[game] = (iface, methods, supers)
        mixin = f"{iface}Mixin"
        out[f"mixin/{GENERATED}/{mixin}.java"] = (
            f"package {package}.mixin.{GENERATED};\n\n"
            f"import {package}.{GENERATED}.{iface};\n"
            f"import org.spongepowered.asm.mixin.Mixin;\n\n"
            f"/** Generated by bench/bridge_generate.py: gives {game.replace('$', '.')} the methods of {iface}. Do not edit. */\n"
            f"@Mixin(targets = \"{game}\")\n"
            + (f"public interface {mixin} extends {iface} {{}}\n" if game in interfaces else f"public abstract class {mixin} implements {iface} {{}}\n")
        )
        declared_names = {m.split("(")[0].split()[-1] for m in methods}
        names = sorted(declared_names | given)
        entry_ = {
            "mixin": f"{GENERATED}.{mixin}",
            "generated": True,
            "client": game.startswith("net.minecraft.client."),
            "uses": [["\\b(?:" + "|".join(names) + ")\\s*\\("]],
            "injects": {target: f"{GENERATED}.{iface}"},
        }
        # Methods protected on the game class are public in the interface: convert makes a mod's override of one public.
        hidden = sorted(n for n in declared_names if (game, n) in PROTECTED)
        if hidden:
            entry_["protected"] = hidden
        mixins.append(entry_)
    # A game class's generated interface extends its game supertypes' (LivingEntityGenerated extends EntityGenerated),
    # so a method both declare (ILivingEntityExtension overrides IEntityExtension's) resolves to the subclass's.
    for game, (iface, methods, supers) in written.items():
        parents = [written[a][0] for a in ancestors.get(game, []) if a in written] + supers
        extends = f" extends {', '.join(parents)}" if parents else ""
        out[f"{GENERATED}/{iface}.java"] = (
            f"package {package}.{GENERATED};\n\n"
            f"/**\n * Generated by bench/bridge_generate.py from NeoForge {entry['coordinate'].split(':')[2]}: the methods NeoForge adds to\n"
            f" * {game.replace('$', '.')} that the hand-written bridge lacks, so mods' overrides and calls compile. Do not edit.\n */\n"
            f"public interface {iface}{extends} {{\n\n" + "\n\n".join(methods) + "\n}\n"
        )
    count = sum(t.count("    default ") for p, t in out.items() if p.startswith(GENERATED))
    print(f"{version}: {count} methods on {len(mixins)} game classes; left out (a NeoForge type the bridge lacks): "
          f"{sum(len(v) for v in blocked.values())} on {len(blocked)} classes")
    out["patchaccess.cfg"] = (
        f"# Generated by bench/bridge_generate.py from NeoForge {entry['coordinate'].split(':')[2]}: game methods NeoForge's patches make\n"
        "# public that the game keeps protected. An interface cannot give them, so convert opens the ones the code names in\n"
        "# the mod's access widener, as it does NeoForge's own access transformer lines. Do not edit.\n"
        + "".join(line + "\n" for line in opened)
    )
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
    entries, layer_entries = [], {}
    for (version, (out, mixins)), (_v, name, where, _game) in zip(results, VERSIONS):
        root = FOLDER / where if where else FOLDER
        for old in list(root.glob(f"{name}/*.java")) + list(root.glob(f"mixin/{name}/*.java")):
            old.unlink()
        for rel, text in out.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        # A layer with its own mixin list (it replaces the bridge's) gets its version's entries; the others share the bridge's.
        layer = next((l for l in b.get("layers", []) if where and l["minecraft"] == where), None)
        if layer is not None and "mixins" in layer:
            layer_entries[where] = mixins
        else:
            entries += mixins
    # Each version's own generated files only: a layer leaves out the other versions' (its drops).
    names = [name for _v, name, _w, _g in VERSIONS]
    for layer in b.get("layers", []):
        own = next((name for _v, name, where, _g in VERSIONS if where == layer["minecraft"]), None)
        drops = [d for d in layer.get("drops", []) if not any(d in (f"{n}/", f"mixin/{n}/") for n in names)]
        drops += [d for n in names if n != own for d in (f"{n}/", f"mixin/{n}/")]
        layer["drops"] = drops
    b["mixins"] = [m for m in b["mixins"] if not m.get("generated")] + entries
    for layer in b.get("layers", []):
        if layer["minecraft"] in layer_entries:
            layer["mixins"] = [m for m in layer["mixins"] if not m.get("generated")] + layer_entries[layer["minecraft"]]
    (ROOT / "knowledge/loaders.json").write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
