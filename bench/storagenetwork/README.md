# Simple Storage Network base: accept or reject a change to a real mod

A complete `perf-optimize` experiment for [Simple Storage Network](https://github.com/Lothrazar/Storage-Network)
1.13.2 (NeoForge 1.21.1, branch `trunk/1.21.1`, commit `3deabb24f282260cda30bc0d95b22a38cfd5a2ff`). It measures the
server-side cost of a storage network in a base that keeps moving items, and decides each candidate diff against frozen
and live equivalence gates.

The clone gets local commits before anything is measured, so launches load only the mod and its library and log as a
player's game does: `build.gradle`'s optional `localRuntime` test mods (JEI, Curios, Patchouli, AE2, Refined Storage,
GuideME) are commented out, and the client run's console `logLevel` is `org.slf4j.event.Level.INFO` instead of DEBUG (a
player's console is at INFO; `debug.log` still gets every DEBUG line). The mod's code is unchanged; both arms of every
run share those commits.

## The base

`scene.java` builds, once, into the save: a master, a cable spine, 8 rows of 32 barrels with a storage link cable each
(256 storage barrels, 6,912 slots, 264,344 items of 41 kinds), and an I/O station of 16 buffer barrels, each with an
export cable asking the network for one item kind and an import cable pulling the buffer back in. Both cables have a
stack upgrade and 3 speed upgrades (64 items every 7 ticks), so items circulate for as long as the world runs. The master
sees 315 nodes. In a hidden client with the full JIT, Simple Storage Network cost 3.16 ms of every server tick
(`perf`, 95% 2.75–3.57), two thirds of the tick: its master's tick re-resolves every node's capabilities through
`DimPos`, which parses its dimension string each time; each export builds a provider list from every one of the 6,912
slots, checking each slot against 18 newly built filter matchers.

## Run it

Windows uses `mmt.cmd`. From the Minecraft Modding Tools checkout:

```sh
git clone --branch trunk/1.21.1 https://github.com/Lothrazar/Storage-Network .runs/storagenetwork
git -C .runs/storagenetwork checkout 3deabb24f282260cda30bc0d95b22a38cfd5a2ff
# Comment out every localRuntime line of build.gradle, set the client run's logLevel to org.slf4j.event.Level.INFO,
# and commit that in the clone (give the clone a local git user.name and user.email first).

# Once: make the world (superflat, fixed seed, peaceful) and the base, then keep the save.
./mmt ui-check --root .runs/storagenetwork --jit full
./mmt eval --root .runs/storagenetwork --file bench/storagenetwork/create-world.java
./mmt eval --root .runs/storagenetwork --until "mc.player != null"   # wait until the new world has loaded
./mmt eval --root .runs/storagenetwork --file bench/storagenetwork/scene.java --thread control
./mmt eval --root .runs/storagenetwork --code "mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().saveEverything(true, true, true)).join()" --thread control
./mmt game-stop --root .runs/storagenetwork
mkdir -p .runs/storagenetwork-world && cp -r .runs/storagenetwork/run/saves/ssn-base .runs/storagenetwork-world/ssn-base

./mmt perf-optimize --root .runs/storagenetwork --experiment bench/storagenetwork/experiment.json \
  --patch bench/storagenetwork/dimension-key-cache.diff --runs 8 --dry-run --out .runs/storagenetwork-opt/dimension-key-cache
```

Each accepted diff was committed in the clone before the next candidate ran, so apply them in the order of the table
below. `check.java` prints the node count and item totals of a running base.

The metric is the exact inclusive wall time of the master's `TileMain.tick` per server tick (`probes.json`), cold right
after the world loads and warm 20 s later; scopes add the mod's sampled CPU and the time in the export batch, node
resolution, storage sorting, comparator, refresh and inserts.

## Gates

- `transfers` (exact): `trace.java` builds two frozen networks far from the player (x 2004 and x 2036, chunks that never
  tick) and ticks each master by hand for 130 rounds at fixed game times: 10 storage barrels behind link cables with a
  deny filter, an allow filter, an import-only link and a link that needs redstone (powered at round 50); a double chest
  with a link on each half, a furnace (the second network has a barrel there, so that every inventory is a plain vanilla
  one) and a barrel; five export cables (cobblestone twice at different priorities, dirt, iron in stock mode, oak logs
  behind an operation limit) and two import cables (one with an allow filter); a player-style insert and request every
  10 rounds; a link broken at round 33 and put back at 66, a barrel replaced by a chest at 95 and an import cable broken
  at 110. After every round every container, the network's size, comparator signal, empty slots, sorted contents and a
  cobblestone count go into a SHA-256. The mod orders equal priorities by identity hashes, which differ between JVMs,
  so every storage and export has its own priority and the two imports never run in the same round.
- `moving` (expect `true`): `count.java` rotates the 16 buffers' contents (no item made or lost), waits 2 s, and requires
  every buffer to hold only its own export item again, all 265,274 items, and all 315 nodes. In steady state an export and
  its import move the same 64 items in the same tick, so the base looks still between ticks: the rotation is what shows
  imports and exports both working.

- `components` (exact): `components.java` (from the independent verification) puts items with components in storages
  and filters (named, enchanted and damaged items, a shulker box item full of cobblestone, nbt-matching filters), with a
  shulker box block as one storage (the general export path) in one network and only barrels (the fast path) in the
  other, and ticks both by hand at fixed game times. Stacks are hashed through their codec: a component's `toString` can
  hold identity hashes.
- `chunks` (exact): `chunks.java` (from the independent verification) builds a live network whose cable spine runs from
  the master's chunk 500 to chunk 514, forces only the master's chunk, and reports whether chunks 503 and 514 stay loaded,
  the cobblestone the network shows, and what an export can still pull; then cuts the spine, lets the far chunks unload,
  and loads them again. The unmodified mod keeps every chunk holding a node loaded while the master ticks: each tick
  its capability lookups read those chunks through `getChunk(x, z, FULL, true)`, which renews a one-tick "unknown"
  ticket on each.
- `borders` (exact): `borders.java` puts two things the network reads into chunks no node is in, with only the master's
  chunk forced: the other half of a double chest storage (the unmodified mod reads it on every tick an export runs, so
  that chunk is loaded about 3 ticks in 7: `some` of 60 polls), and an export's target barrel asking for an item the
  network lacks (read only when items move: `none`). Phase A has a furnace storage (the general export path), phase B
  only vanilla barrels and chests (the fast path).

`sabotage-first-request-only.diff` breaks only the fast export path: it stops serving an item once its first request
is met, so a second export cable asking for the same item starves. `moving` still passes; `transfers` failed in both
pairs while the baseline launches agreed (kept run `2026-11-15T10-47-03.001285100Z-41421918`).
`sabotage-no-chest-neighbour.diff` is the corrected cumulative change without the double chest's other-half read:
`borders` failed in both pairs (phase A `chestHalf756=none` instead of `some`) while the baselines agreed
(`experiment-borders.json`, kept run `2026-11-15T15-24-58.311191500Z-6fc6f49c`).

## Result

Five changes were accepted one after another, each against the clone with the ones before it, then all five together
against the unmodified mod. Every run had both arms of each pair started together, the full JIT, hidden and muted
launches, and the machine busy with other work (77–96% CPU for the per-change runs, 38–89% for the corrected
cumulative run; `machine.load` in each record).

**Correction.** As first accepted, three of the five changes skipped a side effect players can see. The unmodified
master's per-tick capability lookups read the chunk of every node through `ServerChunkCache.getChunk(x, z, FULL,
true)`, which renews a one-tick "unknown" ticket on it, so the whole network stays loaded while the master ticks.
Answered from NeoForge capability caches, `tick-only-acting-nodes` made no such reads, and with only the master's chunk
loaded the far chunks unloaded: the network showed 512 cobblestone instead of 640 and an export moved 640 instead of
768 (the `chunks` gate, found by an independent verification). Two latent cases had the same cause:
`cache-target-item-handlers` skipped the read of a double chest's other half (the `borders` gate, phase A), and
`scan-providers-until-requests-met` read every export's target before each export, where the unmodified mod reads it
only when items move (`borders`, phase B: `exportTarget755s=some` instead of `none`, checked in one launch). The diffs in
this folder are the corrected ones: a cached answer first makes the chunk read the lookup made (once per node chunk per
tick for the master's node plan; before each cached node or target answer; for a double chest also the other half's
chunk), only vanilla barrel and chest answers are cached (their reads are known; other targets are looked up every
time, as before), and the export check reads no chunk. The per-change rows below were measured before the
correction; only the cumulative row was measured again.

| change (diff in this folder) | what it does | cold | warm |
|---|---|---|---|
| `dimension-key-cache` | `DimPos` parses its dimension string into a level key once per string, and builds the string once per key | 1.34x (1.23–1.51) | 1.31x (1.28–1.34) |
| `filter-check-without-matchers` | filter checks ask the 18 filter slots directly instead of building 18 matchers and a stream per call | 1.81x (1.76–1.87) | 1.82x (1.78–1.86) |
| `tick-only-acting-nodes` | the master keeps its node list, in the same order, with each node's import/export/processing capability from NeoForge capability caches, until the node set is replaced or a cache is invalidated | 1.46x (1.29–1.70) | 1.42x (1.32–1.58) |
| `cache-target-item-handlers` | each cable keeps a capability cache of its target inventory's item handler | 1.08x (1.05–1.10) | 1.12x (1.07–1.17) |
| `scan-providers-until-requests-met` | an export decides each provider slot as it reaches it and stops when every request is met, when all inventories are plain vanilla barrels and chests | 1.34x (1.20–1.52) | 1.36x (1.32–1.40) |
| **all five, corrected (`cumulative.diff`) vs the unmodified mod** | | **4.60x (4.22–4.98)** | **4.74x (3.94–5.74)** |
| all five as first accepted (superseded: lets chunks unload) | | 5.12x (4.97–5.23) | 5.95x (5.79–6.08) |

The corrected cumulative run (`2026-11-15T15-24-24.789018800Z-fa04b24c`, 3 pairs, every gate passed: `moving`,
`transfers`, `components`, `chunks`, `borders`) measured the master's tick at 2.54 → 0.56 ms per server tick cold and
3.18 → 0.68 warm on a busier machine than the first cumulative run (2.06 → 0.40 and 2.16 → 0.36); the mod's sampled
CPU fell 3.8–5.3x, the export batch 10–12x and per-tick node resolution 26–31x. The verifier's four runs of the first
cumulative diff on this machine measured cold 4.01–5.07x and warm 4.63–5.76x; the corrected 4.60x and 4.74x lie inside
that spread, so the chunk reads cost at most a part of the speedup smaller than these runs resolve. The first three accepted runs used the mod's dev console logging (DEBUG); the rest, and both cumulative runs,
log to the console at INFO as a player's game does (`debug.log` still gets every DEBUG line).

Rejected, all kept in `coverage/perf-runs.json`: `export-scan-requested-items-only` (only the requested items' providers;
0.98x cold after `filter-check-without-matchers` made the scan cheap; its first run crashed on the charset bug and its
second failed its gate because the gate was edited during the run), `sort-positions-once-per-refresh` (stopped at
3 pairs, made moot by the storage plan), `plan-storage-list-per-topology` (warm 1.36x, cold 1.13x with an interval
including 1), `replay-unchanged-network-walk` (no gain in live play). Capability caches alone measured 1.0x after the
first change and were folded into `tick-only-acting-nodes`, whose listeners need them; a candidate that copied upgrade
slots without a list (about 2% of the tick) was dropped without a run. None of the diffs was sent upstream.

What is left, in a capture of the accepted build: the per-second network walk (it must call `setChanged` on every
node, with its neighbour updates), the comparator (it counts every slot of every storage whenever contents change),
the sorted storage list, and one debug-log line per insert and export cable that a player's `debug.log` also writes.
