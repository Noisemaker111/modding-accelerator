# Forge's fluid pushing in All the Mods 9, fixed in ModernFix

Forge 1.20.1 (47.4.0) checks which fluids push an entity in `Entity.updateFluidHeightAndDoFluidPushing(Predicate)`,
every entity every tick, on the server and the client. It starts with `new Object2ObjectArrayMap<>(FluidType.SIZE - 1)`:
a map with room for every registered fluid type. All the Mods 9 1.1.1 registers 759, so each call allocates two
758-slot arrays (about 6 KB), though most entities touch no fluid and the rest one or two. After the loop the map is only
iterated, in insertion order.

`fluid-map-empty-first.diff` adds the ModernFix (LGPL-3.0, [embeddedt/ModernFix](https://github.com/embeddedt/ModernFix))
mixin `perf.fluid_pushing_alloc`: the map starts empty (`new Object2ObjectArrayMap<>()`, sharing fastutil's empty arrays)
and grows as fluids are found, keeping their insertion order, so the heights and pushes it produces are the same. Switch:
`FluidPushingMaps.EMPTY_FIRST` (on).

| Run | Scenario | Verdict | Whole-game allocation, cold | Warm |
|---|---|---|---|---|
| `2026-11-16T11-34-14.350867800Z-bf11b30f` | 300 cows in a pen beside the player | accepted | 1.64x (95% 1.62-1.67): 217 → 132 MB/s | 1.67x (95% 1.62-1.71): 248 → 148 MB/s |
| `2026-11-16T10-42-29.157326400Z-886caed6` | the player alone in new land | rejected | 0.97x (95% 0.88-1.05) | 0.98x (95% 0.83-1.09) |

With few entities the saving is a few percent of the game's allocation and launches differ by more; with a farm's worth
of animals it is two fifths of it. Scopes on the farm: young collections 1.26x (cold) and 1.44x (warm) fewer, server
tick 1.07x (95% 1.01-1.11) cold. 3 and 5 pairs, both arms at once in their own All the Mods 9 client, on a shared
machine: approximate. Same game before (`perf-toggle`, 37 client entities, 20 passes each): 1.47x less CPU (99%
1.34-1.60) and 12.4x less allocation, same results.

Gate (`exact`, every launch of both arms): `fluids.java` builds a rig 300 high over the player (still water, a flowing
slope, lava) on the server thread and runs seven armor stands in it through the method once from rest; their water and
lava heights and push vectors are the same text in every launch.

## Run it

1. The pack and world, once per machine: the fixed All the Mods 9 export at `.runs/atm9/atm9-1.1.1-fixed.zip` (see
   `bench/spark/README.md`), and a world at `.runs/atm9/perf-world`: `mmt pack-run --pack .runs/atm9/atm9-1.1.1-fixed.zip
   --without journeymap`, make a world named `perf` (seed 20261115, creative, commands on), fly east about 10,000 blocks,
   `game-stop`, copy `saves/perf`.
2. ModernFix at the version the pack ships: `git clone https://github.com/embeddedt/ModernFix .runs/modernfix`,
   `git -C .runs/modernfix checkout 5.24.4+1.20.1`, then commit `setup.diff` (the version fixed instead of read from git:
   perf-optimize builds in git worktrees, which JGit cannot open; see KNOWN_ISSUES.md). Both arms share it.
3. `mmt perf-optimize --root .runs/modernfix --experiment bench/modernfix/experiment.json --patch bench/modernfix/fluid-map-empty-first.diff --dry-run`
