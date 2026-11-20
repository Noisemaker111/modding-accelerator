"""The port scoreboard (bench/exposure.py) counts what users would see.
- Its shared cache (~/.mmt/exposure) is safe for worktrees that run at once: entries are keyed by the content of the
  tools that build them, and a finished file is moved into place whole, the first one kept. Before, entries were keyed
  by file times, and one worktree's newer tools wiped a folder another was reading (#126).
- A copy of a platform module (a Fabric API module) nested in a mod is not what runs: Fabric Loader keeps the newest copy
  of a mod id, the platform's. What only that copy references does not hold the mod back; a copy of a module the platform
  no longer has does.
Run: python bench/test_exposure.py"""
import io
import json
import os
import zipfile
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import exposure as E  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    # Two worktrees: the same tools, then one with an edited GameDiff.
    for tree in ("a", "b"):
        (root / tree / "tools" / "cli").mkdir(parents=True)
        for name in E.MAP_SOURCES:
            (root / tree / "tools" / name).write_text(f"// {name}\n", encoding="utf-8")
    E.HOME = root / "home"

    def key_in(tree):
        E.ROOT = root / tree
        return E.tools_key(*E.MAP_SOURCES)

    check(key_in("a") == key_in("b"), "worktrees with the same tools share entries")
    time.sleep(0.01)
    os.utime(root / "a" / "tools" / "GameDiff.java")  # a newer file time alone changes nothing
    check(key_in("a") == key_in("b"), "a file touched without a change keeps its entries")
    (root / "b" / "tools" / "GameDiff.java").write_text("// GameDiff, edited\n", encoding="utf-8")
    check(key_in("a") != key_in("b"), "worktrees with other tools get entries of their own")

    E.ROOT = root / "a"
    first = E.keyed("maps", *E.MAP_SOURCES)
    (first / "neoforge-1.0-2.0.json").write_text("{}", encoding="utf-8")
    E.ROOT = root / "b"
    second = E.keyed("maps", *E.MAP_SOURCES)
    check(first != second and (first / "neoforge-1.0-2.0.json").is_file(), "making one worktree's entry leaves the other's in place")
    # A key no run has used for over a day is removed when a new one is made; a fresh one is kept.
    stale = first / "used"
    os.utime(stale, (time.time() - 2 * 86400, time.time() - 2 * 86400))
    (root / "b" / "tools" / "VersionMap.java").write_text("// VersionMap, edited\n", encoding="utf-8")
    third = E.keyed("maps", *E.MAP_SOURCES)
    check(not first.exists() and second.exists() and third.exists(), "only a key unused for a day is cleared")

    # Runs beside each other finishing the same file: every reader sees a whole file, and the first one is kept.
    target = third / "same.json"
    seen, start = [], threading.Barrier(8)

    def writer(i):
        part = E.part_of(target)
        part.write_text(f'{{"writer": {i}, "pad": "{"x" * 200000}"}}', encoding="utf-8")
        start.wait()
        E.publish(part, target)
        seen.append(target.read_text(encoding="utf-8"))

    threads = [threading.Thread(target=writer, args=(i,)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    check(len(seen) == 8 and all(s.endswith('"}') for s in seen), "no reader sees half a file")
    check(len(set(seen)) == 1, "one writer's file is kept and the others are dropped")
    check(not list(third.glob("*.part")), "no part file is left behind")



def jar(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    return buffer.getvalue()


def module(mod_id):
    return jar({"fabric.mod.json": json.dumps({"schemaVersion": 1, "id": mod_id, "version": "1"})})


with tempfile.TemporaryDirectory() as tmp:
    mod = Path(tmp) / "m.jar"
    mod.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "m", "jars": [{"file": "META-INF/jars/api-base-1.jar"}]}),
                         "META-INF/jars/api-base-1.jar": module("fabric-api-base"),
                         "META-INF/jars/old-module-1.jar": module("fabric-gone-v1")}))
    report = {"linked": 0, "mods": [
        {"mod": "m.jar", "silent": [], "unlinked": [
            "m.jar!api-base-1.jar: class gone: net.fabricmc.fabric.impl.base.Phase (in net.fabricmc.fabric.impl.base.Event)",
            "m.jar!old-module-1.jar: call: net.minecraft.Gone.run()V"]},
        {"mod": "n.jar", "silent": [], "unlinked": ["n.jar: call: net.minecraft.Gone.run()V (in n.Main)"]},
        {"mod": "o.jar", "silent": [], "unlinked": []}],
        "unlinkedByMods": []}
    out = E.without_platform_copies(report, {"fabric-api-base", "fabric-api"}, {"m.jar": mod})
    m = out["mods"][0]["unlinked"]
    check(len(m) == 1 and "old-module" in m[0], f"a nested copy of a platform module is not counted; one of a module the platform lacks is: {m}")
    check(out["unlinkedByMods"] == [{"member": "call: net.minecraft.Gone.run()V", "mods": 2}] and out["linked"] == 1,
          f"each reference counts once a mod and the mods that link are counted again: {out['unlinkedByMods']} {out['linked']}")
    check(E.nested_ids(mod.read_bytes()) == {"m": "", "fabric-api-base": "api-base-1.jar", "fabric-gone-v1": "old-module-1.jar"},
          "every id in a jar and its nested jars is read with the nested jar's name")

print(f"exposure: {checks} checks passed")
