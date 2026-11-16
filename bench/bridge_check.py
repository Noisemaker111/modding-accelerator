#!/usr/bin/env python3
"""Compiles every file of a convert bridge, mixins included, inside a real project of the loader it runs on.

    python bench/bridge_check.py [--bridge neoforge-on-fabric|fabric-on-neoforge] [--minecraft 26.3|1.21.1] [--keep]

A bridge (knowledge/loaders.json "bridges", files in knowledge/bridges/ID) is written on the other loader's API and only
compiles inside a converted mod. This check makes that mod from a pinned NeoForge MDK of bench/convert-sweep/corpus.json:
  neoforge-on-fabric: the MDK converted to Fabric by mmt convert (which copies the bridge in), then every bridge file
                      and version-layer file, and a mixin config listing every bridge mixin, so the mixin processor sees them;
  fabric-on-neoforge: the MDK as it is (NeoForge), with every bridge file copied in under its package.
and runs mmt compile on it, against the loaders' own published jars: first with none of the interfaces the bridge's
mixins add to game classes injected (as most converted mods get it), then with all of them. Then the bridge's runtime tests, JUnit sources in
knowledge/bridge-tests/ID (at the path of the package they test, ${package} as in the bridge; never copied into a
converted mod), run there with mmt test: they exercise bridge code that needs no running game (transactions, packet
contexts, entrypoints, network codecs). The work folder is .runs/bridge-check/; the result is printed as JSON (exit 0
when it compiled and every test passed). Launches no game.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_sweep  # noqa: E402  (the sweep's clone and copy helpers)

WORK = ROOT / ".runs" / "bridge-check"
HOSTS = {("neoforge-on-fabric", "26.3"): "mdk-mdg-26.3", ("neoforge-on-fabric", "1.21.1"): "mdk-mdg-1.21.1",
         ("fabric-on-neoforge", "26.3"): "mdk-mdg-26.3", ("neoforge-on-fabric", "26.1"): "mdk-mdg-26.3"}
# A host built on another game release than its own: the gradle.properties values that pin it there.
PINS = {("neoforge-on-fabric", "26.1"): {"minecraft_version": "26.1.2", "minecraft_version_range": "[26.1.2]", "neo_version": "26.1.2.126"}}


def bridge(rules, bridge_id):
    return next(b for b in rules["bridges"] if b["id"] == bridge_id)


def layer_of(b, minecraft):
    """The version layer convert would use for this game (Convert.bridgeLayer), or None."""
    def key(v):
        return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v)]
    if key(minecraft) >= key(b.get("minecraft", "99")):
        return None
    best = None
    for layer in b.get("layers", []):
        if key(minecraft) < key(layer["minecraft"]) or (layer.get("below") and key(minecraft) >= key(layer["below"])):
            continue
        if best is None or key(layer["minecraft"]) > key(best["minecraft"]):
            best = layer
    return best


def templates(b, layer):
    """{relative path: file} as Convert.bridgeTemplates reads them (the layer's files replace the bridge's own)."""
    out = {}
    folders = [ROOT / b["folder"]] + ([ROOT / layer["folder"]] if layer else [])
    for i, folder in enumerate(folders):
        for f in folder.rglob("*.java"):
            rel = f.relative_to(folder).as_posix()
            if i == 0 and "/" in rel and rel[0].isdigit():
                continue
            out[rel] = f
    # A layer leaves out the parts of the bridge written for newer games (Convert.dropped).
    for rel in [r for r in out if any(r.startswith(prefix) for prefix in (layer or {}).get("drops", []))]:
        del out[rel]
    return out


INJECTED = "fabricbridge-interfaces.json"  # Convert.INJECTED_INTERFACES
TESTS = ROOT / "knowledge" / "bridge-tests"
TEST_BUILD = """
// bench/bridge_check.py: the bridge's runtime tests.
dependencies {
    testImplementation "org.junit.jupiter:junit-jupiter:5.11.4"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher:1.11.4"
}
test {
    useJUnitPlatform()
}
"""


def run_tests(work, pkg_rel, pkg, modid, bridge_id, to_fabric):
    """Copies knowledge/bridge-tests/ID into the host's tests and runs them; None when the bridge has none."""
    folder = TESTS / bridge_id
    sources = sorted(folder.rglob("*.java")) if folder.is_dir() else []
    if not sources:
        return None
    test_java = work / "src" / "test" / "java" / pkg_rel
    convert_sweep.rmtree(work / "src" / "test" / "java")
    for f in sources:
        target = test_java / f.relative_to(folder)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(f.read_text(encoding="utf-8").replace("${package}", pkg).replace("${modid}", modid), encoding="utf-8")
    build = work / "build.gradle"
    text = build.read_text(encoding="utf-8")
    if "bench/bridge_check.py: the bridge's runtime tests" not in text:
        # ModDevGradle gives the game to the main source set only; Loom gives it to the tests too. Its unit tests run inside
        # FML with the host mod loaded, so the bridge's mixins apply as in the game (tests that use injected interfaces).
        # NeoForge's test framework starts a server for a test that asks for one (tags and item components bound).
        extra = "" if to_fabric else ("\nneoForge {\n    unitTest {\n        enable()\n        testedMod = mods.getByName(mod_id)\n    }\n}\n"
                                      "dependencies {\n    testImplementation \"net.neoforged:testframework:${neo_version}\"\n}\n")
        build.write_text(text + TEST_BUILD + extra, encoding="utf-8")
    code, result, seconds = convert_sweep.mmt(["test", "--root", str(work), "--json"], 3600)
    out = {"ok": code == 0, "files": len(sources), "seconds": round(seconds, 1), "details": str(result.get("details", ""))[:300]}
    if code != 0:
        # Gradle's console names each failed test and its exception; the log is the run's (mmt test --json gives its path).
        log = Path(str(result.get("log", "")))
        text = log.read_text(encoding="utf-8", errors="replace") if log.is_file() else str(result)
        out["failed"] = [line.strip() for line in text.splitlines() if " FAILED" in line or re.match(r"\s+(?:Caused by: )?[\w.$]+(?:Exception|Error) at ", line)][:60]
    return out


def inject(work, injected, to_fabric, on):
    """Adds the interfaces to game classes for the compile (Loom's injected interfaces, ModDevGradle's data), or takes them out."""
    if to_fabric:
        meta_file = work / "src" / "main" / "resources" / "fabric.mod.json"
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
        custom = meta.setdefault("custom", {})
        if on and injected:
            custom["loom:injected_interfaces"] = injected
        else:
            custom.pop("loom:injected_interfaces", None)
        meta_file.write_text(json.dumps(meta, indent="\t"), encoding="utf-8")
        return
    build = work / "build.gradle"
    block = f"\nneoForge {{\n    interfaceInjectionData {{\n        from(file('{INJECTED}'))\n    }}\n}}\n"
    text = build.read_text(encoding="utf-8").replace(block, "")
    if on and injected:
        (work / INJECTED).write_text(json.dumps(injected, indent=1), encoding="utf-8")
        text += block
    build.write_text(text, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bridge", default="neoforge-on-fabric")
    ap.add_argument("--minecraft", default="26.3")
    ap.add_argument("--keep", action="store_true", help="reuse the converted host from the last run (only recopy the bridge)")
    args = ap.parse_args()
    rules = json.loads((ROOT / "knowledge" / "loaders.json").read_text(encoding="utf-8"))
    b = bridge(rules, args.bridge)
    layer = layer_of(b, args.minecraft)
    host_id = HOSTS[(args.bridge, args.minecraft)]
    mods = {m["id"]: m for m in json.loads(convert_sweep.CORPUS.read_text(encoding="utf-8"))["mods"]}
    src = convert_sweep.pristine(mods[host_id])
    work = WORK / f"{args.bridge}-{args.minecraft}"
    to_fabric = b["to"] == "fabric"
    if not (args.keep and work.is_dir()):
        convert_sweep.rmtree(work)
        shutil.copytree(convert_sweep.extended(src), convert_sweep.extended(work),
                        ignore=shutil.ignore_patterns(".git", ".gradle", "build", ".mmt-sweep-commit"))
        props = work / "gradle.properties"
        for key, value in PINS.get((args.bridge, args.minecraft), {}).items():
            props.write_text(re.sub(rf"(?m)^{re.escape(key)}\s*=.*$", f"{key}={value}", props.read_text(encoding="utf-8")), encoding="utf-8")
        if to_fabric:
            code, result, _ = convert_sweep.mmt(["convert", "--root", str(work), "--to", "fabric", "--no-compile", "--json"], 3600)
            if code not in (0, 1) or "error" in result and "from" not in result:
                print(json.dumps({"ok": False, "stage": "convert", "result": result}, indent=1))
                return 1
    java = work / "src" / "main" / "java"
    if to_fabric:
        pkg_dir = next(p.parent for p in java.rglob("Bridge.java") if "What a NeoForge @Mod class" in p.read_text(encoding="utf-8"))
    else:
        mod = next(p for p in java.rglob("*.java") if "@Mod(" in p.read_text(encoding="utf-8"))
        pkg_dir = mod.parent / "fabricbridge"
    pkg = pkg_dir.relative_to(java).as_posix().replace("/", ".")
    modid = re.search(r"(?m)^mod_id\s*=\s*(\S+)", (work / "gradle.properties").read_text(encoding="utf-8")).group(1)
    files = templates(b, layer)
    for rel, f in files.items():
        target = pkg_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(f.read_text(encoding="utf-8").replace("${package}", pkg).replace("${modid}", modid), encoding="utf-8")
    mixins = (layer or {}).get("mixins") if (layer or {}).get("mixins") is not None else b.get("mixins") or []
    mixins = [m for m in mixins if "mixin/" + m["mixin"] + ".java" in files]
    if mixins:
        config = {"required": True, "minVersion": "0.8", "package": pkg + ".mixin",
                  "compatibilityLevel": "JAVA_21" if args.minecraft.startswith("1.") else "JAVA_25",
                  "mixins": [m["mixin"] for m in mixins if not m.get("client")],
                  "client": [m["mixin"] for m in mixins if m.get("client")], "injectors": {"defaultRequire": 1}}
        name = "bridgecheck.mixins.json"
        (work / "src" / "main" / "resources" / name).write_text(json.dumps(config, indent=1), encoding="utf-8")
        if to_fabric:
            meta_file = work / "src" / "main" / "resources" / "fabric.mod.json"
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
            meta["mixins"] = [m for m in meta.get("mixins", []) if m != name] + [name]
            meta_file.write_text(json.dumps(meta, indent="\t"), encoding="utf-8")
        else:
            toml = next((work / "src" / "main").rglob("neoforge.mods.toml"))
            text = toml.read_text(encoding="utf-8")
            if name not in text:
                toml.write_text(text + f'\n[[mixins]]\nconfig = "{name}"\n', encoding="utf-8")
    # The interfaces the bridge's mixins add to game classes. A converted mod gets only those of the mixins it needs, most
    # often none, so the bridge first compiles with none injected, then with all of them for its runtime tests.
    injected = {}
    for m in mixins:
        for target, interface in m.get("injects", {}).items():
            injected.setdefault(target, []).append((pkg + "." + interface).replace(".", "/"))
    inject(work, injected, to_fabric, False)
    code, result, seconds = convert_sweep.mmt(["compile", "--root", str(work), "--json"], 3600)
    errors = []
    # javac's errors are in the compile log of this run (the receipt's folder), not in the JSON mmt prints.
    logs = sorted((work / ".runs" / "portable").glob("*/*compile*.log"), key=lambda p: p.stat().st_mtime)
    if code != 0 and logs:
        for e in convert_sweep.compile_errors(logs[-1].read_text(encoding="utf-8", errors="replace")):
            errors.append(f"{Path(e['file']).name}:{e['line']}: {e['message']} {e['symbol']}".strip())
    out = {"ok": code == 0, "bridge": args.bridge, "minecraft": args.minecraft, "layer": (layer or {}).get("minecraft"),
           "files": len(files), "mixins": len(mixins), "seconds": round(seconds, 1), "work": str(work), "errors": errors[:200]}
    if code != 0 and not errors:
        out["result"] = {k: v for k, v in result.items() if k in ("error", "details", "log")} or str(result)[-3000:]
    if code == 0 and injected:
        inject(work, injected, to_fabric, True)
        code, result, more = convert_sweep.mmt(["compile", "--root", str(work), "--json"], 3600)
        out["seconds"] = round(seconds + more, 1)
        if code != 0:
            out["ok"] = False
            logs = sorted((work / ".runs" / "portable").glob("*/*compile*.log"), key=lambda p: p.stat().st_mtime)
            out["errors"] = ["with the interfaces injected: " + f"{Path(e['file']).name}:{e['line']}: {e['message']} {e['symbol']}".strip()
                             for e in (convert_sweep.compile_errors(logs[-1].read_text(encoding="utf-8", errors="replace")) if logs else [])][:200]
    if code == 0:
        tests = run_tests(work, pkg_dir.relative_to(java), pkg, modid, args.bridge, to_fabric)
        if tests is not None:
            out["tests"] = tests
            out["ok"] = tests["ok"]
    convert_sweep.mmt(["game-stop", "--root", str(work), "--json"], 300)
    print(json.dumps(out, indent=1))
    return 0 if out["ok"] else 1


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
