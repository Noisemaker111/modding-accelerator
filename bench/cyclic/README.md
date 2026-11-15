# Cyclic crafter base: accept or reject a change to a real mod

A complete `perf-optimize` experiment for [Cyclic](https://github.com/Lothrazar/Cyclic) 1.14.2 (NeoForge 1.21.1,
branch `trunk/1.21.1`, commit `b2213600474fa088702dec68a783549e44dfa6ae`). It measures the server-side cost of Cyclic's
auto-crafters in a base that keeps compressing items, and decides each candidate diff against frozen and live
equivalence gates.

The clone gets one local commit before anything is measured, so launches load only the mod and its required libraries
(FLib, JEED): `build.gradle`'s and `extra.gradle`'s optional `localRuntime` test mods (JEI, Curios, Patchouli) are
commented out and the run logs to the console at INFO, as a player's game does. The mod's code is unchanged; both arms
of every run share that commit.

## The base

`scene.java` builds, once, into the save: 8 rows of 16 crafters (128), each a column of a source barrel, a hopper, the
crafter, a hopper and a sink barrel, with a second barrel and hopper feeding it from the side and a creative battery
between each pair. Every crafter compresses one of 16 ingredients (nine iron ingots into an iron block, nine wheat
into a hay bale, nine gold nuggets into an ingot, ...) every 40 ticks for at least 12 minutes of play. Cyclic's crafter
only crafts a grid with no empty slot while its inputs are full (its input check claims empty grid slots only with an
empty input stack), so the base uses full 3x3 grids, the common compression setup.

In a hidden client with the full JIT, Cyclic cost 4.71 ms of every server tick (`perf`, 95% 4.38–5.03), 81% of the
tick; its crafters 4.32 ms. Every crafter, every tick it has power, copied its grid, built a `CraftingInput`, and
called `RecipeManager.getAllRecipesFor(CRAFTING)` (which copies the list of every crafting recipe) to test each recipe
in turn: 92% of its samples.

## Run it

Windows uses `modaccel.cmd`. From the Mod Accel checkout:

```sh
git clone --branch trunk/1.21.1 https://github.com/Lothrazar/Cyclic .runs/cyclic
git -C .runs/cyclic checkout b2213600474fa088702dec68a783549e44dfa6ae
# Comment out every localRuntime line of build.gradle and extra.gradle, set the runs' logLevel to INFO, and commit
# that in the clone (give the clone a local git user.name and user.email first).

# Once: make the world (superflat, fixed seed, peaceful) and the base, then keep the save.
./modaccel ui-check --root .runs/cyclic --jit full
./modaccel eval --root .runs/cyclic --file bench/cyclic/create-world.java
./modaccel eval --root .runs/cyclic --file bench/cyclic/scene.java --thread control
./modaccel eval --root .runs/cyclic --code "mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().saveEverything(true, true, true)).join()" --thread control
./modaccel game-stop --root .runs/cyclic
mkdir -p .runs/cyclic-world && cp -r .runs/cyclic/run/saves/cyclic-base .runs/cyclic-world/cyclic-base

./modaccel perf-optimize --root .runs/cyclic --experiment bench/cyclic/experiment.json \
  --patch bench/cyclic/cache-matched-recipe.diff --runs 8 --dry-run --out .runs/cyclic-opt/cache-matched-recipe
```

Each accepted diff was committed in the clone before the next candidate ran, so apply them in the order of the table
below. `check.java` prints the live base's crafting totals; `parts.java` times each part of one crafter's tick directly
on the server thread (microseconds per call, no probes).

The metric is the exact inclusive wall time of `TileCrafter.serverTick` per server tick (`probes.json`), cold right
after the world loads and warm 20 s later; scopes add the mod's sampled CPU and the creative batteries' tick. A timing
probe costs about a microsecond per call on a loaded machine (it records every call), and the probe runs 128 times a
tick, so once a crafter's tick is down to a microsecond or two the probe is a large part of what is measured and the
measured ratio understates the change.

## Gates

- `crafts` (exact): `trace.java` builds 12 frozen crafters far from the player (x 2004..2026, chunks that never tick) and
  ticks them by hand for 160 rounds inside one server task: nine iron ingots; one oak log with empty grid slots and an
  empty input slot (shapeless); eight paper around a filled map (map extending, whose match reads the map's saved
  data); eight cobblestone around nothing (furnace); dirt and a diamond (no recipe); an empty grid; nine iron ingots
  with one named (components); a crafter that needs redstone, powered at round 100; gold nuggets with energy for two
  crafts; a three-kind shapeless stew; and two crafters with full inputs whose first stack holds 1 or 2 oak logs, for
  the input check's quirk (a stack whose count ran out claims empty grid slots, and then every empty grid slot,
  because the original copied every empty stack to the one `ItemStack.EMPTY` and `removeAll` compares by identity).
  During the run: a grid's centre emptied and put back, a log swapped for a birch log, a cobblestone shrunk to nothing
  in place (no handler call), a named ingot renamed in place, and the server's recipes replaced by all but iron_block
  and furnace (`RecipeManager.replaceRecipes`) and then put back in their order. After every round each crafter's 30
  slots (item, count, components), timer and energy go into a SHA-256.
- `crafting` (expect `true`): `count.java` empties the centre grid slot of 16 live crafters, requires each preview to
  be empty a second later, puts the centres back, waits 4 s, and requires all 128 crafters to show their block in the
  preview, to have made at least one more, to hold only their own items, and to have used exactly nine ingredients for
  each block made (counted over every barrel, hopper and crafter slot of the column).

The first version of `skip-unchanged-rewrites` failed `crafts` in both pairs while the baseline launches agreed: it
kept the preview's stack object between ticks, but NeoForge's `insertItem` stores the stack it is given, so on a craft
tick the result stack is both the preview and the stack in an empty output slot, and the next craft's `grow` changed
the preview too (kept run `2026-11-15T12-47-30.653364100Z-4d88655c`).

## Result

Three changes were accepted one after another, each against the clone with the ones before it, then all three
together against the unmodified mod. Every run had both arms of each pair started together, the full JIT, hidden and
muted launches, and the machine at a mean 45-72% CPU from other work (up to 100%; `machine.load` in each record).

| change (diff in this folder) | what it does | cold | warm |
|---|---|---|---|
| `cache-matched-recipe` | each crafter keeps the recipe its last scan found while its grid holds equal stacks (item, count, components) and the server's recipes are the same set (identity of `RecipeManager.getRecipes()`, replaced on every reload); a grid with a filled map is always scanned | 10.49x (9.94-11.33) | 11.42x (10.67-12.84) |
| `check-input-without-copies` | the input check reads the slots in place instead of copying 19 stacks into lists, with the original's empty-stack quirk kept | 1.25x (1.20-1.33) | 1.20x (1.12-1.29) |
| `skip-unchanged-rewrites` | the preview slot is not taken out and put back while it holds an equal stack of its own (it is written as before on a craft tick and the tick after, when its stack may also be in an output slot), and the grid is copied into the crafting container only when the recipe is looked up again | 1.55x (1.37-1.91) | 1.32x (1.25-1.42) |
| **all three (`cumulative.diff`) vs the unmodified mod** | | **15.70x (14.90-17.19)** | **15.90x (13.93-18.16)** |

The cumulative run (`2026-11-15T13-29-19.466771800Z-ef437d02`) measured the crafters' tick at 4.56 -> 0.29 ms per
server tick cold and 4.34 -> 0.28 warm, probe included; Cyclic's sampled CPU fell 6.8x cold and 6.3x warm (it includes
the 128 creative batteries, which no change touched). The three ratios multiply to about 20x; the cumulative run
measures less because each of the 128 timed calls a tick carries the probe's own cost, which no longer is small
next to a crafter's tick (`parts.java` times one at about a microsecond).

Rejected, kept in `coverage/perf-runs.json`: the first `skip-unchanged-rewrites` (`skip-unchanged-rewrites-aliased.diff`,
the shared preview stack above). None of the diffs was sent upstream.

What is left, in the accepted build: per crafter and tick, the recipe check (nine stack comparisons), a copy of the
result, the input check, a simulated insert of the result into the ten output slots, and an energy sync every 20 ticks.
The creative batteries' tick (0.3-0.4 ms per tick here) looks up the energy capability of itself and each neighbour on
every side every tick, in a shuffled order; batteries, generators and cables share that `moveEnergy` path.
