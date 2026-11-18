---
name: convert-a-mod
description: Move Minecraft mods between loaders (Fabric and NeoForge) with mmt convert and turn everything that is missing into bridge coverage every mod reuses. Use when Jon names a mod and a loader, says "convert this", "next mod to convert", or asks to push the convert numbers further.
---

The goal is a converter that needs no hand edits: a converted mod compiles, loads and behaves like the original.
Convert rests on a bridge, the other loader's API written on top of this one (`knowledge/bridges/`, wired in
`knowledge/loaders.json` `bridges[]`). Work on the bridge's coverage of the loader's whole published API, never on
one mod's errors: hand fixes closed one near miss a day (curios, then morered, then buildinggadgets2), each from its
own long tail.

1. **The number.** `python bench/convert_sweep.py table` prints the last sweep: of 28 convertible mods, how many
   compile, load, behave like the original and convert back, and the gaps ranked by how many mods each blocks.
2. **List the whole gap from the loader's files.** `python bench/bridge_coverage.py` compares each game version's
   whole loader API (published jars, NeoForge's patches and the extension interfaces they add) with the bridge, per
   game class; `--use` ranks what the corpus mods call. Group what blocks the failing mods by cause before fixing any.
3. **Produce the fixes in one go, from the loader's own files:**
   - signatures NeoForge adds to game classes: `python bench/bridge_generate.py` (writes `generated*/` and their
     mixins for 26.3, 26.1 and 1.21.1); real behaviour is hand-written only for what mods call, most-used first;
   - NeoForge's own classes: convert copies the ones a mod names from NeoForge's LGPL sources jar into the bridge
     package. `python bench/bridge_import.py` measures, per version, which compile on Fabric and which members must
     go (`neoforge-sources.txt`); rerun it after any bridge change. A copy that calls a patch-only game member gets a
     text patch onto the game's own members (`neoforge-patches.json`);
   - constructors and static methods NeoForge adds to game classes: `bridges[].constructors` and `bridges[].statics`
     send the call to a bridge class and back.
   Never copy from corpus mods, and never write a rule that names one.
4. **Prove it locally in the game** on the mods the change targets, with a mod that already worked as a guard:
   `python bench/convert_sweep.py run --only ID,ID --no-back` converts each, compiles it, starts it and the original
   hidden, and compares registries and the behaviour fingerprint. `mmt game-stop` afterwards.
5. **The whole corpus runs in the cloud** once, on the PR's final commit: `tools/cloud-run convert -f label=NAME`. Its
   numbers are the PR's numbers; copy its `summary/coverage/sweeps/convert.json` into `coverage/sweeps/`.
6. If two fixes in a row each freed one mod, stop and find what would generate the rest.
