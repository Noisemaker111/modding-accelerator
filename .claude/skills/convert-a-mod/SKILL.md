---
name: convert-a-mod
description: Move Minecraft mods between loaders (Fabric and NeoForge) with mmt convert and turn everything that is missing into bridge coverage every mod reuses. Use when Jon names a mod and a loader, says "convert this", "next mod to convert", or asks to push the convert numbers further.
---

Convert rests on a bridge: the other loader's API, written on top of this one (`knowledge/bridges/`, wired in
`knowledge/loaders.json` `bridges[]`). You never work on "this mod's errors". You add one missing piece of the
loader's API to the bridge, and every mod that uses that piece is fixed with it. Read this whole file first.

## Where converting stands (2026-11-18)

- `python bench/convert_coverage.py real` (2 minutes, no build) reads the 150 most-downloaded NeoForge mods for
  1.21.1: **68 use nothing the bridge lacks; 442 classes and members hold the other 82, and the next 20 on its list
  would make it 94.** That list is the work, top first, and that count is the number to move.
- `python bench/convert_sweep.py table` prints the last full sweep of our own 35 mods: which compile, load and behave
  like the original, mod by mod. It is the proof at the end of a PR, never what chooses the work.
- The list predicts the compiler: every corpus mod it shows with nothing missing compiled after converting (15 of
  15). It says nothing about behaviour; only the game does.

## What was tried, with its result. Don't repeat these

| Tried | Result |
|---|---|
| Fixing the compile errors of the nearest corpus mod, one mod at a time (Nov 16 to 18: curios, morered, buildinggadgets2) | one mod a day, each from its own long tail |
| Grouping the 14 corpus mods that don't compile by their first compiler errors (Nov 18) | 0 of 3 target mods: the compiler stops at the first layer, and fixing it shows the next |
| Giving every mod all of NeoForge's copied classes as supertypes (Nov 18, reverted) | 0 of 3, and a conversion took 27 s instead of 5 |
| Counting what those 14 corpus mods miss | true, but no package frees more than one of them: they are 14 unusual mods |
| Copying NeoForge's own classes when they compile on Fabric, generating the methods its patches add (Nov 16 to 17) | worked: 10 to 14 corpus mods |
| Setting a mod's own test mod aside instead of converting it (Nov 18) | worked: satin |

What the failures share: they aimed at the corpus mods that fail. Those are a small, odd sample. What the 150
most-downloaded mods use is the real list, and it has a short head.

## The loop. One missing piece per turn

1. `python bench/convert_coverage.py real --top 10`. On a new PC first:
   `python bench/exposure.py fetch --loader neoforge --version 1.21.1`. Take the first line. Each line is a NeoForge
   class or member, how many mods use it, and how many mods are clear once it is there.
2. Put it in the bridge, from the loader's own published files (jars and sources under `.runs/loader-api/`). Where it
   goes depends on what it is:
   - `Class#method (added by neoforge)`, a method NeoForge adds to a game class: `python bench/bridge_generate.py`
     writes the signature (`generated*/` and its mixin). Write real behaviour by hand only when mods call it.
   - a `net.neoforged.neoforge.*` class: add it to `neoforge-sources.txt` so convert copies NeoForge's own LGPL source
     into the converted mod, then `python bench/bridge_import.py` measures which members compile on Fabric.
   - a `net.neoforged.fml.*` or event-bus class, or a member missing from a class the bridge wrote by hand: write it
     in `knowledge/bridges/neoforge-on-fabric/` (or the version folder, `1.21.1/`), on Fabric's API, and name it in
     `bridges[].classes`.
   Never copy from a mod, and never name one.
3. Check it without a mod: `python bench/bridge_check.py --bridge neoforge-on-fabric --minecraft 1.21.1` compiles
   the whole bridge in a real project, runs its tests and applies its mixins.
4. Run step 1 again. The piece must be gone from the list and the clear count must not drop. If neither moved, it is
   not there the way mods use it: fix it or take it out. Commit.
5. Before the PR ends, prove it in the game on two corpus mods that use what you added plus one that already worked:
   `python bench/convert_sweep.py run --only ID,ID,ID --no-back`, then `./mmt game-stop`. A conversion still takes
   about 5 s.
6. The whole corpus runs in the cloud once, on the PR's final commit: `tools/cloud-run convert -f label=NAME`. Copy
   its `summary/coverage/sweeps/convert.json` into `coverage/sweeps/`; its numbers are the PR's numbers.

Stop and tell Jon when: three pieces in a row each cleared no mod; the clear count rose by 20 and the corpus count
did not move (then the list has stopped predicting the compiler, and that comes first); a fix needs to name a mod;
or a piece cannot be written on Fabric's API at all (say which, and why).

## What this loop does not cover yet

Each is its own roadmap step, and a reason a mod can be clear on the list and still not convert:
- a mod that depends on another mod with no build for the target loader (12 of the 35 corpus mods);
- game versions the bridges lack (1.21.4, 1.21.5, 1.21.9; Fabric to NeoForge starts at 26.1);
- Fabric jars before 26.1, which the list cannot read (they name the game in intermediary names);
- behaviour: an event the bridge has but never fires compiles and loads. The sweep's fingerprint is the only check.
