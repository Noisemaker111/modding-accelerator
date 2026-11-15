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

print(f"{checks} sweep checks passed")
