#!/usr/bin/env python3
"""How much of each corpus mod's loader API the convert stand-ins and rules already cover, in seconds, without a build.

    python bench/convert_coverage.py refs CLASSES_DIR [--out FILE]   what one compiled mod uses (run by convert_sweep --references)
    python bench/convert_coverage.py                                coverage per mod and the ranked to-do list
    python bench/convert_coverage.py --json                         the same as JSON
    python bench/convert_coverage.py real [--loader neoforge --version 1.21.1] [JARS_DIR]
                                                                    the same over the most-downloaded published mods
                                                                    (python bench/exposure.py fetch): how many use nothing
                                                                    the bridge lacks, and what to add next, in the order
                                                                    that clears the most mods soonest

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
import io
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
    "forge": ("net/minecraftforge/",),
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


def class_files(path):
    """The bytes of every class file under a folder (a mod's build/classes, or jars there), or in one jar."""
    for f in ([Path(path)] if Path(path).is_file() else Path(path).rglob("*")):
        # Windows refuses paths over 260 characters unless they are given in their \\?\ form.
        long = Path("\\\\?\\" + str(f.resolve())) if sys.platform == "win32" else f
        if f.suffix == ".class":
            yield long.read_bytes()
        elif f.suffix == ".jar":
            with zipfile.ZipFile(f) as jar:
                for e in jar.namelist():
                    if e.endswith(".class"):
                        yield jar.read(e)


def mod_references(path):
    """What a mod's class files (a folder of them or of jars, or one jar) name of the loaders and the game."""
    classes, members, own = set(), set(), {}
    for data in class_files(path):
        try:
            c, m = references(data)
        except (ValueError, IndexError, struct.error):
            continue
        classes |= c
        members |= m
        try:
            name, supers, declared = class_header(data)
            own[name] = (supers, set(declared))
        except (ValueError, IndexError, KeyError, struct.error):
            pass
    keep = lambda name: name.startswith(sum(LOADER.values(), ()) + GAME)

    # A member the mod reaches through its own class (this.modLoc() in a subclass of NeoForge's BlockStateProvider) is
    # the first loader or game class's up its supertypes that the mod does not declare it in.
    def declared_by_mod(owner, name, seen):
        # Anywhere in the mod's own types above owner, its interfaces included (IFramedBlock's default getCamoDrops).
        if owner in seen or owner not in own:
            return False
        seen.add(owner)
        supers, declared = own[owner]
        return name in declared or any(declared_by_mod(s, name, seen) for s in supers)

    def inherited(owner, name, seen):
        if owner in seen or owner not in own:
            return []
        seen.add(owner)
        supers, declared = own[owner]
        if not seen - {owner} and declared_by_mod(owner, name, set()):
            return []
        if name in declared:
            return []
        # The superclass only: an interface's method would be the class's own declaration's.
        if not supers:
            return []
        return [supers[0]] if keep(supers[0]) else inherited(supers[0], name, seen)
    for o, n, k in list(members):
        if o in own and n not in ("<init>", "<clinit>"):
            for loader_class in inherited(o, n, set()):
                members.add((loader_class, n, k))
    return {
        "classes": sorted(c for c in classes if keep(c)),
        "members": sorted(f"{o}#{n}:{k}" for o, n, k in members if keep(o)),
    }


# ---- what the other side provides -------------------------------------------------------------------

def version(v):
    nums = [int(x) for x in re.findall(r"\d+", v or "")[:3]]
    return tuple(nums) if nums else (0,)


# A member declaration: modifiers (interface members need none), type parameters, a type, the name, then ( = or ;.
DECLARED = re.compile(
    r"(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|protected|static|final|abstract|default|synchronized|native)\s+)*"
    r"(?:<[^;{}()]*?>\s+)?(?:@[\w.]+\s+)*(?!return\b|new\b|throw\b|else\b|case\b)[\w.$]+(?:<[^;{}()]*?>)?(?:\[\])*\s+(\w+)\s*(?:\(|=|;)"
)
# An enum constant: a name alone on its line (with arguments or a body), which the declaration pattern does not see.
ENUM_CONSTANT = re.compile(r"(?m)^\s*([A-Z][A-Z0-9_]*)\s*(?:\([^;{}]*\))?\s*(?:,|;|\{)?\s*(?://.*)?$")


def declared(text):
    """The member names a source file declares, an enum's constants included (TriState.TRUE read as missing without)."""
    names = set(DECLARED.findall(text))
    if re.search(r"\benum\s+\w+", text):
        names.update(ENUM_CONSTANT.findall(text))
    return names


# Java's own supertypes a bridge class may extend or implement: their members are the JDK's, so any name counts.
JDK = {"Iterable", "Comparable", "Runnable", "Supplier", "Consumer", "Function", "Predicate", "AutoCloseable", "Closeable", "Collection", "List", "Map", "Set", "Record", "Enum"}


def bridge_provides(rules, frm, minecraft):
    """{class (slash form): set of member names, or None when unknown} the bridge for (from-loader, game version) has; or None."""
    for b in rules["bridges"]:
        if b["from"] != frm:
            continue
        folders = [ROOT / b["folder"]]
        drops = []
        if version(minecraft) < version(b["minecraft"]):
            layers = [l for l in b.get("layers", []) if version(minecraft) >= version(l["minecraft"]) and (not l.get("below") or version(minecraft) < version(l["below"]))]
            if not layers:
                return None
            layer = max(layers, key=lambda l: version(l["minecraft"]))
            folders.append(ROOT / layer["folder"])
            drops = layer.get("drops", [])
        names = {}
        if frm == "neoforge":
            names = {simple.replace(".", "/"): full.replace(".", "/") for full, simple in b.get("classes", {}).items()}
        out, supers = {}, {}
        for folder in folders:
            for p in folder.rglob("*.java"):
                rel = p.relative_to(folder).as_posix()[:-5]
                # As convert reads them: the base folder's version layers are read only as the layer; a layer's drops go.
                if folder == folders[0] and rel[:1].isdigit() or any((rel + ".java").startswith(d) for d in drops):
                    continue
                if "/" not in rel and frm == "fabric" or rel.startswith("mixin/"):
                    continue
                if frm == "fabric":
                    cls = ("net/fabricmc/" + rel) if rel.split("/")[0] in ("fabric", "loader", "api") else rel
                else:
                    # A class of the bridge's own (an injected interface, a helper) keeps its path in the bridge.
                    cls = names.get(rel.replace("/", "."), names.get(rel)) or names.get(rel.rsplit("/", 1)[-1]) or "bridge/" + rel
                text = p.read_text(encoding="utf-8", errors="replace")
                out.setdefault(cls, set()).update(declared(text))
                header = re.search(r"\b(?:class|interface|record|enum)\s+\w+[^{]*\{", text)
                supers.setdefault(cls, set()).update(re.findall(r"\b([A-Z]\w*)\b", re.sub(r"<[^{]*?>", "", header.group(0)).split(" ", 2)[-1]) if header else [])
        # NeoForge's own classes convert copies when they compile on Fabric (neoforge-sources.txt, bench/bridge_import.py),
        # with the members they declare there and their supertypes.
        if frm == "neoforge":
            for cls, members in copied_classes(folders, b).items():
                out.setdefault(cls, set()).update(members)
                supers.setdefault(cls, set()).update(COPY_SUPERS.get(cls, set()))
        # A member declared by another bridge class it extends or implements (DeferredItem's get is DeferredHolder's).
        by_simple = {c.rsplit("/", 1)[-1].split("$")[-1]: c for c in out}
        BRIDGE_SUPERS.clear()
        for cls in out:
            BRIDGE_SUPERS[cls] = {x for x in supers.get(cls, ()) if x not in by_simple}
        for cls in out:
            if supers.get(cls, set()) & JDK:
                out[cls].add("*")
            seen, todo = set(), list(supers.get(cls, ()))
            while todo:
                s = by_simple.get(todo.pop())
                if s and s not in seen and s != cls:
                    seen.add(s)
                    out[cls] |= out[s]
                    todo.extend(supers.get(s, ()))
        # A bridge file NeoForge has under two names (a class it moved between versions) provides both.
        if frm == "neoforge":
            for full, simple in b.get("classes", {}).items():
                main = names.get(simple.replace(".", "/"))
                cls = full.replace(".", "/")
                if main and main != cls and main in out:
                    out[cls] = out[main]
                    for nested in [c for c in out if c.startswith(main + "$")]:
                        out[cls + nested[len(main):]] = out[nested]
        return out
    return None


def copied_classes(folders, bridge):
    """{class (slash form): member names} of NeoForge's classes convert copies for this bridge version: the listed ones
    (the layer's neoforge-sources.txt, else the bridge's), each with what its source declares less what does not compile."""
    lists = [f / "neoforge-sources.txt" for f in reversed(folders) if (f / "neoforge-sources.txt").is_file()]
    if not lists:
        return {}
    listed, dropped = [], defaultdict(set)
    for line in lists[0].read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, _, member = line.strip().partition(" ")
        if member:
            dropped[name].add(member.split("#", 1)[-1].split("(")[0])
        else:
            listed.append(name)
    rules = json.loads((ROOT / "knowledge/loaders.json").read_text(encoding="utf-8"))
    key = next((l.get("sources") for l in bridge.get("layers", []) if ROOT / l["folder"] == lists[0].parent and l.get("sources")), bridge.get("sources"))
    coordinate = rules["published"][key]["coordinate"].split(":")[2]
    jars = sorted((ROOT / ".runs/loader-api").glob(f"*{coordinate}-sources.jar"))
    out = {}
    import contextlib
    import zipfile
    # Without NeoForge's sources jar (a fresh checkout) a listed class is taken to provide every member.
    with (zipfile.ZipFile(jars[0]) if jars else contextlib.nullcontext()) as jar:
        for name in listed:
            try:
                text = jar.read(name.replace(".", "/") + ".java").decode("utf-8", "replace") if jar else ""
            except KeyError:
                text = ""
            out[name.replace(".", "/")] = (declared(text) or {"*"}) - dropped.get(name, set())
            header = re.search(r"\b(?:class|interface|record|enum)\s+\w+[^{]*\{", text)
            COPY_SUPERS[name.replace(".", "/")] = set(re.findall(r"\b([A-Z]\w*)\b", re.sub(r"<[^{]*?>", "", header.group(0)).split(" ", 2)[-1])) if header else set()
    return out


COPY_SUPERS = {}
BRIDGE_SUPERS = {}


def by_package(result, blocked):
    """For each package (and each top-level package family) of what the blocked mods miss, the mods that would miss
    nothing if all of it were there."""
    missing = defaultdict(set)
    for x in result["missing"]:
        for mod in x["mods"]:
            if mod in blocked:
                missing[mod].add(package_of(x["symbol"]))
    rows = []
    for level in (lambda p: p, lambda p: ".".join(p.split(".")[:4]) if not p.startswith("game") else p):
        groups = defaultdict(set)
        for mod, packages in missing.items():
            for p in packages:
                groups[level(p)].add(mod)
        for group, mods in groups.items():
            freed = sorted(m for m in mods if {level(p) for p in missing[m]} == {group})
            rows.append((group, sorted(mods), freed))
    measured = {r["mod"] for r in result["mods"]}
    clean = sorted(m for m in blocked & measured if not missing.get(m))
    return {"nothing missing already": clean, "missing": {m: sorted(p) for m, p in sorted(missing.items())},
            "groups": sorted({r[0]: r for r in rows}.values(), key=lambda r: (-len(r[2]), -len(r[1]), r[0]))}


def package_of(symbol):
    if "(added by" in symbol:
        return "game classes: methods the loader adds"
    owner = symbol.split("#")[0].split("$")[0]
    return owner.rsplit(".", 1)[0]


def rule_provides(rules, frm):
    """Classes the loader rules rewrite away (slash form): their uses need no bridge."""
    out = set()
    for kind, r in loader_api.rules_of(rules):
        if kind == "bridges":
            continue
        for s in r.get("symbols", {}).get(frm, []):
            out.add(s.split("|", 1)[1].split("#")[0].replace(".", "/"))
    return out


# ---- what the game itself declares ------------------------------------------------------------------

def class_header(data):
    """(name, [superclass, interfaces...], {member names}) of one class file."""
    name, members = loader_api.class_members(data)
    count = struct.unpack(">H", data[8:10])[0]
    pool, i, index = {}, 10, 1
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
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            index += 1
        index += 1
    cls = lambda ref: pool[pool[ref][1]] if ref else None
    sup = struct.unpack(">H", data[i + 4:i + 6])[0]
    n = struct.unpack(">H", data[i + 6:i + 8])[0]
    ifaces = [cls(struct.unpack(">H", data[i + 8 + 2 * k:i + 10 + 2 * k])[0]) for k in range(n)]
    return name.replace(".", "/"), [s for s in [cls(sup)] + ifaces if s], members


def vanilla(minecraft):
    """{class: [supertypes, member names]} of the game release, cached under .runs/loader-api. From 26.1 the client jar
    is unobfuscated, so it is read as it is; an older release's classes are read from its obfuscated jar (their
    supertypes) and named by Mojang's client mappings (which also give the member names). Without supertypes a method
    a game class inherits from a library (EnumCodec.fieldOf, which is Codec's) read as one the loader added. None when
    the release cannot be read."""
    # Mojang names a release 26.2, never 26.2.0.
    minecraft = re.sub(r"^(\d+\.\d+)\.0$", r"\1", minecraft)
    cache = loader_api.CACHE / f"game-{minecraft}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    out = {}
    try:
        if version(minecraft) >= (26,):
            _, _, data = loader_api.fetch(f"minecraft-{minecraft}", {"manifest": True, "version": minecraft})
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                for e in jar.namelist():
                    if e.endswith(".class") and e.startswith(GAME):
                        name, supers, members = class_header(jar.read(e))
                        out[name] = [supers, sorted(members)]
        else:
            _, _, mappings = loader_api.fetch(f"minecraft-{minecraft}", {"manifest": True, "version": minecraft, "mojangMappings": True})
            _, _, data = loader_api.fetch(f"minecraft-{minecraft}", {"manifest": True, "version": minecraft})
            out = named_game(data, mappings)
    except Exception as e:  # noqa: BLE001 (an unreachable release reads as unknown, never as covered)
        print(f"no game index for {minecraft}: {e}", file=sys.stderr)
        return None
    loader_api.CACHE.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(out), encoding="utf-8")
    return out


def named_game(jar_bytes, mappings):
    """{class: [supertypes, member names]} of an obfuscated release, in Mojang's names: the supertypes from the jar's
    class files, the names from the mappings (a line "net.minecraft.Foo -> abc:" names class abc)."""
    named = {}
    for line in mappings.decode("utf-8").splitlines():
        if line and not line.startswith((" ", "#")) and " -> " in line:
            name, obfuscated = line.rstrip(":").split(" -> ")
            named[obfuscated.strip().replace(".", "/")] = name.strip().replace(".", "/")
    members = loader_api.index_mappings(mappings, {})
    out = {}
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        for e in jar.namelist():
            if not e.endswith(".class"):
                continue
            obfuscated, supers, _ = class_header(jar.read(e))
            name = named.get(obfuscated)
            if name:
                out[name] = [[named.get(s, s) for s in supers], sorted(members.get(name.replace("/", "."), ()))]
    return out


def declared_by_game(game, owner, name):
    """Whether the game declares name on owner or a supertype (with no supertypes known, on any game class)."""
    # Object's and Enum's own methods (EquipmentSlot.ordinal), and names with a $ (a mod's own mixin methods, framedblocks$getCache).
    if game is None or name in OBJECT_METHODS or "$" in name:
        return True
    seen, todo = set(), [owner]
    while todo:
        c = todo.pop()
        if c in seen or c not in game:
            continue
        seen.add(c)
        supers, members = game[c]
        if name in members:
            return True
        if supers is None:
            return any(name in m for _, m in game.values())
        todo.extend(supers)
    if not seen or name in OBJECT_METHODS:
        return True
    # A supertype outside the game other than Java's base classes (a library, a JDK interface) may declare it.
    # Mojang's own libraries (com.mojang.serialization.Codec, Brigadier) are outside the game jar too.
    outside = {s for c in seen for s in (game[c][0] or []) if s and s not in game}
    return bool(outside - {"java/lang/Object", "java/lang/Enum", "java/lang/Record"})


OBJECT_METHODS = {"equals", "hashCode", "toString", "getClass", "clone", "values", "valueOf", "ordinal", "name", "compareTo", "<init>", "<clinit>"}


def extension_names(rules, frm, minecraft):
    """Method names the bridge adds to game classes: NeoForge's I...Extension interfaces, Fabric's injected Fabric... ones."""
    provided = bridge_provides(rules, frm, minecraft) or {}
    pattern = re.compile(r"(^I\w+Extension$)" if frm == "neoforge" else r"(^Fabric\w+$)")
    # The interfaces the bridge's mixins inject into game classes (bridges[].mixins[].injects).
    injected = set()
    for b in rules["bridges"]:
        if b["from"] == frm:
            for mixin in b.get("mixins", []):
                injected |= {v.rsplit(".", 1)[-1] for v in (mixin.get("injects") or {}).values()}
    return {m for c, members in provided.items()
            if pattern.match(c.rsplit("/", 1)[-1].split("$")[-1]) or c.rsplit("/", 1)[-1].split("$")[-1] in injected for m in members}


# ---- coverage ---------------------------------------------------------------------------------------

def proven(refs, sweep):
    """Everything a mod used whose conversion compiled (coverage/sweeps/convert.json): convert handles it, whether by a
    rule, a bridge class or its own code (it drops withTabsBefore, moves getData to attachments ...)."""
    out = set()
    for run in (sweep or {}).get("runs", []):
        if str(run.get("outcome", "")).startswith(("compiles", "loads")) and run["id"] in refs["mods"]:
            r = refs["mods"][run["id"]]
            out |= {c.replace("/", ".") for c in r["classes"]}
            out |= {m.split(":")[0].replace("/", ".") for m in r["members"]}
    return out


def coverage(refs, rules, corpus, sweep=None):
    mods = {m["id"]: m for m in corpus["mods"]}
    rows, need = [], defaultdict(set)
    rewritten = {side: rule_provides(rules, side) for side in LOADER}
    handled = proven(refs, sweep)
    for mod_id, r in sorted(refs["mods"].items()):
        mod = mods.get(mod_id)
        if not mod:
            continue
        frm = mod["loader"]
        bridge = bridge_provides(rules, frm, r.get("minecraft", ""))
        prefixes = LOADER[frm]
        game_index = vanilla(r.get("minecraft", "")) if r.get("minecraft") else None
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
            if declared is not None and declared and "*" not in declared and name not in declared and name not in ("<init>", "<clinit>", "values", "valueOf"):
                # A bridge class that extends a game class (ExtendedButton extends Button) has the game class's members.
                game_supers = [g for g in (game_index or {}) if g.rsplit("/", 1)[-1] in BRIDGE_SUPERS.get(owner, ())]
                if not any(declared_by_game(game_index, g, name) for g in game_supers):
                    missing.append(f"{owner}#{name}")
        # Methods the loader adds to game classes: called on a game class that does not declare them.
        game = vanilla(r.get("minecraft", "")) if r.get("minecraft") else None
        added = extension_names(rules, frm, r.get("minecraft", "")) if game is not None else set()
        extensions = 0
        for m in r["members"]:
            owner, rest = m.split("#", 1)
            name, kind = rest.split(":")
            if kind != "m" or not owner.startswith(GAME) or game is None or declared_by_game(game, owner, name):
                continue
            extensions += 1
            if name not in added:
                missing.append(f"{owner}#{name} (added by {frm})")
        missing = [x for x in missing if x.split(" (added by")[0].replace("/", ".") not in handled]
        for x in missing:
            need[x].add(mod_id)
        total = len(used) + sum(1 for m in r["members"] if m.startswith(prefixes)) + extensions
        rows.append({"mod": mod_id, "from": frm, "minecraft": r.get("minecraft"), "bridge": bridge is not None,
                     "used": total, "missing": len(missing), "covered": round(100 * (total - len(missing)) / total) if total else 100})
    ranked = sorted(((x, sorted(m)) for x, m in need.items()), key=lambda e: (-len(e[1]), e[0]))
    return {"mods": rows, "missing": [{"symbol": x.replace("/", "."), "mods": m} for x, m in ranked]}


def real(jars, loader, minecraft, rules=None):
    """The same coverage over published mod jars (python bench/exposure.py fetch): the mods with nothing missing, and
    what the others miss in the order that clears the most mods soonest. The jars are only read, to count."""
    if loader == "fabric" and version(minecraft) < (26,):
        raise SystemExit("Fabric jars before 26.1 name the game in intermediary names, which this cannot read yet.")
    refs, corpus = {"mods": {}}, {"mods": []}
    for jar in sorted(Path(jars).glob("*.jar")):
        mod = jar.name.split("--")[0]
        # Read as a corpus mod is, so a call through the mod's own subclass reaches the loader class that declares it.
        refs["mods"][mod] = {"minecraft": minecraft, **mod_references(jar)}
        corpus["mods"].append({"id": mod, "loader": loader})
    result = coverage(refs, rules or loader_api.load_rules(), corpus)
    left = {r["mod"]: set() for r in result["mods"]}
    for e in result["missing"]:
        for mod in e["mods"]:
            left[mod].add(e["symbol"])
    result["clear"] = sum(1 for s in left.values() if not s)
    # The order that clears mods soonest: a symbol that is the last thing some mod misses first, then the most-used.
    order = []
    while any(left.values()):
        used = Counter(s for mod in left.values() for s in mod)
        last = Counter(next(iter(mod)) for mod in left.values() if len(mod) == 1)
        best = max(sorted(used), key=lambda s: (last.get(s, 0), used[s]))
        for mod in left.values():
            mod.discard(best)
        order.append({"symbol": best, "mods": used[best], "clearAfter": sum(1 for s in left.values() if not s)})
    result["order"] = order
    return result


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", nargs="?", default="check")
    p.add_argument("folder", nargs="?")
    p.add_argument("--out")
    p.add_argument("--json", action="store_true")
    p.add_argument("--top", type=int, default=30)
    p.add_argument("--blocked", help="comma-separated mod ids: rank what they miss by package, and the mods each package frees")
    p.add_argument("--loader", default="neoforge", help="real: the loader the jars were published for")
    p.add_argument("--version", default="1.21.1", help="real: their Minecraft version")
    a = p.parse_args(argv)
    if a.command == "real":
        jars = Path(a.folder) if a.folder else Path.home() / ".mmt" / "exposure" / "jars" / f"{a.loader}-{a.version}"
        if not any(jars.glob("*.jar")):
            raise SystemExit(f"No jars in {jars}: python bench/exposure.py fetch --loader {a.loader} --version {a.version}")
        result = real(jars, a.loader, a.version)
        if a.json:
            print(json.dumps(result, indent=1))
            return 0
        print(f"{len(result['mods'])} mods read ({a.loader} {a.version}): {result['clear']} use nothing the bridge lacks; "
              f"{len(result['order'])} classes and members hold the rest. Next, with the mods clear once each is there:")
        for e in result["order"][:a.top]:
            print(f"  {e['clearAfter']:3} clear  used by {e['mods']:2}  {e['symbol']}")
        return 0
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
    sweep_file = ROOT / "coverage" / "sweeps" / "convert.json"
    sweep = json.loads(sweep_file.read_text(encoding="utf-8")) if sweep_file.is_file() else None
    result = coverage(refs, loader_api.load_rules(), json.loads(CORPUS.read_text(encoding="utf-8")), sweep)
    if a.blocked:
        blocked = set(a.blocked.split(","))
        ranked = by_package(result, blocked)
        if a.json:
            print(json.dumps(ranked, indent=1))
            return 0
        print(f"no references (original not built): {', '.join(sorted(blocked - set(refs['mods'])))}")
        print(f"nothing missing already: {', '.join(ranked['nothing missing already'])}")
        for group, mods, freed in ranked["groups"][:a.top]:
            print(f"  frees {len(freed)}  missed by {len(mods):2}  {group}  freed: {', '.join(freed)}")
        return 0
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
