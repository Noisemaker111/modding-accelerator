# Extending pack-check

`pack-check` runs a modpack on its own loader's real server. A new loader, input, check or repair goes into one
registry, not the core (`tools/cli/PackCheck.java`); `modaccel pack-check --list` prints them. Copy an existing entry.

| Add | Where | Test it in |
|---|---|---|
| Pack input (export, `.mrpack`, instance, folder) | `PackInputs.ALL`, before any shape it overlaps | `PackMetadataTests.inputs` |
| Loader | `knowledge/pack-loaders.json` (`style`, `aliases`, installer, `metadata`, `failed`, `clientOnly`); a new style is a `Style` in `PackLoaders.java` | `PackServerTests.loaders` |
| Jar metadata | `PackMods.read` | a jar layout in `PackMetadataTests` |
| Check | `new Check(id, stage, what, body)` in `PackChecks.ALL`; `problem` only for what the game showed, `warning` for a metadata prediction, `note` never fails | a real log or jar layout in `PackLogTests` |
| Repair | `knowledge/pack-fixes.json` (`match`, `action`, `check`, `evidence`, `test`), only after a real run proved it; new matchers/actions in `PackRepairs.java` | its own `test`, run by `self-test` |
| Game version whose API moved | a layer `tools/packcheck/game/VERSION/ModaccelPackGame.java`, listed in `Installation.RESOURCES` | `probe-compile` |

The probe (`tools/packcheck/ModaccelPackProbe.java`) is loader-neutral, written in Mojang names, and reaches the
loader only by reflection; keep loader code out of it and its game layers.

Offline: `python bench/run_tests.py --only self-test`. In game, locally, before merge: `pack-check --pack PACK --focus
MODID [--fix]` starts one mod and its required mods on the real server (a repair is proven when the repaired server
makes a world, reopens it and has no problem the shipped one lacks). Every pack at once: `tools/cloud-run packs`.
