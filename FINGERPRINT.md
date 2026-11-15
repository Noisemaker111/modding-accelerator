# The behaviour fingerprint

One definition of "it works" and "nothing changed" for every mod: what the mod does in a seeded world that is frozen
from its first tick, recorded the same way whatever the mod, the loader or the game version. Nothing in it names a
mod, and no rule, repair, bridge or pattern condition may name one either.

## What it records

One probe, `tools/packcheck/ModaccelFingerprint.java`, written in Mojang names against the vanilla game. Every call that
changed between versions is in a layer under `tools/packcheck/fingerprint/VERSION/` (1.16 to 26.x; the newest at or below
the game's is used). It runs in the mod's hidden development client (loaded through `eval`, compiled inside the game
against the game's own classes) or on the loader's real dedicated server (inside pack-check's probe jar).

| Part | What |
|---|---|
| Registries | Every registry the server holds (built-in and from data packs): blocks, items, entities, block entities, menus, ...; every tag with its members; recipes with their types; loot tables; advancements |
| Blocks | Every block item of the mod's namespaces placed by a fake player through the real right-click path (pack-check's placement), each with the state it placed |
| Entities | Every entity type of the mod's namespaces made as a spawn egg makes it, in its own glass cell |
| Inventories | Each placed container given one stack; every container's slots and the item totals of the area |
| Ticks | A fixed number of full server ticks (`--ticks`, default 100), the area recorded every `--every` ticks |
| State per checkpoint | Per chunk: a hash of every block state and the count of each block; every block entity's saved data; every entity's saved data by type; scheduled block and fluid ticks; chunk tickets; loaded and unloaded chunks; the level's random state |
| Tickets during the run | Every chunk ticket seen on the area after any tick (a ticket a lookup renews for one tick shows here) |
| Save round trip | The world saved, the game closed, the save opened again frozen, the area read back and compared with the last checkpoint |
| Log | The game's warnings and errors, with times, threads, paths, hashes and durations replaced so two runs compare |

## Determinism

- The world: a flat world made by the vanilla server of the mod's Minecraft version, fixed seed (20261115), creative,
  easy, no structures, time fixed at noon and clear weather that stays, no mob, patrol, trader, phantom or warden
  spawning. Kept per version and seed in `~/.modaccel/packs/fingerprint-worlds/`.
- Frozen from the first tick: `world-check --freeze` (and the server probe) queue the game's tick freeze the moment the
  server exists, so it runs while the spawn area loads, before any tick. Chunks load; blocks, entities, block entities
  and scheduled ticks stand still.
- Seeded: before the first tick the probe seeds every random source in fields of the server, each level and each loaded
  entity (`RandomSource` and `java.util.Random`, found by type, never by name), from the seed and the field's class and order.
- Ticked by the probe: all ticks run inside one task on the server thread, so nothing else interleaves with them.
- Compared canonically: saved data is the game's own text form, with identifiers made from an unseeded source at creation
  (entity UUIDs and references to them) left out.

## What cannot be frozen

- Code a mod runs in its own server or level tick events runs while the world is frozen, before the scenario starts.
- Randomness a mod keeps in its own static fields, or takes from `ThreadLocalRandom`, `Math.random`, `new Random()` or the clock.
- The order of hash maps and sets keyed by identity: identity hash codes differ in every run.
- Work on other threads: chunk generation, lighting, a mod's own executors.
- Before Minecraft 1.20.3 there is no tick freeze: the world runs from load until the scenario holds the server thread.
- Development clients of yarn-named Fabric sources: the probe is Mojang-named; use `--jar` (the server path).

These show as leaves two baseline runs disagree on. A comparison leaves them out and names them.

## Commands

```sh
./modaccel fingerprint --root my-mod --out before.json                    # the mod's hidden development client
./modaccel fingerprint --jar my-mod.jar --minecraft 1.21.1 --loader neoforge   # the loader's real server
./modaccel fingerprint-diff before.json after.json                        # exit 0 when they match
./modaccel pack-check --pack my-pack.mrpack --fingerprint                 # the same probe on a whole pack
./modaccel port --root my-mod --from 1.21.1 --to 1.21.4 --fix --check fingerprint
```

## Where it is the definition

- **perf-optimize:** every experiment with a world gets a built-in `fingerprint` gate (opt out with `"fingerprint": false`,
  tune with `"fingerprint": {"ticks": 200, "every": 50, "radius": 8}`). Each launch opens the saved world frozen, runs the
  same seeded ticks with checkpoints over the chunks round the player and spawn, and lets the world run on for measuring.
  Leaves two baseline launches disagree on are left out and named; every other leaf of each candidate must equal its
  paired baseline's. A mod needs no hand-written gate; `--only fingerprint` decides on this gate alone.
- **port --check fingerprint:** the fingerprint on `--from` before any file changes and on `--to` after the port must match.
- **convert:** the same, on one game version, before and after the loader changes (every leaf the mod owns).
- **pack-check --fingerprint:** two more server starts make and reopen the fingerprint world with every mod of the pack.

## Normalisation across versions

`knowledge/fingerprint-normalise.json`. When the two records are of different game versions only what the mod owns is
compared, after the game's own renames: its registry entries, tags, recipes, loot tables and advancements; the state
each of its items placed; which of its entities could be made; its blocks, items and entities counted at each checkpoint;
whether the save round trip kept everything; and log errors that name it. Saved data (block entities, entities) is
compared by type and count across versions, since the game changed its formats (item components in 1.20.5, ValueOutput
in 1.21.6), and leaf by leaf within one version. Block state hashes use the game's numeric state ids, so they are only
compared within one version and mod set.

## Tests and proofs

`python bench/test_fingerprint.py` runs offline on recorded fingerprints (`bench/fingerprint-fixtures/`, trimmed from a
real mod's runs; the cases no run recorded say so in their `about`): a record against itself, a changed leaf named, the
generic gate leaving out what baselines disagree on and failing a candidate that loses its lookups' chunk tickets, and a
port compared across versions on what the mod owns.

The proofs in the game launch clients and run on an unattended runner, never a shared desktop:

```sh
python bench/perf_sweep.py run --only storagenetwork --prepare --record   # the shipped diff passes the generic gate; the old #75 diff fails it
python bench/perf_sweep.py run --only cyclic,pipez --prepare --record     # shipped diffs pass; Pipez's stale-cache sabotage fails
python bench/port_board.py --only zaa-neoforge-1.21.1-1.21.4 --fingerprint
python bench/convert_sweep.py run --only mdk-mdg-1.21.1 --no-back
python bench/pack_sweep.py run --only additive-fabric-1.19.2
python bench/scoreboard.py
```

`--prepare` makes each clone at its pinned commit with the folder's `prepare.diff` and builds the experiment world with
the folder's `create-world.java` and `scene.java`.

## Scoreboard

`coverage/scoreboard.json` holds one headline per goal, each from fingerprints: ports whose fingerprint matches across
the jump, conversions whose fingerprint matches across loaders, packs whose fingerprint world opens again unchanged, and
perf diffs the generic gate decides as their hand gates did. `python bench/scoreboard.py` rebuilds it from the sweeps'
results; the sweeps run unattended in CI (`--only ID` runs some).
