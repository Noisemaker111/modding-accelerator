# Pipez base: accept or reject a change to a real mod

A complete `perf-optimize` experiment for [Pipez](https://github.com/henkelmax/pipez)
1.21.1-1.2.31 (NeoForge 1.21.1, branch `1.21.1`, commit `91a01deda19e5beb19fe7840aa14daaa312aa212`).
It measures the server-side cost of item pipes in a storage base that keeps moving items, and
decides a candidate diff against frozen and live equivalence gates.

## The base

`scene.java` builds 4 rows of 16 barrels with an item pipe line on each side of every row. In each
row the south line extracts from the even barrels into the odd ones and the north line from the odd
barrels into the even ones, so items circulate for as long as the world runs. All 64 extracting
pipes have ultimate upgrades (64 items every tick), and each reaches 8 barrels at distances 1 to 15,
some full and some empty. The even barrels start with 27 stacks of 27 different items (55,296 items).
In a hidden client with the full JIT, Pipez costs about 1 ms of every server tick here
(`perf`: 0.99 ms, 95% 0.71–1.28): `ItemPipeType.insertOrdered` and
`UpgradeTileEntity.getSortedConnections`, which sorts the pipe's connection list on every
extraction, lead.

## Run it

Windows uses `mmt.cmd`. From the Minecraft Modding Tools checkout:

```sh
git clone --branch 1.21.1 https://github.com/henkelmax/pipez .runs/pipez
git -C .runs/pipez checkout 91a01deda19e5beb19fe7840aa14daaa312aa212

# Once: make the world (superflat, fixed seed, peaceful) and the base, then keep the save.
./mmt ui-check --root .runs/pipez --jit full
./mmt eval --root .runs/pipez --file bench/pipez/create-world.java
./mmt eval --root .runs/pipez --until "mc.player != null" --timeout 120
./mmt eval --root .runs/pipez --file bench/pipez/scene.java --thread control
./mmt eval --root .runs/pipez --code "mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().saveEverything(true, true, true)).join()" --thread control
./mmt game-stop --root .runs/pipez
mkdir -p .runs/pipez-world && cp -r .runs/pipez/run/saves/pipez-base .runs/pipez-world/pipez-base

./mmt perf-optimize --root .runs/pipez --experiment bench/pipez/experiment.json \
  --patch bench/pipez/sorted-connections.diff --runs 8 --dry-run --out .runs/pipez-opt/sorted-connections
```

The metric is the exact inclusive wall time of `ItemPipeType.tick` per server tick
(`probes.json`), cold right after the world loads and warm 20 s later; scopes add Pipez's sampled CPU
and the time in `insertOrdered` and `getSortedConnections`.

## Gates

- `transfers` (exact): `trace.java` builds two more rows at x 2000, chunks that never tick, with
  one extracting pipe at an end of each of their 4 lines, and ticks them by hand for 120 rounds at
  fixed game times: NEAREST, FURTHEST with an inverted filter, ROUND_ROBIN, and an advanced upgrade
  (speed depends on the game time) with a filter that allows one barrel; a full barrel and partial
  mixed stacks. After every round every slot of every barrel and every round-robin index go into a
  SHA-256. Extractors sit at line ends so no two destinations are at the same distance: Pipez orders
  equal-distance connections by `DirectionalPosition.hashCode`, which includes `Direction`'s identity
  hash and so differs between JVMs. The first version of this gate had such ties and gave a different
  hash in every launch.
- `rewire` (exact): `trace-rewire.java` is `transfers` with one change to the network: at round 5,
  after every extractor has sorted its connections once, a pipe and a barrel join the end of the
  ROUND_ROBIN line. Neither other gate ever changes a network, so a candidate that caches the sorted
  list and never sorts again passed both; this gate sees items keep going to the old destinations.
- `moving` (expect `true`): `count.java` checks after both captures that the live base still holds
  all 55,296 items and that items are on both sides of every row, so every line is moving them.

`sabotage-nearest-reversed.diff` sorts nearest-first connections the other way. It moves every
item, so `moving` passes, but `transfers` fails, and the run stops at the second pair, once two
baseline launches agree. `sabotage-stale-cache.diff` sorts each pipe's connections once and keeps
that list forever: `moving` and `transfers` pass, `rewire` fails.

## Result

`sorted-connections.diff` keeps each pipe's sorted connection list (nearest-first and furthest-first)
beside the connection cache it was made from, and sorts again only when that cache is replaced;
RANDOM still shuffles every time. Accepted (kept run `2026-11-15T08-39-41.055874700Z-c46937a9`,
8 pairs, both arms of each pair started together, full JIT, hidden and muted, mean system CPU 47%):

| | speedup | 95% interval |
|---|---|---|
| `itemPipeTick` cold | 1.30x | 1.08–1.58 |
| `itemPipeTick` warm | 1.20x | 1.01–1.44 |
| `getSortedConnections` cold / warm | 8.85x / 9.15x | 7.95–9.89 / 8.43–9.86 |

`transfers` and `moving` passed in all 16 launches, with the same `transfers` hash in every
baseline. The run took 21.5 min of wall time. It became the pattern
`cache-sorted-copy-until-source-replaced` in `knowledge/perf-patterns.json`. The diff was not sent
upstream.
