#!/usr/bin/env python3
"""Runs mmt convert on a pinned corpus of real mods and ranks what it is missing.

    python bench/convert_sweep.py run [--only ID,ID] [--jobs 2] [--no-compile] [--no-game] [--no-back] [--label NAME] [--record]
    python bench/convert_sweep.py table [--label NAME]     rank the recorded runs again and print the gap table

bench/convert-sweep/corpus.json pins each mod (repository and commit). For each one the runner:
  1. clones the pinned commit once into .runs/convert-sweep/src/ID and copies it to a fresh work folder;
  2. runs mmt convert --to the other loader --json (the compile included unless --dry-run);
  3. classifies every refusal, every item convert names as not converted, and every compiler error
     (with its symbol and location from the compile log) by the loader concept it lacks, using
     bench/convert-sweep/concepts.json;
  4. when the converted mod compiles: starts it in a hidden client (mmt ui-check), reads every
     registry entry in the mod's namespace and the mixins Mixin applied from the mod's own mixin packages
     (mmt eval), stops the game, and does the same for the untouched original. The behaviour check is
     "the converted mod registers what the original does" when the original registers anything, else "the
     same mixins apply to the same game classes"; a mod with neither is "loads, no registry entries to
     compare", never a pass;
  5. when it compiles, converts it back and compiles again (the other direction), and compares each Java
     file with the original.

Per-run records go to .runs/convert-sweep/runs/ID-to-LOADER.json. The ranked gap table and the pass rates
go to coverage/sweeps/convert.json after a run of the whole corpus (or with --record); --label keeps each
sweep's pass rates in its history, so a batch of rules shows its before and after. Launches follow ~/.mmt/preferences.json (hidden and muted).
After each mod, mmt game-stop ends its games and Gradle daemons. The runner never calls a network model: convert's rules are plain data.
"""
import argparse
import concurrent.futures
import datetime
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "bench" / "convert-sweep" / "corpus.json"
CONCEPTS = ROOT / "bench" / "convert-sweep" / "concepts.json"
OUT = ROOT / "coverage" / "sweeps" / "convert.json"
WORK = ROOT / ".runs" / "convert-sweep"
WINDOWS = os.name == "nt"
MMT = [str(ROOT / ("mmt.cmd" if WINDOWS else "mmt"))]
OTHER = {"fabric": "neoforge", "neoforge": "fabric"}
TIMEOUT = {"convert": 3600, "ui-check": 1500, "eval": 300, "game-stop": 300, "fingerprint": 3600}

# Reads every registry entry in one namespace; works on 1.20.2 to 26.x (ResourceLocation or Identifier). Data attachment
# types count as entries of neoforge:attachment_types on both loaders: NeoForge registers them there, Fabric API keeps them
# in its own attachment table, which is read under that name so the two compare.
REGISTRY_SNIPPET = """
java.util.Map<String, java.util.List<String>> out = new java.util.TreeMap<>();
for (var registry : net.minecraft.core.registries.BuiltInRegistries.REGISTRY) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (var key : registry.keySet()) if (key.getNamespace().equals("NAMESPACE")) ids.add(key.getPath());
    if (!ids.isEmpty()) { java.util.Collections.sort(ids); out.put(String.valueOf(registry.key()), ids); }
}
// Data attachment types: NeoForge keeps them in its neoforge:attachment_types registry, Fabric API in its own table.
try {
    java.lang.reflect.Field table = Class.forName("net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl").getDeclaredField("attachmentRegistry");
    table.setAccessible(true);
    java.util.TreeSet<String> ids = new java.util.TreeSet<>(out.getOrDefault("ResourceKey[minecraft:root / neoforge:attachment_types]", java.util.List.of()));
    for (Object id : ((java.util.Map<?, ?>) table.get(null)).keySet()) if (String.valueOf(id).startsWith("NAMESPACE:")) ids.add(String.valueOf(id).substring("NAMESPACE:".length()));
    if (!ids.isEmpty()) out.put("ResourceKey[minecraft:root / neoforge:attachment_types]", new java.util.ArrayList<>(ids));
} catch (ClassNotFoundException notFabric) {
}
return out.toString();
"""

# For a mod that registers nothing: which of its mixins Mixin applied to which game classes (the mixins of a convert
# bridge, which stand in for the other loader's own patches, are not the mod's and are left out). The configs Mixin selected
# live in its active transformer's processor (Mixins.getConfigs() is empty once they are selected). Every target of a
# config whose package is the mod's is loaded (not initialised); a merged method carries @MixinMerged (kept at run time)
# naming its mixin, and an accessor or interface mixin is an interface the target now implements.
MIXIN_SNIPPET = """
java.util.Set<String> out = new java.util.TreeSet<>();
ClassLoader loader = net.minecraft.server.MinecraftServer.class.getClassLoader();
Object transformer = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
java.lang.reflect.Field processorField = transformer.getClass().getDeclaredField("processor");
processorField.setAccessible(true);
Object processor = processorField.get(transformer);
java.lang.reflect.Field configsField = processor.getClass().getDeclaredField("configs");
configsField.setAccessible(true);
for (Object item : (java.util.List<?>) configsField.get(processor)) {
    var config = (org.spongepowered.asm.mixin.extensibility.IMixinConfig) item;
    String pkg = config.getMixinPackage();
    if (pkg == null || !(PACKAGES)) continue;
    // A convert bridge's own mixins are the other loader's hooks (NeoForge's patches, Fabric API's mixins), not the mod's.
    if (pkg.contains(".loaderbridge.") || pkg.contains(".fabricbridge.")) continue;
    for (String target : config.getTargets()) {
        String name = target.replace('/', '.');
        try {
            Class<?> c = Class.forName(name, false, loader);
            for (var m : c.getDeclaredMethods()) {
                var merged = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                if (merged != null && merged.mixin().startsWith(pkg)) out.add(merged.mixin().substring(pkg.length()) + " > " + name);
            }
            for (var i : c.getInterfaces()) if (i.getName().startsWith(pkg)) out.add(i.getName().substring(pkg.length()) + " > " + name);
        } catch (Throwable t) {
            out.add("not loaded: " + name);
        }
    }
}
return String.join("\\n", out);
"""


def extended(path):
    """Windows paths past 260 characters need the extended-length prefix (deep generated resources, convert backups)."""
    path = os.path.abspath(path)
    prefix = "\\\\?\\"
    return prefix + path if WINDOWS and not path.startswith(prefix) else path


def rmtree(path):
    def unlock(func, p, _):
        try:
            os.chmod(p, stat.S_IWRITE)
            func(p)
        except FileNotFoundError:
            pass
    if Path(path).exists():
        shutil.rmtree(extended(path), onerror=unlock)


def git(args, cwd=None, timeout=1800):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=timeout)


def pristine(mod):
    """The pinned commit, cloned once (no history beyond it)."""
    src = WORK / "src" / mod["id"]
    marker = src / ".mmt-sweep-commit"
    if marker.is_file() and marker.read_text().strip() == mod["commit"]:
        return src
    rmtree(src)
    src.mkdir(parents=True)
    for args in (["init", "-q"], ["config", "core.longpaths", "true"], ["remote", "add", "origin", mod["repo"]],
                 ["fetch", "-q", "--depth", "1", "origin", mod["commit"]], ["checkout", "-q", "FETCH_HEAD"]):
        r = git(args, cwd=src)
        if r.returncode:
            raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()}")
    marker.write_text(mod["commit"])
    return src


def fresh_copy(src, name):
    dest = WORK / "work" / name
    try:
        rmtree(dest)
    except OSError:
        # A Gradle daemon or a game from an earlier run can still hold files there: use a new folder.
        dest = WORK / "work" / f"{name}-{int(time.time())}"
    shutil.copytree(extended(src), extended(dest), ignore=shutil.ignore_patterns(".git", ".gradle", "build", ".mmt-sweep-commit"))
    return dest


def mmt(args, timeout):
    """Runs mmt and returns (exit code, the JSON object it printed last, seconds)."""
    started = time.monotonic()
    try:
        r = subprocess.run(MMT + args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        out, code = r.stdout, r.returncode
    except subprocess.TimeoutExpired as e:
        out = e.stdout.decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return 124, {"error": f"timed out after {timeout} s"}, time.monotonic() - started
    value = None
    for start in [m.start() for m in re.finditer(r"(?m)^\{", out)][::-1]:
        try:
            value = json.loads(out[start:])
            break
        except json.JSONDecodeError:
            continue
    if value is None:
        value = {"error": (out.strip() or r.stderr.strip())[-2000:]}
    elif isinstance(value.get("result"), dict):
        value = value["result"]  # the receipt envelope mmt prints with --json
    return code, value, time.monotonic() - started


# ---- classification ----------------------------------------------------------------------------------

def load_concepts():
    return [(c["concept"], [re.compile(p) for p in c["match"]]) for c in json.loads(CONCEPTS.read_text(encoding="utf-8"))["concepts"]]


def classify(text, concepts):
    for name, patterns in concepts:
        if any(p.search(text) for p in patterns):
            return name
    return None


ERROR = re.compile(r"(?m)^(?:\S.*?)?([^\s:]+\.java):(\d+): error: (.+)$")


def compile_errors(log_text):
    """Every javac error in a Gradle log with the symbol, location and source line javac prints after it."""
    out, seen = [], set()
    matches = list(ERROR.finditer(log_text))
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else min(len(log_text), m.end() + 1500)
        tail = log_text[m.end():end]
        symbol = re.search(r"symbol:\s+(.+)", tail)
        location = re.search(r"location:\s+(.+)", tail)
        source = tail.strip("\n").split("\n")[0].strip() if tail.strip() else ""
        key = (m.group(1), m.group(2), m.group(3))
        if key in seen:
            continue
        seen.add(key)
        out.append({"file": m.group(1).replace("\\", "/"), "line": int(m.group(2)), "message": m.group(3).strip(),
                    "symbol": symbol.group(1).strip() if symbol else "", "location": location.group(1).strip() if location else "",
                    "source": source[:300]})
    return out


def error_concept(error, from_loader, yarn, concepts, own_packages):
    text = " ".join([error["message"], error["symbol"], error["location"], error["source"]])
    concept = classify(text, concepts)
    if concept:
        return concept
    loc, sym = error["location"], error["symbol"]
    game = re.search(r"\bnet\.minecraft\.|\bcom\.mojang\.", loc) or re.search(r"of type (?!.*\b(?:" + "|".join(map(re.escape, own_packages or ["\0"])) + r"))", loc)
    if yarn and (re.search(r"net\.minecraft|com\.mojang", text) or error["message"].startswith(("cannot find symbol", "package net.minecraft"))):
        return classify("yarn-names", concepts)
    if error["message"].startswith("cannot find symbol") and sym.startswith("method") and game:
        return classify("extension-method" if from_loader == "neoforge" else "injected-interface", concepts)
    if error["message"].startswith(("method does not override", "cannot find symbol", "incompatible types", "no suitable method",
                                    "method ", "constructor ", "cannot infer", "is not abstract")):
        if from_loader == "neoforge" and re.search(r"net\.minecraft|com\.mojang", loc + " " + sym):
            return classify("extension-method", concepts)
        if from_loader == "fabric" and re.search(r"net\.minecraft|com\.mojang", loc + " " + sym):
            return classify("injected-interface", concepts)
    return "unclassified: " + (sym or error["message"])[:80]


def refusal_concept(message, work, concepts):
    if "found neither" in message:
        if any((work / "src/main/templates/META-INF/neoforge.mods.toml").is_file() for _ in [0]):
            return classify("templates/META-INF/neoforge.mods.toml", concepts)
        if (work / "settings.gradle").is_file() or (work / "settings.gradle.kts").is_file():
            settings = ""
            for name in ("settings.gradle", "settings.gradle.kts"):
                if (work / name).is_file():
                    settings += (work / name).read_text(encoding="utf-8", errors="replace")
            if re.search(r"\binclude\b", settings):
                return classify("multi-module", concepts)
        gradle = (work / "build.gradle").read_text(encoding="utf-8", errors="replace") if (work / "build.gradle").is_file() else ""
        if re.search(r"srcDirs?\b", gradle) and not (work / "src/main").is_dir():
            return classify("custom-source-sets", concepts)
        return "shape: no loader metadata found"
    return classify(message, concepts) or ("refused: " + message.split(".")[0][:120])


def own_packages(work):
    pkgs = set()
    java = work / "src" / "main" / "java"
    if java.is_dir():
        for d in java.iterdir():
            if d.is_dir():
                for sub in d.iterdir():
                    if sub.is_dir():
                        pkgs.add(f"{d.name}.{sub.name}")
    return sorted(pkgs)


def mod_namespace(work, loader):
    if loader == "fabric":
        f = work / "src/main/resources/fabric.mod.json"
        if f.is_file():
            try:
                return json.loads(f.read_text(encoding="utf-8")).get("id")
            except json.JSONDecodeError:
                pass
    for f in (work / "src/main/resources/META-INF/neoforge.mods.toml", work / "src/main/templates/META-INF/neoforge.mods.toml"):
        if f.is_file():
            m = re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8", errors="replace"))
            if m:
                value = m.group(1)
                if value.startswith("${"):
                    props = (work / "gradle.properties").read_text(encoding="utf-8", errors="replace") if (work / "gradle.properties").is_file() else ""
                    p = re.search(r"(?m)^\s*" + re.escape(value[2:-1]) + r"\s*=\s*(\S+)", props)
                    value = p.group(1) if p else value
                return value
    return None


# ---- one run -----------------------------------------------------------------------------------------

def evaluate(work, name, code_text):
    """Runs a snippet in the project's running game; (value, error)."""
    snippet = WORK / f"{Path(work).name}-{name}.java"
    snippet.write_text(code_text, encoding="utf-8")
    code, ev, _ = mmt(["eval", "--root", str(work), "--file", str(snippet), "--json"], TIMEOUT["eval"])
    if code != 0:
        return None, str(ev.get("error") or ev)[-1000:]
    return (ev.get("value") if isinstance(ev, dict) else None), None


def mixin_snippet(packages):
    test = " || ".join(f'pkg.startsWith("{p}")' for p in packages) or "false"
    return MIXIN_SNIPPET.replace("PACKAGES", test)


# The registry snippet in yarn's names, for the untouched original of a yarn-named mod (its game is in yarn's names).
YARN_SNIPPET_NAMES = [("net.minecraft.core.registries.BuiltInRegistries.REGISTRY", "net.minecraft.registry.Registries.REGISTRY"),
                      ("registry.keySet()", "registry.getIds()"), ("registry.key()", "registry.getKey()")]


def registry_snippet(namespace, yarn=False):
    text = REGISTRY_SNIPPET.replace("NAMESPACE", namespace or "")
    for mojang, named in YARN_SNIPPET_NAMES if yarn else []:
        text = text.replace(mojang, named)
    return text


def yarn_to_mojang_targets(mixins, table_file):
    """An original yarn mod's applied mixins ("Mixin > game class") with the game classes in Mojang's names, from the
    yarn -> Mojang table convert wrote, so they compare with the renamed mod's."""
    if not isinstance(mixins, str) or not Path(table_file).is_file():
        return mixins
    classes = {c["from"]: c["to"] for c in json.loads(Path(table_file).read_text(encoding="utf-8")).get("classes", [])}
    out = []
    for line in mixins.split("\n"):
        mixin, sep, target = line.partition(" > ")
        out.append(mixin + sep + classes.get(target, target) if sep else line)
    return "\n".join(sorted(out))


def game_registries(work, namespace, packages=(), yarn=False):
    """Starts the project in a hidden client, reads its registry entries and its applied mixins, stops the game."""
    record = {}
    code, ui, seconds = mmt(["ui-check", "--root", str(work), "--json"], TIMEOUT["ui-check"])
    record["loaded"] = code == 0
    record["loadSeconds"] = round(seconds, 1)
    if code != 0:
        record["loadError"] = str(ui.get("error") or ui.get("details") or ui)[-1500:]
    else:
        record["registries"], error = evaluate(work, "registries", registry_snippet(namespace, yarn))
        if error:
            record["evalError"] = error
        record["mixins"], error = evaluate(work, "mixins", mixin_snippet(packages))
        if error:
            record["mixinError"] = error
    mmt(["game-stop", "--root", str(work), "--json"], TIMEOUT["game-stop"])
    return record


def behaviour(converted, original):
    """
    The behaviour check of one conversion from both games' records. Registry entries in the mod's namespace are compared
    when the original registers any; a mod that registers nothing is compared by the mixins Mixin applied (the same mixins
    on the same game classes). Nothing to compare on either side is no pass: equal is None.
    """
    a, b = parse_registries(converted.get("registries")), parse_registries(original.get("registries"))
    if a is None or b is None:
        return {"check": "registry entries", "equal": None, "why": "a registry read failed"}
    if sum(len(v) for v in b.values()):
        missing = {k: sorted(set(v) - set(a.get(k, []))) for k, v in b.items() if set(v) - set(a.get(k, []))}
        extra = {k: sorted(set(v) - set(b.get(k, []))) for k, v in a.items() if set(v) - set(b.get(k, []))}
        return {"check": "registry entries in the mod's namespace, converted vs original", "signal": "registries",
                "equal": not missing and not extra, "entries": sum(len(v) for v in b.values()), "missing": missing, "extra": extra}
    if sum(len(v) for v in a.values()):
        return {"check": "registry entries in the mod's namespace, converted vs original", "signal": "registries",
                "equal": False, "entries": 0, "missing": {}, "extra": a}
    mine, theirs = converted.get("mixins"), original.get("mixins")
    if isinstance(mine, str) and isinstance(theirs, str) and theirs.strip():
        x, y = set(mine.split("\n")) - {""}, set(theirs.split("\n")) - {""}
        return {"check": "mixins applied to game classes, converted vs original (the mod registers nothing)", "signal": "mixins",
                "equal": x == y, "mixins": len(y), "missing": sorted(y - x), "extra": sorted(x - y)}
    return {"check": "registry entries", "equal": None, "signal": None,
            "why": "the mod registers nothing in either game and no applied mixins were read, so there is nothing to compare"}


def fingerprint_behaviour(converted, original, name):
    """
    The behaviour definition (FINGERPRINT.md): the original's and the converted mod's fingerprints on the same game
    version, compared on what the mod owns (fingerprint-diff --across-versions: the two loaders' own registries differ).
    equal is None when either fingerprint could not be taken.
    """
    folder = WORK / "fingerprints"
    folder.mkdir(parents=True, exist_ok=True)
    a, b = folder / f"{name}-original.json", folder / f"{name}-converted.json"
    taken = {}
    for side, work, out in (("original", original, a), ("converted", converted, b)):
        code, result, seconds = mmt(["fingerprint", "--root", str(work), "--out", str(out), "--json"], TIMEOUT["fingerprint"])
        taken[side] = {"ok": code == 0 and out.is_file(), "seconds": round(seconds, 1), "error": None if code == 0 else str(result.get("error") or result)[-800:]}
    if not (taken["original"]["ok"] and taken["converted"]["ok"]):
        return {"check": "behaviour fingerprint", "signal": "fingerprint", "equal": None, "taken": taken}
    code, diff, _ = mmt(["fingerprint-diff", str(a), str(b), "--across-versions", "--json"], TIMEOUT["eval"])
    return verdict_from_diff(diff, taken)


def verdict_from_diff(diff, taken=None):
    """A fingerprint-diff answer as a sweep verdict: equal only when something was compared and nothing differed."""
    compared = diff.get("compared", 0) if isinstance(diff, dict) else 0
    equal = bool(diff.get("same")) and compared > 0 if isinstance(diff, dict) and "same" in diff else None
    return {"check": "behaviour fingerprint, the mod's own leaves, converted vs original", "signal": "fingerprint", "equal": equal,
            "compared": compared, "counts": (diff or {}).get("counts"), "sections": (diff or {}).get("sections"), "taken": taken}


def parse_registries(text):
    """{'ResourceKey[minecraft:root / minecraft:item]': [ids]} from the snippet's toString."""
    if not isinstance(text, str):
        return None
    out = {}
    for m in re.finditer(r"(ResourceKey\[[^\]]*\])=\[([^\]]*)\]", text):
        out[m.group(1)] = [s.strip() for s in m.group(2).split(",") if s.strip()]
    return out


def stop(work):
    """Ends the game and the Gradle daemons mmt started for one checkout, so nothing waits out its idle timeout."""
    if work is not None and Path(work).is_dir():
        mmt(["game-stop", "--root", str(work), "--json"], TIMEOUT["game-stop"])


def run_one(mod, args, concepts):
    started = []
    try:
        return run_mod(mod, args, concepts, started)
    finally:
        for work in started:
            stop(work)


def run_mod(mod, args, concepts, started):
    # --names mojang: a yarn-named Fabric mod stays on Fabric and only its names move; the game check then proves
    # the renamed mixins apply to the same game classes and the mod registers what it did.
    names = getattr(args, "names", "")
    to = mod["loader"] if names else OTHER[mod["loader"]]
    record = {"id": mod["id"], "name": mod["name"], "label": args.label, "from": mod["loader"], "to": to, "commit": mod["commit"],
              "features": mod["features"], "started": datetime.datetime.now().isoformat(timespec="seconds")}
    try:
        src = pristine(mod)
    except Exception as e:
        record.update(stage="clone", error=str(e), concepts={"clone failed": 1})
        return record
    work = fresh_copy(src, f"{mod['id']}-to-{to}" + (f"-{names}" if names else ""))
    started.append(work)
    props = (work / "gradle.properties").read_text(encoding="utf-8", errors="replace") if (work / "gradle.properties").is_file() else ""
    yarn = bool(re.search(r"(?m)^\s*yarn_(?:mappings|version)\s*=", props)) and mod["loader"] == "fabric"
    record["yarn"] = yarn
    convert_args = ["convert", "--root", str(work), "--to", to, "--json"] + (["--names", names] if names else []) + (["--no-compile"] if args.no_compile else [])
    code, result, seconds = mmt(convert_args, TIMEOUT["convert"])
    record["convertSeconds"] = round(seconds, 1)
    record["exit"] = code
    # convert renames a yarn-named mod to Mojang's names first; its compiler errors are then not about yarn's names.
    if yarn and "yarn-to-mojang" in (result.get("rulesApplied") or {}):
        yarn = False
        record["renamedFromYarn"] = True
    concepts_hit, items = {}, []

    def hit(concept, item):
        concepts_hit[concept] = concepts_hit.get(concept, 0) + 1
        item["concept"] = concept
        items.append(item)

    if "error" in result and "from" not in result:
        record["stage"] = "refused"
        record["refusal"] = str(result["error"])[:1500]
        hit(refusal_concept(record["refusal"], work, concepts), {"kind": "refusal", "text": record["refusal"][:300]})
        if yarn:
            hit(classify("yarn-names", concepts), {"kind": "names", "text": "the source uses Fabric's yarn names"})
    else:
        record["stage"] = "converted"
        record["minecraft"] = result.get("minecraft")
        record["rulesApplied"] = sorted((result.get("rulesApplied") or {}).keys())
        record["notes"] = result.get("notes", [])
        for left in result.get("left", []):
            text = f"{left.get('what', '')} {left.get('why', '')}"
            hit(classify(text, concepts) or "unclassified left: " + str(left.get("what"))[:80],
                {"kind": "left", "file": left.get("file"), "line": left.get("line"), "what": left.get("what"), "why": left.get("why")})
        record["compiled"] = result.get("compiled")
        errors = []
        log = result.get("compileLog")
        if result.get("compiled") is False:
            if result.get("compileError"):
                hit(classify(result["compileError"], concepts) or "build evaluation failed",
                    {"kind": "build", "text": str(result["compileError"])[:1500]})
            elif log and Path(log).is_file():
                errors = compile_errors(Path(log).read_text(encoding="utf-8", errors="replace"))
                pkgs = own_packages(work)
                for e in errors:
                    hit(error_concept(e, mod["loader"], yarn, concepts, pkgs), {"kind": "compile", **e})
                if not errors:
                    text = Path(log).read_text(encoding="utf-8", errors="replace")
                    what = re.search(r"(?m)^\* What went wrong:\n(.+(?:\n.+){0,6})", text)
                    detail = what.group(1) if what else text[-1500:]
                    hit(classify(detail, concepts) or "build failed before javac", {"kind": "build", "text": detail[:1500]})
        record["compileErrors"] = len(errors)
        record["capped"] = len(errors) >= 100
        if result.get("compiled") and not args.no_game:
            namespace = mod_namespace(work, to) or mod_namespace(src, mod["loader"])
            record["namespace"] = namespace
            packages = own_packages(src)
            record["game"] = game_registries(work, namespace, packages)
            if record["game"].get("loaded"):
                original = fresh_copy(src, f"{mod['id']}-original")
                started.append(original)
                renamed = record.get("renamedFromYarn", False)
                record["originalGame"] = game_registries(original, mod_namespace(original, mod["loader"]), packages, yarn=renamed)
                if renamed:
                    tables = sorted((work / ".mmt" / "portable" / "yarn-to-mojang").glob("names-*.json"))
                    if tables:
                        record["originalGame"]["mixins"] = yarn_to_mojang_targets(record["originalGame"].get("mixins"), tables[-1])
                record["behaviour"] = behaviour(record["game"], record["originalGame"])
                if not args.no_fingerprint:
                    record["fingerprint"] = fingerprint_behaviour(work, original, mod["id"])
            else:
                hit("game load failed after a clean compile", {"kind": "game", "text": record["game"].get("loadError", "")[:1500]})
        if result.get("compiled") and not args.no_back and not names:
            code2, back, seconds2 = mmt(["convert", "--root", str(work), "--to", mod["loader"], "--json"], TIMEOUT["convert"])
            entry = {"exit": code2, "seconds": round(seconds2, 1)}
            if "error" in back and "from" not in back:
                entry["refusal"] = str(back["error"])[:600]
                entry["concept"] = refusal_concept(entry["refusal"], work, concepts)
            else:
                entry["compiled"] = back.get("compiled")
                entry["left"] = len(back.get("left", []))
                same = differ = 0
                for f in src.rglob("*.java"):
                    if ".git" in f.parts:
                        continue
                    g = work / f.relative_to(src)
                    if g.is_file() and g.read_bytes().replace(b"\r\n", b"\n") == f.read_bytes().replace(b"\r\n", b"\n"):
                        same += 1
                    else:
                        differ += 1
                entry["javaFilesSame"], entry["javaFilesDiffer"] = same, differ
            record["back"] = entry
    record["concepts"] = concepts_hit
    record["items"] = items
    record["outcome"] = outcome(record)
    runs = WORK / "runs"
    runs.mkdir(parents=True, exist_ok=True)
    (runs / f"{mod['id']}-to-{to}{'-' + names if names else ''}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def outcome(r):
    if r.get("stage") in ("clone", "refused"):
        return r["stage"]
    if not r.get("compiled"):
        return "converted, does not compile" if r.get("compiled") is False else "converted (not compiled)"
    if r.get("concepts"):
        if "game" in r and not r["game"].get("loaded"):
            return "compiles, fails in the game"
        return "compiles, items left"
    if "game" not in r:
        return "compiles"
    if not r["game"].get("loaded"):
        return "compiles, fails in the game"
    if (r.get("fingerprint") or {}).get("equal"):
        return "loads, same behaviour fingerprint as the original"
    b = r.get("behaviour") or {}
    if b.get("equal") and b.get("signal", "registries") == "registries":
        return "loads, registers what the original does"
    if b.get("equal") and b.get("signal") == "mixins":
        return "loads, applies the same mixins as the original"
    if b.get("equal") is None and b.get("why", "").startswith("the mod registers nothing"):
        return "loads, no registry entries to compare"
    return "loads in the game"


def rederive(r):
    """A record's behaviour check and outcome again from the games it read (older records counted zero against zero)."""
    if (r.get("game") or {}).get("loaded") and r.get("originalGame"):
        r["behaviour"] = behaviour(r["game"], r["originalGame"])
    r["outcome"] = outcome(r)
    return r


# ---- ranking ------------------------------------------------------------------------------------------

LADDER = ["refused", "converted, does not compile", "compiles, items left", "compiles", "compiles, fails in the game",
          "loads in the game", "loads, no registry entries to compare", "loads, applies the same mixins as the original",
          "loads, registers what the original does", "loads, same behaviour fingerprint as the original"]


def summarize(records, label):
    records = [rederive(r) for r in records]
    gaps = {}
    for r in records:
        for concept, count in r.get("concepts", {}).items():
            g = gaps.setdefault(concept, {"concept": concept, "mods": [], "items": 0, "blocking": 0, "examples": []})
            g["mods"].append(r["id"])
            g["items"] += count
            if r.get("stage") == "refused":
                g["blocking"] += 1
            for item in r.get("items", []):
                if item.get("concept") == concept and len(g["examples"]) < 4:
                    g["examples"].append(f"{r['id']}: " + str(item.get("what") or item.get("symbol") or item.get("message") or item.get("text"))[:160])
    ranked = sorted(gaps.values(), key=lambda g: (-len(g["mods"]), -g["blocking"], -g["items"], g["concept"]))
    for i, g in enumerate(ranked, 1):
        g["rank"] = i
    counts = {}
    for r in records:
        counts[r["outcome"]] = counts.get(r["outcome"], 0) + 1
    n = len(records)
    rates = {
        "label": label, "date": datetime.date.today().isoformat(), "runs": n,
        "not refused": sum(r.get("stage") == "converted" for r in records),
        "compiled": sum(bool(r.get("compiled")) for r in records),
        "compiled with nothing left": sum(bool(r.get("compiled")) and not r.get("concepts") for r in records),
        "loaded in the game": sum(bool((r.get("game") or {}).get("loaded")) for r in records),
        "registers what the original does": sum(
            bool((r.get("behaviour") or {}).get("equal")) and (r.get("behaviour") or {}).get("signal", "registries") == "registries" for r in records),
        "same behaviour as the original (registries, or mixins when it registers nothing)": sum(bool((r.get("behaviour") or {}).get("equal")) for r in records),
        "loaded with nothing to compare": sum((r.get("behaviour") or {}).get("signal", "") is None for r in records),
        "converted back and compiled": sum(bool((r.get("back") or {}).get("compiled")) for r in records),
        "same behaviour fingerprint as the original": sum(bool((r.get("fingerprint") or {}).get("equal")) for r in records),
        "fingerprinted": sum((r.get("fingerprint") or {}).get("equal") is not None for r in records),
        "outcomes": counts,
    }
    rates["headline"] = {"name": "conversions with the same behaviour fingerprint as the original", "value": rates["same behaviour fingerprint as the original"], "of": len(records)}
    return ranked, rates


def compact(r):
    keep = ["id", "name", "from", "to", "commit", "minecraft", "yarn", "outcome", "convertSeconds", "compileErrors", "capped",
            "refusal", "rulesApplied", "concepts", "behaviour", "fingerprint", "back"]
    out = {k: r[k] for k in keep if k in r}
    if "game" in r:
        out["game"] = {k: v for k, v in r["game"].items() if k not in ("registries", "mixins")}
    return out


def write(records, label):
    ranked, rates = summarize(records, label)
    previous = json.loads(OUT.read_text(encoding="utf-8")) if OUT.is_file() else {}
    history = [h for h in previous.get("history", []) if h.get("label") != label] + [rates]
    data = {
        "about": "mmt convert on the pinned corpus in bench/convert-sweep/corpus.json, by python bench/convert_sweep.py. "
                 "gaps ranks each missing loader concept by how many mods it affects (blocking: refusals). history keeps each labelled sweep's pass rates.",
        "corpus": len(json.loads(CORPUS.read_text(encoding="utf-8"))["mods"]),
        "latest": rates, "history": history, "gaps": ranked, "runs": [compact(r) for r in sorted(records, key=lambda r: r["id"])],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return ranked, rates


def table(ranked, rates, limit=30):
    lines = [f"Convert sweep ({rates['label']}): {rates['runs']} runs; not refused {rates['not refused']}, compiled {rates['compiled']}, "
             f"nothing left {rates['compiled with nothing left']}, loaded {rates['loaded in the game']}, "
             f"registers what the original does {rates['registers what the original does']}, "
             f"same mixins or entries {rates['same behaviour as the original (registries, or mixins when it registers nothing)']}, "
             f"nothing to compare {rates['loaded with nothing to compare']}, back and compiled {rates['converted back and compiled']}",
             "", "| # | Missing concept | Mods | Blocking | Items | Example |", "|---|---|---|---|---|---|"]
    for g in ranked[:limit]:
        example = (g["examples"][0] if g["examples"] else "").replace("|", "/")
        lines.append(f"| {g['rank']} | {g['concept']} | {len(g['mods'])} | {g['blocking']} | {g['items']} | {example[:110]} |")
    return "\n".join(lines)


def load_records():
    runs = WORK / "runs"
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(runs.glob("*.json"))] if runs.is_dir() else []


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--only", default="")
    run.add_argument("--jobs", type=int, default=2, help="at most this many builds or games at once (default 2)")
    run.add_argument("--no-compile", action="store_true", help="convert without compiling")
    run.add_argument("--no-game", action="store_true")
    run.add_argument("--no-back", action="store_true")
    run.add_argument("--names", default="", help="mojang: rename yarn-named Fabric mods to Mojang's names, staying on Fabric, instead of converting")
    run.add_argument("--no-fingerprint", action="store_true", help="skip the behaviour fingerprints of the original and the converted mod")
    run.add_argument("--label", default="latest")
    run.add_argument("--resume", action="store_true", help="skip what already has a record under this --label")
    run.add_argument("--mmt", default="", help="run another mmt checkout (e.g. the baseline) instead of this one")
    run.add_argument("--record", action="store_true", help="write coverage/sweeps/convert.json after a partial (--only) run too")
    t = sub.add_parser("table")
    t.add_argument("--label", default="latest")
    args = parser.parse_args()
    if args.command == "table":
        ranked, rates = write(load_records(), args.label)
        print(table(ranked, rates))
        return 0
    if args.mmt:
        MMT[0] = str(Path(args.mmt).resolve() / ("mmt.cmd" if WINDOWS else "mmt"))
    mods = json.loads(CORPUS.read_text(encoding="utf-8"))["mods"]
    if args.only:
        wanted = set(args.only.split(","))
        mods = [m for m in mods if m["id"] in wanted]
    if args.resume:
        done = {r["id"] for r in load_records() if r.get("label") == args.label}
        mods = [m for m in mods if m["id"] not in done]
    concepts = load_concepts()
    WORK.mkdir(parents=True, exist_ok=True)
    failed = run_all(mods, args, concepts)
    records = load_records()
    if records_coverage(args):
        ranked, rates = write(records, args.label)
    else:
        ranked, rates = summarize(records, args.label)
        print(f"Partial run (--only): {OUT.relative_to(ROOT)} is left as it is; pass --record to write it.")
    print(table(ranked, rates))
    if failed:
        # A mod whose run raised has no record, so the table would silently leave it out: fail the run instead.
        print(f"Runner errors (no record written): {', '.join(failed)}", flush=True)
        return 1
    return 0


def run_all(mods, args, concepts):
    """Runs each mod; returns the ids whose run raised (they have no record)."""
    failed = []
    with concurrent.futures.ThreadPoolExecutor(max(1, min(2, args.jobs))) as pool:
        futures = {pool.submit(run_one, m, args, concepts): m for m in mods}
        for f in concurrent.futures.as_completed(futures):
            m = futures[f]
            try:
                r = f.result()
                print(f"{m['id']}: {r['outcome']} {json.dumps(r.get('concepts', {}))[:300]}", flush=True)
            except Exception as e:
                failed.append(m["id"])
                print(f"{m['id']}: runner error {e!r}", flush=True)
    return sorted(failed)


def records_coverage(args):
    """Whether a run writes the tracked coverage file: a run of the whole corpus, or one asked to with --record."""
    return not args.only or args.record


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):  # UTF-8 whatever the console or PYTHONIOENCODING says
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
