# Simple Storage Network base: accept or reject a change to a real mod

A complete `perf-optimize` experiment for [Simple Storage Network](https://github.com/Lothrazar/Storage-Network)
1.13.2 (NeoForge 1.21.1, branch `trunk/1.21.1`, commit `3deabb24f282260cda30bc0d95b22a38cfd5a2ff`). It measures the
server-side cost of a storage network in a base that keeps moving items, and decides each candidate diff against frozen
and live equivalence gates.

The clone gets one local commit before anything is measured, so launches load only the mod and its library:
`build.gradle`'s optional `localRuntime` test mods (JEI, Curios, Patchouli, AE2, Refined Storage, GuideME) are commented
out. The mod's code is unchanged; both arms of every run share that commit.

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

Windows uses `modaccel.cmd`. From the Mod Accel checkout:

```sh
git clone --branch trunk/1.21.1 https://github.com/Lothrazar/Storage-Network .runs/storagenetwork
git -C .runs/storagenetwork checkout 3deabb24f282260cda30bc0d95b22a38cfd5a2ff
# Comment out every localRuntime line of build.gradle and commit that in the clone.

# Once: make the world (superflat, fixed seed, peaceful) and the base, then keep the save.
./modaccel ui-check --root .runs/storagenetwork --jit full
./modaccel eval --root .runs/storagenetwork --file bench/storagenetwork/create-world.java
./modaccel eval --root .runs/storagenetwork --file bench/storagenetwork/scene.java --thread control
./modaccel eval --root .runs/storagenetwork --code "mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().saveEverything(true, true, true)).join()" --thread control
./modaccel game-stop --root .runs/storagenetwork
mkdir -p .runs/storagenetwork-world && cp -r .runs/storagenetwork/run/saves/ssn-base .runs/storagenetwork-world/ssn-base

./modaccel perf-optimize --root .runs/storagenetwork --experiment bench/storagenetwork/experiment.json \
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

`sabotage-first-request-only.diff` breaks only the fast export path: it stops serving an item once its first request
is met, so a second export cable asking for the same item starves. `moving` still passes; `transfers` failed in both
pairs while the baseline launches agreed (kept run `2026-11-15T10-47-03.001285100Z-41421918`).

## Result

Five changes were accepted one after another, each against the clone with the ones before it, then all five together
against the unmodified mod. Every run had both arms of each pair started together, the full JIT, hidden and muted
launches, and the machine at 77–96% CPU from other work (`machine.load` in each record).

| change (diff in this folder) | what it does | cold | warm |
|---|---|---|---|
| `dimension-key-cache` | `DimPos` parses its dimension string into a level key once per string, and builds the string once per key | 1.34x (1.23–1.51) | 1.31x (1.28–1.34) |
| `filter-check-without-matchers` | filter checks ask the 18 filter slots directly instead of building 18 matchers and a stream per call | 1.81x (1.76–1.87) | 1.82x (1.78–1.86) |
| `tick-only-acting-nodes` | the master keeps its node list, in the same order, with each node's import/export/processing capability from NeoForge capability caches, until the node set is replaced or a cache is invalidated | 1.46x (1.29–1.70) | 1.42x (1.32–1.58) |
| `cache-target-item-handlers` | each cable keeps a capability cache of its target inventory's item handler | 1.08x (1.05–1.10) | 1.12x (1.07–1.17) |
| `scan-providers-until-requests-met` | an export decides each provider slot as it reaches it and stops when every request is met, when all inventories are plain vanilla barrels and chests | 1.34x (1.20–1.52) | 1.36x (1.32–1.40) |
| **all five (`cumulative.diff`) vs the unmodified mod** | | **5.12x (4.97–5.23)** | **5.95x (5.79–6.08)** |

The cumulative run (`2026-11-15T12-01-21.384063400Z-18bda471`) measured the master's tick at 2.06 → 0.40 ms per server
tick cold and 2.16 → 0.36 warm; the mod's sampled CPU fell 6.0x, the export batch 12–13x and per-tick node resolution
30x. The first three accepted runs used the mod's dev console logging (DEBUG); the rest, and the cumulative run, log
to the console at INFO as a player's game does (`debug.log` still gets every DEBUG line).

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
