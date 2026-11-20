"""The convert and pack sweep runners' verdicts and what they write. Run: python bench/test_sweeps.py

Offline: no corpus, game or network. Found by an independent rerun of the sweeps: zero registry entries against zero
counted as "registers what the original does", and partial --only runs overwrote the tracked coverage files.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_sweep as C  # noqa: E402
import pack_sweep as P  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


EMPTY = "{}"
ITEMS = "{ResourceKey[minecraft:root / minecraft:item]=[gem, ore]}"


def record(converted, original, mixins=(None, None), equal=True):
    """A run record as the runner wrote it, with the games it read."""
    return {"id": "x", "stage": "converted", "compiled": True, "concepts": {}, "outcome": "loads, registers what the original does",
            "game": {"loaded": True, "registries": converted, "mixins": mixins[0]},
            "originalGame": {"loaded": True, "registries": original, "mixins": mixins[1]},
            "behaviour": {"equal": equal}}


# Nothing on either side is no comparison, even in a record an older runner marked equal (Krypton, fabric-example).
r = C.rederive(record(EMPTY, EMPTY))
check(r["behaviour"]["equal"] is None and r["outcome"] == "loads, no registry entries to compare", f"zero entries against zero is no pass: {r}")
_, rates = C.summarize([record(EMPTY, EMPTY)], "t")
check(rates["registers what the original does"] == 0 and rates["loaded with nothing to compare"] == 1, f"and is not counted: {rates}")

# Real entries are compared as before.
r = C.rederive(record(ITEMS, ITEMS))
check(r["behaviour"]["equal"] is True and r["outcome"] == "loads, registers what the original does", f"the same entries pass: {r}")
r = C.rederive(record(EMPTY, ITEMS))
check(r["behaviour"]["equal"] is False and r["behaviour"]["missing"], f"missing entries fail: {r}")

# A mod that registers nothing is compared by the mixins Mixin applied.
same = ".KryptonMixin > net.minecraft.network.Connection\n.Accessor > net.minecraft.server.MinecraftServer"
r = C.rederive(record(EMPTY, EMPTY, (same, same)))
check(r["behaviour"]["equal"] is True and r["outcome"] == "loads, applies the same mixins as the original", f"the same mixins pass: {r}")
r = C.rederive(record(EMPTY, EMPTY, (".KryptonMixin > net.minecraft.network.Connection", same)))
check(r["behaviour"]["equal"] is False and r["behaviour"]["missing"] == [".Accessor > net.minecraft.server.MinecraftServer"], f"a mixin that no longer applies fails: {r}")
_, rates = C.summarize([record(EMPTY, EMPTY, (same, same))], "t")
check(rates["registers what the original does"] == 0 and rates["same behaviour as the original (registries, or mixins when it registers nothing)"] == 1,
      f"a mixin pass is not counted as a registry pass: {rates}")
check('pkg.startsWith("me.steinborn")' in C.mixin_snippet(["me.steinborn"]) and "PACKAGES" not in C.mixin_snippet([]), "the mixin snippet reads the mod's packages")

# Only a run of the whole corpus, or one asked to with --record, writes coverage/sweeps.
for module in (C, P):
    check(module.records_coverage(argparse.Namespace(only="", record=False)), f"{module.__name__}: a full run writes the coverage file")
    check(not module.records_coverage(argparse.Namespace(only="krypton", record=False)), f"{module.__name__}: a partial run does not")
    check(module.records_coverage(argparse.Namespace(only="krypton", record=True)), f"{module.__name__}: unless --record asks it to")
check(not P.records_coverage(argparse.Namespace(only="", record=True, focus="soulsweapons", corpus=P.CORPUS)), "a focused pack sweep never writes it")
check(not P.records_coverage(argparse.Namespace(only="", record=True, focus="", corpus=P.CORPUS.with_name("repro.json"))), "nor one of another corpus")

# A mod whose run raises has no record: the run reports it and main fails, instead of the table silently leaving it out.
def boom(mod, args, concepts):
    if mod["id"] == "bad":
        raise RuntimeError("runner broke")
    return {"outcome": "ok"}
real = C.run_one
C.run_one = boom
try:
    failed = C.run_all([{"id": "good"}, {"id": "bad"}], argparse.Namespace(jobs=2), {})
finally:
    C.run_one = real
check(failed == ["bad"], f"a raising run is reported as failed: {failed}")

# The table on a checkout with no runs of its own prints the saved sweep and leaves the file alone; with runs it writes it.
import json  # noqa: E402
import tempfile  # noqa: E402
with tempfile.TemporaryDirectory() as tmp:
    real_out, real_work = C.OUT, C.WORK
    C.OUT, C.WORK = Path(tmp) / "convert.json", Path(tmp) / "work"
    try:
        rates = C.summarize([], "kept")[1]
        saved = json.dumps({"latest": dict(rates, runs=35), "history": [], "gaps": [], "runs": [{"id": "krypton", "outcome": "loads"}]})
        C.OUT.write_text(saved, encoding="utf-8")
        shown = C.table_command("latest")
        check(C.OUT.read_text(encoding="utf-8") == saved, "the table of a checkout with no runs leaves the saved sweep as it is")
        check("35 runs" in shown and "krypton: loads" in shown, "and prints it: " + shown)
        (C.WORK / "runs").mkdir(parents=True)
        (C.WORK / "runs" / "x.json").write_text(json.dumps({"id": "x", "outcome": "refused", "concepts": {}}), encoding="utf-8")
        C.table_command("latest")
        check([r["id"] for r in json.loads(C.OUT.read_text(encoding="utf-8"))["runs"]] == ["x"], "a checkout with runs writes them")
    finally:
        C.OUT, C.WORK = real_out, real_work

# The port sweep's error classes: "package Foo does not exist" for a game class used as a qualifier is not a missing
# library, while one imported from a library is.
import sweep as S  # noqa: E402
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / "Gen.java"
    src.write_text("package mod;\nimport net.minecraft.advancements.critereon.*;\nimport top.theillusivec4.curios.api.SlotContext;\n"
                   "class Gen { Object a = InventoryChangeTrigger.TriggerInstance.hasItems(); Object b = SlotContext.Builder.of(); }\n")
    rec = {"names": "mojmap", "to": "26.3"}
    game = S.error_class({"message": "package InventoryChangeTrigger does not exist", "file": str(src), "line": 4}, rec)
    lib = S.error_class({"message": "package SlotContext does not exist", "file": str(src), "line": 4}, rec)
    real_lib = S.error_class({"message": "package top.theillusivec4.curios.api does not exist", "file": str(src), "line": 3}, rec)
check(game[0] != "dependency-unavailable" and game[1] == "InventoryChangeTrigger", f"a game class used as a qualifier: {game}")
check(lib == ("dependency-unavailable", "top.theillusivec4.curios.api.SlotContext"), f"a library class used as a qualifier: {lib}")
check(real_lib[0] == "dependency-unavailable", f"a library package: {real_lib}")
import tempfile  # noqa: E402

with tempfile.TemporaryDirectory() as tmp:
    gradle = 'dependencies {\n    implementation "a:b:1"\n    runtimeOnly "mekanism:Mekanism:1"\n    localRuntime("x:y:2")\n}\n'
    (Path(tmp) / "build.gradle").write_text(gradle, encoding="utf-8")
    left = C.without_runtime_mods(tmp)
    text = (Path(tmp) / "build.gradle").read_text(encoding="utf-8")
    check(len(left) == 2 and '\n    implementation "a:b:1"' in text and "\n    runtimeOnly" not in text and "\n    localRuntime(" not in text,
          f"the original's runtime-only mods are left out for the behaviour check, its own dependencies kept: {text}")
    C.restore_runtime_mods(tmp, left)
    check((Path(tmp) / "build.gradle").read_text(encoding="utf-8") == gradle, "and put back as they were")

print(f"{checks} sweep checks passed")
