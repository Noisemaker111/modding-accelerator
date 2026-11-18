---
name: convert-a-mod
description: Move Minecraft mods between loaders (Fabric and NeoForge) with mmt convert and turn everything that is missing into bridge coverage every mod reuses. Use when Jon names a mod and a loader, says "convert this", "next mod to convert", or asks to push the convert numbers further.
---

The goal is a converter that needs no hand edits: a converted mod compiles, loads and behaves like the original.
Convert rests on a bridge, the other loader's API written on top of this one. Work on the bridge's coverage of the
whole loader API, not on one mod's compiler errors: a fix for one mod's error frees one mod, a covered API frees
every mod that calls it.

1. Start from the whole-goal number: `python bench/convert_sweep.py table` prints the last sweep's pass rates
   (compile, load, behaves the same, converts back) and the gaps ranked by how many mods each one blocks.
2. Take the gap that blocks the most mods. Fill it from the loaders' own published files (their jars, sources and
   patches), generated where the files allow it, for every game version the bridge serves. Never copy from the mods
   in the corpus, and never write a rule that names one.
3. Check the bridge without a mod: `python bench/bridge_check.py` compiles it, applies its mixins and runs its tests.
4. Prove it in the game locally on the mods the gap blocked: `python bench/convert_sweep.py run --only ID,ID`
   converts each, starts it and the untouched original hidden, and compares what they register and which mixins
   apply. `mmt game-stop` afterwards.
5. The whole corpus runs in the cloud, once, on the PR's final commit: `tools/cloud-run convert`. Its numbers are the
   PR's numbers.
6. If two gaps in a row each freed a single mod, stop: the list has reached its long tail. Find what would generate
   that tail from the loader's files before fixing a third.
