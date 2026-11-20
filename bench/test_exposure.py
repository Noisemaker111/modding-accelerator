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

game_class = b"\xca\xfe\xba\xbe....net/minecraft/world/item/ElytraItem"
payload = jar({"gg/x/Mixin.class": game_class})
check(E.hidden_code(jar({"fabric.mod.json": json.dumps({"id": "e"}), "payload-1.jar": payload})) == "payload-1.jar",
      "game code in a jar no loader metadata lists is found: the port never reads it")
check(E.hidden_code(jar({"fabric.mod.json": json.dumps({"id": "e", "jars": [{"file": "META-INF/jars/payload.jar"}]}), "META-INF/jars/payload.jar": payload})) is None
      and E.hidden_code(jar({"META-INF/jarjar/metadata.json": json.dumps({"jars": [{"path": "META-INF/jarjar/p.jar"}]}), "META-INF/jarjar/p.jar": payload})) is None,
      "a jar fabric.mod.json or NeoForge's jarjar list nests is read by the port, so it is not hidden")
check(E.hidden_code(jar({"fabric.mod.json": "{}", "lib.jar": jar({"org/lib/A.class": b"\xca\xfe\xba\xbe org/lib"})})) is None,
      "a library jar with no game code is not the mod's hidden code")
rep = {"linked": 1, "mods": [{"mod": "e.jar", "unlinked": [], "silent": []}], "unlinkedByMods": []}
with tempfile.TemporaryDirectory() as tmp:
    e = Path(tmp) / "e.jar"
    e.write_bytes(jar({"fabric.mod.json": "{}", "payload-1.jar": payload}))
    out = E.with_hidden_code_unread(rep, {"e.jar": e})
check(out["linked"] == 0 and "payload-1.jar" in out["mods"][0].get("error", ""), "such a mod is reported as not read instead of linking")

with tempfile.TemporaryDirectory() as tmp:
    capes = Path(tmp) / "capes.jar"
    capes.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "capes", "depends": {"fabricloader": "*", "fabric-api": "*", "minecraft": "1.19.4"}})}))
    other = Path(tmp) / "other.jar"
    other.write_bytes(jar({"fabric.mod.json": json.dumps({"id": "o", "depends": {"cloth-config": "*"}})}))
    check(E.standalone(capes, {"fabric-api", "fabric-api-base"}) and not E.standalone(capes) and not E.standalone(other, {"fabric-api"}),
          "a mod that needs only Fabric API is started with the platform; one that needs another mod is not")

# The to-do list finishes mods. The reference the most mods share (g.Shared.one, 2 mods) finishes none of them.
rows = [
    {"mod": "a.jar", "unlinked": ["a.jar: call: g.Shared.one()V (in a.A)", "a.jar!in.jar: call: g.Shared.one()V (in a.B)", "a.jar: call: g.A.x()V", "a.jar: call: g.A.y()V"]},
    {"mod": "b.jar", "unlinked": ["b.jar: call: g.Shared.one()V", "b.jar: call: g.B.x()V"]},
    {"mod": "c.jar", "unlinked": ["c.jar: call: g.C.only()V (in c.C)"]},
    {"mod": "d.jar", "unlinked": ["d.jar: call: g.C.only()V"]},
    {"mod": "w.jar", "unlinked": ["w.jar: call: g.W.gone()V"]},
    {"mod": "e.jar", "unlinked": []},
]
said = {"call: g.C.only()V": {"game": "replaced"}, "call: g.W.gone()V": {"game": "redesigned"}}
order = E.nearest(rows, {"e.jar": ["data id gone: minecraft:thing x (in data/e.json)"]}, said)
check([t["mod"] for t in order] == ["c.jar", "e.jar", "w.jar", "b.jar", "a.jar"],
      f"mods come in the order they finish, the ones the game answers first among equals: {[t['mod'] for t in order]}")
check(order[0]["alsoFrees"] == ["d.jar"] and [t["modsDone"] for t in order] == [2, 3, 4, 5, 6] and [t["fixes"] for t in order] == [1, 2, 3, 5, 7],
      f"each turn counts the fixes so far and every mod they finish: {[(t['fixes'], t['modsDone']) for t in order]}")
check([m["member"] for m in order[4]["members"]] == ["call: g.A.x()V", "call: g.A.y()V"] and order[0]["members"][0]["game"] == "replaced",
      "a turn lists only what earlier turns left, each reference once with what the game did about it")
longer = {"mod": "b.jar", "unlinked": [f"b.jar: call: g.B.m{i}()V" for i in range(5)]}
check([t["mod"] for t in E.nearest([rows[0], longer], None, {})] == ["a.jar", "b.jar"], "a mod with more left than another comes after it")

# Jumps: the ports people ask for, from each family's most-used release to the next family's and to the newest.
with tempfile.TemporaryDirectory() as tmp:
    E.INDEX = Path(tmp) / "index.json"
    E.INDEX.write_text(json.dumps({
        "allVersions": [{"id": v, "status": "indexed"} for v in ("1.0", "1.1", "2.0", "2.1", "3.0")],
        "families": {"list": [{"id": "1", "versions": ["1.0", "1.1"], "hub": "1.0", "mods": {"1.0": 9, "1.1": 2}},
                              {"id": "2", "versions": ["2.0", "2.1"], "hub": "2.1", "mods": {"2.0": 1, "2.1": 5}},
                              {"id": "3", "versions": ["3.0"], "hub": "3.0", "mods": {"3.0": 4}}]}}), encoding="utf-8")
    E.FIRST = {"fabric": "1.0", "neoforge": "2.0"}
    check(E.jumps("fabric") == [("1.0", "2.1"), ("1.0", "3.0")],
          f"each hub to the next hub, and the most-used release to the newest, without the pairs one update apart: {E.jumps('fabric')}")
    check(E.jumps("neoforge") == [], "a loader read from a later release has only its own hubs, and a pair one update apart is no jump")
    check(E.big("1.1", "2.0") and not E.big("1.0", "1.1"), "an update into another family is a big one")

    def summary(loader, frm, to, link, turns=()):
        return {"loader": loader, "from": frm, "to": to, "modsRead": 10, "modsThatLink": link, "modsThatLinkAndData": link, "top": [],
                "nearest": [{"mod": mod, "members": [{"member": m, "game": "replaced"} for m in members]} for mod, members in turns]}

    runs = Path(tmp) / "runs"
    runs.mkdir()
    for s in (summary("fabric", "1.0", "1.1", 10), summary("fabric", "1.1", "2.0", 4, [("far.jar", ["a", "b", "c"]), ("near.jar", ["a"])]),
              summary("fabric", "1.0", "3.0", 1), summary("fabric", "1.1", "3.0", 7)):
        (runs / f"{s['loader']}-{s['from']}-{s['to']}.json").write_text(json.dumps(s), encoding="utf-8")
    sys.stdout = io.StringIO()
    try:
        table = E.join(runs, False)
    finally:
        sys.stdout = sys.__stdout__
    total = table["total"]
    check((total["link"], total["mods"]) == (14, 20), f"a jump is not counted among the single updates: {total['link']}/{total['mods']}")
    check((total["big"]["link"], total["small"]["link"], total["jumps"]["link"], total["jumps"]["mods"], total["newest"]["link"]) == (4, 10, 1, 10, 1),
          f"big updates, small ones, jumps and the jump to the newest release are counted apart: {total['big']} {total['small']} {total['jumps']} {total['newest']}")
    check(len(table["jumps"]) == 1 and table["jumps"][0]["updatesCrossed"] == 4 and "fabric 1.0 -> 2.1" in table["missing"] and "fabric 2.0 -> 2.1" in table["missing"],
          f"a jump says how many updates it crosses, one that did not run is named, and a pair that is no jump is left out: {table['jumps']} {table['missing']}")
    check([t["mod"] for t in table["nearest"]] == ["near.jar", "far.jar"], "over every update the mods nearest to done come first")

print(f"exposure: {checks} checks passed")
