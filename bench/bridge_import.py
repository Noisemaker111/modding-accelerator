"""Which of NeoForge's own classes compile on Fabric through the NeoForge bridge, for each game version the bridge has:
writes the list mmt convert copies NeoForge's classes from (neoforge-sources.txt in the bridge folder or its version
layer; tools/cli/NeoForgeSources.java).

    python bench/bridge_import.py [--only 26.3,26.1,1.21.1]

For each version, NeoForge's MDK for that game is converted to Fabric with MMT_NEOFORGE_SOURCES=all, so every class of
NeoForge's published sources jar that the bridge does not mirror is copied into the bridge's package. mmt compile then
builds it; in each copied file javac reports an error in, the member around the error (a method, field, constructor,
initializer, nested class or import) is removed, or the whole file when the error is outside any member or in an
@Override method (NeoForgeSources.around), and it compiles again until no copied file fails. What is left compiles
together on Fabric, so any part of it a mod names, with every listed class that names in turn (what convert copies,
without the listed members), compiles too. An error outside the copied files (the bridge or the MDK) stops the run, except
the entrypoint's subscription of a removed NeoForge @EventBusSubscriber class, which goes with the class.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_sweep as cs  # noqa: E402

BRIDGE = "knowledge/bridges/neoforge-on-fabric"
# version: (corpus MDK, gradle.properties changed for that game, folder the list goes in)
VERSIONS = {
    "26.3": ("mdk-mdg-26.3", {}, BRIDGE),
    "26.1": ("mdk-mdg-26.3", {"minecraft_version": "26.1.2", "minecraft_version_range": "[26.1.2]", "neo_version": "26.1.2.126"}, BRIDGE + "/26.1"),
    "1.21.1": ("mdk-mdg-1.21.1", {}, BRIDGE + "/1.21.1"),
}
MARK = "/" + "loaderbridge/neoforge/"
MEMBERS = cs.ROOT / "tools" / "cli" / "NeoForgeSources.java"


def newest_log(work):
    logs = sorted((work / ".runs" / "portable").glob("*/*compile.log"), key=lambda p: p.stat().st_mtime)
    return logs[-1] if logs else None


def measure(version):
    mod_id, props, folder = VERSIONS[version]
    corpus = __import__("json").loads(cs.CORPUS.read_text(encoding="utf-8"))["mods"]
    src = cs.pristine(next(m for m in corpus if m["id"] == mod_id))
    work = cs.fresh_copy(src, f"neoforge-sources-{version}")
    if props:
        path = work / "gradle.properties"
        lines = [line.split("=", 1)[0] + "=" + props[line.split("=", 1)[0]] if line.split("=", 1)[0] in props else line
                 for line in path.read_text(encoding="utf-8").splitlines()]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.environ["MMT_NEOFORGE_SOURCES"] = "all"
    try:
        code, result, _ = cs.mmt(["convert", "--root", str(work), "--to", "fabric", "--no-compile", "--json"], cs.TIMEOUT["convert"])
    finally:
        del os.environ["MMT_NEOFORGE_SOURCES"]
    copied = sorted(p for p in work.joinpath("src", "main", "java").rglob("*.java") if MARK in p.as_posix())
    if not copied:
        sys.exit(f"{version}: convert copied none of NeoForge's classes (exit {code}): {str(result)[:1500]}")
    root = Path(copied[0].as_posix().split(MARK)[0] + MARK)
    print(f"{version}: {len(copied)} of NeoForge's classes copied; compiling", flush=True)
    removed = 0
    members = {}
    for round_ in range(1, 200):
        code, result, seconds = cs.mmt(["compile", "--root", str(work), "--json"], cs.TIMEOUT["convert"])
        if code == 0:
            break
        log = newest_log(work)
        errors = cs.compile_errors(log.read_text(encoding="utf-8", errors="replace")) if log else []
        failing = {Path(e["file"]).resolve() for e in errors}
        mine = {f for f in failing if MARK in f.as_posix()}
        others = failing - mine
        if others or not mine:
            for e in [e for e in errors if MARK not in e["file"]][:20]:
                print(f"  {e['file']}:{e['line']}: {e['message']}")
            sys.exit(f"{version}: the compile fails outside NeoForge's copied classes (round {round_}); log {log}: {str(result)[:800]}")
        java = work / "src" / "main" / "java"
        lines = {}
        for e in errors:
            if Path(e["file"]).resolve() in mine:
                lines.setdefault(Path(e["file"]).resolve(), set()).add(e["line"])
        batch = work / ".runs" / "bridge-import-errors.txt"
        batch.write_text("".join(f"{f}\t{','.join(map(str, sorted(n)))}\n" for f, n in lines.items()), encoding="utf-8")
        out = subprocess.run(["java", str(MEMBERS), str(batch)], capture_output=True, text=True, encoding="utf-8", check=True).stdout
        gone = set()
        cut = 0
        for row in out.splitlines():
            f, key = row.split("\t", 1)
            name = "net.neoforged.neoforge." + Path(f).resolve().relative_to(root.resolve()).with_suffix("").as_posix().replace("/", ".")
            if key:
                # An import names the bridge by the MDK's package; convert puts the mod's in its place.
                members.setdefault(name, set()).add(key.replace(str(root.relative_to(java)).replace(os.sep, ".").removesuffix(".neoforge"), "${package}"))
                cut += 1
                continue
            os.unlink(cs.extended(f))
            members.pop(name, None)
            gone.add(Path(f).resolve().relative_to(java.resolve()).with_suffix("").as_posix().replace("/", "."))
        # Convert subscribes NeoForge's own @EventBusSubscriber classes from the mod's entrypoints: not the removed ones.
        for own in java.rglob("*.java"):
            if MARK in own.as_posix():
                continue
            text = Path(cs.extended(own)).read_text(encoding="utf-8")
            kept = "".join(line for line in text.splitlines(keepends=True) if not any(name + ".class" in line for name in gone))
            if kept != text:
                Path(cs.extended(own)).write_text(kept, encoding="utf-8")
        removed += len(gone)
        print(f"  round {round_}: {len(mine)} files failed ({seconds:.0f} s): {cut} members and {len(gone)} files removed; {removed} files in all", flush=True)
    else:
        sys.exit(f"{version}: still failing after 200 rounds")
    kept = sorted("net.neoforged.neoforge." + p.relative_to(root).with_suffix("").as_posix().replace("/", ".") for p in root.rglob("*.java"))
    out = cs.ROOT / folder / "neoforge-sources.txt"
    out.write_text(
        f"# NeoForge's own classes that compile on Fabric through this bridge (python bench/bridge_import.py --only {version}):\n"
        "# mmt convert copies the ones a mod names, with the listed classes those name, from NeoForge's sources jar.\n"
        "# A line with a second part names a member of the class that does not compile on Fabric and is left out.\n"
        + "".join(name + "\n" for name in kept)
        + "".join(f"{name} {key}\n" for name in kept for key in sorted(members.get(name, ()))), encoding="utf-8", newline="\n")
    print(f"{version}: {len(kept)} of {len(copied)} compile on Fabric; wrote {out.relative_to(cs.ROOT)}")
    cs.stop(work)
    return len(kept), len(copied)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--only", default=",".join(VERSIONS))
    args = parser.parse_args(argv)
    for version in args.only.split(","):
        measure(version)


if __name__ == "__main__":
    main()
