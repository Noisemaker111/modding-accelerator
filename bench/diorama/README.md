# Diorama CPU performance experiments

These snippets measure the real Diorama implementations inside its development
client through `modaccel eval`. They target Diorama 0.10.1, commit
`7aa1484cd5dfe4ec19d246a58fb23bbc48a8bc6e`, on Fabric / Minecraft 1.21.1.
They require that source checkout and its JDK 25 build environment.

## Measure ordinary play and decide a change

`play/` measures what Diorama costs while a player flies over new land with the minimap
showing, in hidden test clients, and decides a candidate patch with `modaccel perf-optimize`.

```sh
git clone https://github.com/SunkenInTime/diorama .runs/diorama
git -C .runs/diorama checkout 7aa1484cd5dfe4ec19d246a58fb23bbc48a8bc6e
python bench/diorama/play/prepare_world.py --root .runs/diorama
./modaccel perf-optimize --root .runs/diorama --experiment bench/diorama/play/experiment.json \
  --patch CANDIDATE.diff --runs 6 --label NAME --session NAME --dry-run
```

- `prepare_world.py` creates the save every launch copies (`.runs/diorama-play/diorama-play`):
  `create-world.java` (fixed seed, creative, no mobs, time and weather frozen), then one
  flight over the whole path so every chunk is generated and saved. Every region file the
  experiment's `files` copies gets its chunk nearest the origin generated too (`r.-1.1` is never
  reached by the flight), and the copied save is checked for each of them.
- Each launch starts with Diorama's map folder deleted (`reset`) and the production JIT
  (`"jit": "full"`) and turns server saving off (`save-off.java`). Right before the cold capture
  `fly.java` flies east for 30 s, right before the warm capture south for 30 s, at 16 blocks/s
  (`measure.before`), so each 60 s window holds its whole flight and 30 s of settling.
- The metric is the bakers' CPU time: what the operating system counted on the
  `diorama-baker-*` threads over the window (`{"threadCpu": "^diorama-baker"}`, from
  ThreadMXBean at the window's start and end). It is exact, and a baker preempted by other load
  counts nothing; the sampled CPU the earlier runs decided on (`liveMappingSampledCpu`, execution
  samples on the bakers and the render thread) also counts a preempted thread in Java code. The
  save import takes whatever time the bakers leave, so in a fixed window it hides savings; it and
  every Diorama thread are scopes. `bakerMsPerMesh` divides the bakers' time by the meshes built,
  for windows where a faster build finishes more of them.
- Scopes for the player: the render thread's and server thread's CPU time, the server tick, the
  young-collection pause (`gcPauseMeanMs`), all pause time and the mean heap, and minimap render,
  radar, waypoint layout, session tick and map update as mean milliseconds per call (`scopes.json`
  probes). Inclusive sampled CPU of the GPU mesher, spawn spots, tint blends, shadow casters, the
  save import, tile writes, mipmaps and the fog mask shows where a change landed.
- Gates: `bake-digest.java` digests the GPU mesh, the traced image (full and partial over saved
  pixels), every mip level, spawn spots with and without saved spots, the GPU merge and the
  tints of six tiles along the path, read from the experiment's own frozen copy of the region
  files (`files`), so it is identical between launches. `tints-live.java` requires every tint
  the live map holds to equal a fresh blend; `mapped.java` requires the flight to have ended
  with GPU-baked tiles; `reuse-live.java` requires everything a build carries between bakes to
  change nothing (below); `slabs-ring.java` requires a build that keeps those in a ring of arrays
  to have gone round it during the flight and to read nothing stale once it goes round again.
- `bake-bench.java` times those functions in one client (descriptive), and `toggle-bench.java`
  compares switchable variants of one build in the same JVM with randomized order.

### Exact optimizations for ordinary play

Apply in order to a clean checkout of the pinned commit; together they are the change
`perf-optimize` accepted (its run is in `coverage/perf-runs.json`, summarised by
`python bench/perf_sessions.py --session diorama-10x`):

```sh
git -C DIORAMA_SOURCE apply bench/diorama/tint-carry-over.patch
git -C DIORAMA_SOURCE apply bench/diorama/dirty-only-gpu-spots.patch
```

- `tint-carry-over.patch`: a chunk snapshot that replaces another (light updates do this all the
  time) adopts its blended biome tints when everything the blend reads is identical: the same
  biome containers for the chunk and its neighbours, column tops, zoom seed, blend radius and
  colour resolvers. Tint blends fall about threefold.
- `dirty-only-gpu-spots.patch`: GPU bakes draw whole tiles, and the spawn-spot pass after them
  re-picked every sample. When a tile's stored spots came from its previous live bake (same
  store, colour generation and ray start height), only the dirty blocks are picked again. An
  instrumented build that also ran the whole pick found 444 of 444 incremental picks identical.

Measured with six paired launches (`experiment.json`, production JIT, hidden clients):

| Scope | Cold | Warm |
|---|---|---|
| Live mapping CPU (decision metric) | 1.38× (95% 1.09–1.86) | 1.32× (1.02–1.77) |
| All Diorama threads | 1.34× (1.00–1.92) | 1.27× (1.02–1.57) |
| Spawn spots | 1.85× (1.32–2.70) | 1.75× (1.43–2.15) |
| Tint blends | 3.16× (2.52–4.11) | 2.71× (2.31–3.33) |
| GPU mesher | 1.24× (0.84–1.97) | 1.20× (0.94–1.54) |

Every gate was identical in every launch. Minimap drawing, radar and waypoint layout cost well
under a millisecond a call before and after and are not changed.

### Reuse what a re-mesh would work out again

A tile is meshed about ten times while the land around it loads, and each mesh walked every
column again. Measured over the flight, 79% of a re-mesh's columns and 80% of its shadow-caster
columns came out exactly as the tile's mesh before; 39% of re-meshes were identical throughout.
Apply after the two patches above:

```sh
git -C DIORAMA_SOURCE apply bench/diorama/column-reuse.patch
```

- Snapshots carry tokens for their blocks, block light and sky light. A snapshot that replaces
  one with the same blocks (every section the same block, or the same packed indices and
  palette, the same column tops, no copycats) takes over its blocks token; likewise each light
  token where every light section is equal. The first bake thread that asks compares the two,
  not the client thread, and a token never changes once read. Anything worked out from blocks
  alone is then shared by every snapshot with that token.
- `ChunkBakeData`, per blocks token and looks: the solid masks the readers build, the chunk's
  shadow-caster faces in one array (with the west and south neighbours' tokens where its edge
  columns read them) and the height from which each column is all air. Kept as an object per
  column, the caster faces were half a million long-lived objects that made every young
  collection 3 ms longer; as one per chunk the heap after a flight has as many objects as before.
- `MeshColumns`: each tile keeps, per column of x, the camera faces its walk added and the
  chunks it read, by blocks and light tokens and tint arrays. A column is taken again only when
  the tile's settings (looks, style, overlays, projection, ray start and limit) are equal and
  every one of those chunks is: the same tokens and equal tints. A column that reached a chunk
  the map has no data for also keeps the saved spots it read there, which must be equal too.
  The tile's caster faces are kept per x the same way, by the blocks tokens of x and x − 1.
- `SpotColumns`: in the sky view a spawn-spot ray reads only blocks of its own column, so each
  column's spots are kept with the chunks the rays read and, where those were missing, the saved
  spots; whole and dirty-block picks take the columns that still match.
- Rows of a column that are all air from their south end on are reached throughout and add
  only their extent, so they are taken in one step; below them the walk visits only the cells
  the row above reached and an unbroken run north of them. Kept faces are packed (one int for
  position and face, a box only when it isn't the whole cube): 16 MB each for camera faces and
  casters and 8 MB for spots hold a flight's worth of tiles.
- One column's walk is a method of its own. In `build` the new branches had not been taken when
  C2 first compiled it, and the uncommon traps that followed sent the whole 4.6 KB method back
  to the interpreter: the first paired run of these changes spent 343–757 samples per capture
  interpreting it, against 5–94 unchanged, and was rejected (cold 1.18×, 95% 0.98–1.39).
- Switches (`VoxelReader.SHARED`, `GpuMesher.REUSE`, `GpuMesher.AIR_ROWS`) exist so the gate
  can compare; they default on.

`reuse-live.java` is the gate for anything carried between bakes: after the flight it meshes
every live surface tile and picks its spots (whole, and every fourth block over saved spots)
from the live map's own snapshots, with every switch off and twice on, and requires identical
results. Corrupting a live tile's kept mesh columns, caster rows or spot columns makes it fail.
`bake-digest.java` stays `ac6a8694…` in every build.

Six paired launches against the two patches above decided it (`column-reuse-caster-chunks` in
`coverage/perf-runs.json`); six more measured all three patches against unmodified Diorama
(`all-patches-vs-unmodified`). Every gate was identical in every launch of both.

| Scope | This patch, cold | warm | All three patches, cold | warm |
|---|---|---|---|---|
| Live mapping CPU (decision metric) | 1.25× (95% 1.03–1.52) | 1.91× (1.57–2.24) | 1.61× (1.48–1.76) | 1.96× (1.58–2.41) |
| Bakers' thread CPU time | 1.11× (0.99–1.23) | 1.67× (1.45–1.98) | 1.46× (1.38–1.57) | 1.85× (1.56–2.19) |
| All Diorama threads | 1.16× (0.95–1.42) | 1.64× (1.32–2.00) | 1.39× (1.30–1.52) | 1.69× (1.36–2.04) |
| GPU mesher | 1.30× (0.98–1.68) | 1.96× (1.57–2.39) | 1.53× (1.38–1.70) | 1.97× (1.52–2.48) |
| Shadow casters | 3.13× (2.61–3.78) | 4.07× (3.09–5.27) | 2.56× (2.19–2.97) | 3.45× (2.80–4.05) |
| Spawn spots | 1.21× (0.99–1.49) | 1.39× (1.00–1.85) | 1.82× (1.59–2.09) | 1.87× (1.56–2.31) |
| Tint blends | 0.97× (0.76–1.23) | 1.04× (0.77–1.27) | 3.11× (2.57–3.68) | 2.16× (1.84–2.47) |
| Minimap drawing, ms a call | 1.08× (1.00–1.19) | 1.02× (0.93–1.10) | 1.06× (0.87–1.28) | 0.99× (0.91–1.07) |
| Session tick, ms a call | 0.98× (0.86–1.09) | 1.02× (0.74–1.32) | 0.94× (0.85–1.02) | 0.77× (0.63–0.94) |
| Map update, ms a call | 1.05× (0.95–1.19) | 1.08× (0.93–1.22) | 1.00× (0.92–1.07) | 0.75× (0.56–0.94) |

The cold window is mostly the first mesh of tiles nobody has meshed, which nothing reuses; the
warm one re-meshes them. Three things to weigh before distributing:

- The decision metric counts JFR execution samples, and JFR samples a thread in Java code
  whether or not it is on a CPU: on a saturated machine a preempted baker's wait counts too.
  The bakers' operating-system CPU time (`jdk.ThreadCPULoad`) shows smaller gains, above.
- What is kept costs heap: about 120 MB more after a flight, all of it primitive arrays, and
  young collections pause about 2–3 ms longer (17.0 against 14.3 ms in the run against
  unmodified). Kept as an object per column, the caster cache made the first accepted version's
  minimap and radar calls measurably slower; as one array per chunk they are not, against the
  two patches above. Against unmodified Diorama the session tick and map update were slower in
  the warm captures; that run shared the machine with another paired run.
- Hit rates during the flight: 70% of camera columns, 78% of caster rows and 73% of spot
  columns came from the bake before; no tile was evicted from the budgets.

### Decide on the bakers' CPU time

The runs above decided on execution samples, which count a baker preempted in Java code as
busy. Decided again on what the operating system counted on the baker threads (six paired
launches of the three patches against unmodified Diorama, `all3-vs-unmodified-cputime` in
`coverage/perf-runs.json`; every gate identical in every launch):

| Scope | Cold | Warm |
|---|---|---|
| Bakers' CPU time (decision metric) | 1.61× (95% 1.34–1.99) | 2.21× (2.05–2.35) |
| Bakers' CPU time per mesh built | 1.77× (1.38–2.44) | 2.19× (2.08–2.31) |
| Sampled live-mapping CPU (the old metric) | 1.58× (1.41–1.80) | 2.22× (1.99–2.43) |
| Every Diorama thread's CPU time | 1.40× (1.20–1.67) | 1.65× (1.62–1.70) |

On this run the two metrics agree; the machine was less loaded than in #78's. This round's runs,
kept and rejected, are session `diorama-round3` (`python bench/perf_sessions.py --session
diorama-round3`). In the cold window
the patched bakers keep up with the flight and build 10% more meshes, so per mesh the gain is
larger than per window.

### The player's tick and the collector

Against unmodified Diorama, with the three patches (same run):

- The render thread's and the server thread's CPU time are unchanged (cold 0.96×, 0.93–1.00;
  warm 1.01×, 0.98–1.03; server 0.99× and 1.01×). Session tick and map update, per call, are
  0.99× (0.83–1.19) and 1.04× (0.79–1.46) cold, 1.09× (0.97–1.30) and 0.99× (0.91–1.06) warm:
  the warm slowdowns #78 measured on a shared machine were noise. Where a pair's map update was
  slower it uploaded more tiles: the patched build finishes 11% more of them in the cold window,
  and the map update per uploaded tile is unchanged (0.99× warm). Sampled CPU under the session
  tick was the same in both builds; the extra wall time of #78's slow pairs was in GL calls and
  in rare long calls, not in Diorama's work.
- The collector is a real cost: young pauses were 19% longer cold and 33% longer warm
  (`gcPauseMeanMs` 0.84×, 0.81–0.88; 0.75×, 0.70–0.81) and the mean heap 9% larger. Total pause
  time stayed level (0.94×, 0.97×, both including 1) because there were fewer, longer pauses.
  Each young collection copied 10–22% more bytes: what the bakes keep (kept columns, caster rows,
  spot columns) lives for seconds, so it is copied from one survivor space to the next before it
  is promoted.

### Keep carried-over ints in a ring of large arrays

Apply after the three patches above:

```sh
git -C DIORAMA_SOURCE apply bench/diorama/slab-ring.patch
```

- `Slabs` keeps every int the kept columns, caster rows and spot columns hold (faces, spots, saved
  spots) in a ring of arrays, each one collector region long (8 MB for the game's 16 GB heap, at
  least 4 MB), 40 MB in all, what those caches were allowed before. `G1` allocates such an array
  straight into the old generation and never moves it, so no young collection copies kept data.
  A kept record holds a run (array, offset, length, the pass that wrote it) instead of an array.
- Each pass over an array has a serial; tiles whose ints are in a pass older than the ring are
  dropped, and a run in one of the two passes that leave next is copied forward when a bake takes
  it again. A bake that took a tile just before its array is written over may still be reading
  it, so every read is checked afterwards against the array's serial (as `StampedLock` readers
  do) and, if the array was written over meanwhile, the column is walked or picked instead and
  what was copied is dropped. Kept records are never written once a tile holds them; a record
  copied forward is a new record.
- The first version allocated a new array whenever one filled. It shortened young pauses as much,
  but each allocation of so large an array started a young collection and a concurrent cycle
  (8 and 8 in a cold window): total pause time went up. Its run ended when the machine ran out of
  memory (`kept-ints-in-slabs`, two pairs). The ring allocates each array once.
- `slabs-ring.java` (gate, run last) requires the flight to have gone round the ring at least once,
  so `reuse-live.java` compared tiles while arrays were being written over under them, then goes
  round once more and requires a run kept before to read as stale and every live tile to mesh and
  pick as it does with reuse off.

Four paired launches against the three patches (`slab-ring` in `coverage/perf-runs.json`; every
gate identical in every launch). Not a speedup, and not accepted by the speed rule; it removes the
collector's cost:

| Scope | Cold | Warm |
|---|---|---|
| Young-collection pause | 1.12× shorter (1.10–1.13) | 1.19× shorter (1.13–1.24) |
| Mean heap | 1.03× smaller (0.99–1.08) | 1.08× smaller (1.03–1.12) |
| All pause time | 1.05× (0.95–1.17) | 1.04× (0.99–1.10) |
| Bakers' CPU time | 1.16× (1.07–1.25) | 1.04× (0.98–1.16) |
| Render thread CPU time | 0.97× (0.94–0.99) | 0.98× (0.94–1.02) |

The render thread draws each GPU bake; the ring's arm did 3% more of them in the cold window,
and its render time per GPU bake was the same.

### All four against unmodified Diorama

Six paired launches of the three patches and `slab-ring.patch` against unmodified Diorama
(`all-four-vs-unmodified`, accepted; every gate identical in every launch). The three patches'
own run (above) is in the first columns; the two runs are separate sessions, so their baselines
differ (the unmodified bakers used 504 and 474 ms a second warm), and against the three patches
directly the ring changed the bakers' time by 1.16× cold and 1.04× warm (0.98–1.16).

| Scope | Three patches, cold | warm | All four, cold | warm |
|---|---|---|---|---|
| Bakers' CPU time (decision metric) | 1.61× (1.34–1.99) | 2.21× (2.05–2.35) | 1.62× (1.20–2.22) | 1.93× (1.86–2.01) |
| Bakers' CPU time per mesh | 1.77× (1.38–2.44) | 2.19× (2.08–2.31) | 1.77× (1.18–2.74) | 2.00× (1.93–2.07) |
| Every Diorama thread's CPU time | 1.40× (1.20–1.67) | 1.65× (1.62–1.70) | 1.39× (1.10–1.79) | 1.45× (1.39–1.51) |
| Render thread CPU time | 0.96× (0.93–1.00) | 1.01× (0.98–1.03) | 0.97× (0.92–1.01) | 0.97× (0.95–1.00) |
| Server thread CPU time | 0.99× (0.95–1.06) | 1.01× (0.96–1.08) | 1.01× (0.97–1.05) | 0.99× (0.93–1.06) |
| Young-collection pause | 0.84× (0.81–0.88) | 0.75× (0.70–0.81) | 0.94× (0.91–0.98) | 0.93× (0.87–0.98) |
| All pause time | 0.94× (0.86–1.02) | 0.97× (0.90–1.06) | 0.94× (0.87–1.04) | 1.02× (0.93–1.16) |
| Mean heap | 0.91× (0.89–0.94) | 0.91× (0.88–0.94) | 0.96× (0.95–0.98) | 0.93× (0.91–0.95) |
| Session tick, ms a call | 0.99× (0.83–1.19) | 1.09× (0.97–1.30) | 0.88× (0.72–1.02) | 0.95× (0.93–0.96) |
| Map update, ms a call | 1.04× (0.79–1.46) | 0.99× (0.91–1.06) | 1.00× (0.89–1.13) | 0.94× (0.92–0.97) |
| Shadow casters (sampled) | 2.44× (2.10–2.77) | 3.87× (3.45–4.22) | 2.17× (1.87–2.48) | 3.73× (3.40–4.08) |
| Spawn spots (sampled) | 1.62× (1.43–1.83) | 2.04× (1.76–2.31) | 1.50× (1.23–1.81) | 1.73× (1.59–1.88) |
| Tint blends (sampled) | 2.53× (2.16–2.96) | 2.69× (2.37–3.03) | 2.51× (2.14–2.88) | 2.29× (2.18–2.41) |

Above 1 is better for the candidate. With all four, young pauses are still 6–7% longer than
unmodified Diorama's and the heap 4–7% larger (from 19–33% and 9% with the three patches): what
the per-chunk `ChunkBakeData` keeps is still small arrays. In the warm window the patched arm
finished 3.4% more GPU bakes and uploads; the render thread's CPU time grew no faster than the
GPU bakes it drew (21.2 against 21.0 ms per GPU bake in the window) and the map update per
uploaded tile was 0.97× (0.95–1.00), so the warm session
tick and map update, 5–6% slower per call, did more work rather than the same work slower.

### Tried and rejected this round

- `sky-free.patch`: a kept column no longer depends on its chunks' sky light, which in the views
  kept only the spawn-spot overlay reads (off by default, and one of the tile's settings). Re-meshes
  took more columns (warm 1.08×, 95% 1.03–1.15), but the cold window was slower (0.80×, 0.64–0.99,
  five pairs of six): with more columns taken, the column walk gets hot later and C2 compiles it
  later, so more of the first minute runs in slower tiers. Exact (every gate identical).
- `pick-rows.patch`: every ray of a pixel row steps through the same cells whatever its x, so the
  steps were worked out once per row, and `passedWhenSaved` answers were remembered per cell. Picks
  came out 1.7× slower (`spotsCpu` 0.60×, 0.57×; bakers 0.79×, 0.83×): a boxed `HashMap` lookup per
  ray and per unknown cell costs more than the floating-point steps it replaced, which the
  sampled line numbers had overstated.

### The cold window is the JIT's first minute

Cold pairs vary far more than warm ones, even on exact CPU time (sky-free's cold pairs ranged
0.56–1.20×). In a slow pair the bakers' samples are in the interpreter: 728 interpreted top frames
against 88 in its baseline, in `TileBaker.bake`, `TileBaker.trace` and `GpuMesher.build`, while the
traced check bakes and the first meshes run before C2 has compiled them. A change that makes
hot code run less often can make the first minute slower. Count interpreted frames before reading
a cold verdict, and expect a small change to show only in the warm window.

### What is left

Per mesh, the bakers spend about 55% in the column walk, 18–26% in spawn-spot picks, 10% in shadow
casters, 7% blending tints of new chunks and 5% each in the GPU image merge and traced bakes. What
has been tried makes re-meshes cheap; the first mesh of a new tile is untouched:

- the collector: `ChunkBakeData`'s per-chunk arrays (solid masks, caster faces, open heights) in
  the ring too, with validated reads, would take the last 6–7% off young pauses;

- the column walk visits every cell rays reach one at a time, with per-cell rock, water and fog
  bookkeeping the sky view never needs and twelve extent updates. Rows of air and whole cubes could
  be walked as bit masks, 64 cells at a time (reach propagates north along a row as a carry does),
  leaving only faces to visit one at a time;
- picks: the same per-row steps, kept in a primitive table indexed by row rather than a map;
- the per-row clearing of five arrays over the whole span and the extent updates could be per row
  (about 5% of the walk, exact);
- reuse that tolerates a chunk's sky light, decided per cell rather than per chunk, once the cold
  window's JIT effect is understood (or warmed: run the cold capture after a short flight).

### Time to safepoint on a busy machine

Long pauses before a safepoint (a garbage collection, a deoptimization) come from the machine,
not a build. In #72's 24 captures, below 0.97 machine load both builds reached safepoints in a
median 0.03 ms with almost no pause over 100 ms; at full load both took milliseconds (baseline
median 2.7 ms, 12 of 183 over 100 ms; patched 7.1 ms, 61 of 337), the patched build simply met
full load more often. The game runs JDK 25 with G1, so C2 keeps a safepoint poll in every 1000
iterations of a counted loop: no loop runs without one. Diorama's bakers, save import and tile
writer run at Java priority 1–2, Windows' lowest. When other programs keep every core busy, a
baker preempted in Java code waits for the scheduler's starvation boost before it reaches its
poll, and the whole game, render thread included, waits at the safepoint for it.

`normal-priority-workers.patch` (after `column-reuse.patch`) runs the bakers, the save import and
the tile writer at normal priority; there are at most a third as many bakers as cores. In three
paired launches no baseline or candidate safepoint took over 100 ms below full load; at full
load 2 of 37 baseline safepoints did and 0 of 33 candidate ones (medians 0.131 against
0.057 ms). The machine reached full load in only part of those launches, so this is thin
evidence. `perf-optimize` accepted it (1.37× cold, 1.34× warm), but that is the metric's
preemption count: the bakers' thread CPU time did not change (1.11×, 0.96×). It is a trade:
at normal priority the bakers also compete with whatever else the player runs.

Hidden clients present about 8 frames a second whatever `maxFps` and vsync say, so frame-driven
work (minimap drawing, GPU tile readback) runs less often than in a visible game; the per-call
render scopes stay comparable, totals per second do not. Measure frames visibly with `perf`.

Start a development client with `modaccel perf --root DIORAMA_SOURCE
--project :1.21.1`, enter a **disposable mapped world**, and allow initial mapping
to settle. Then, from the Mod Accel checkout:

```sh
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/bake.java --json
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/mipmaps.java --json
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/density.java --json
```

Windows uses `modaccel.cmd`. Each result's `value` is a JSON string containing
the samples. Run in that order: the bake experiment retains a real terrain tile
in the session for the mipmap comparison. These temporarily block the render
thread; resulting frame stalls are **benchmark-induced**, not ordinary gameplay.

- `bake.java`: preflights a 3×3 neighborhood and selects up to three tiles with
  at least 5% mapped coverage, then alternates Fancy/Fast CPU bakes with two warmup
  rounds and six measured rounds. Reports coverage and pixel hashes. Restores
  the original quality flag. Fast deliberately changes visual quality.
- `mipmaps.java`: the upstream alpha-weighted mip builder versus an opaque-pixel
  fast-path candidate. Checks one million pixel tuples and every mip pixel for
  synthetic inputs and captured terrain. Warms both implementations and
  randomizes their order in twelve measured rounds. Does not patch Diorama.
- `density.java`: radar collection for 0/64/256/1024 client-only item entities,
  with 1/64 items per stack; waypoint layout for 0/120/1000/5000 unsaved pins.
  Verifies fixture counts and removes temporary entities in `finally`.

`opaque-mipmaps.patch` is an experimental patch for that Diorama commit. Run the
comparison against the unmodified source first; applying it changes the baseline
that `mipmaps.java` calls. Apply only in a separate Diorama checkout with
`git apply /path/to/opaque-mipmaps.patch`, build through modaccel, and validate the
target game versions before distributing a modified mod. This repository does
not publish or replace Diorama releases.

Capture ordinary rendering separately with `modaccel perf`. Record the world,
camera, map view, quality, renderer, JVM, and loaded mods with each comparison.
Same-JVM timings are descriptive, not independent-trial regression evidence.
CPU collection/layout excludes icon drawing, entity ticking, and networking.
Software-rendered frames cannot establish hardware GPU performance.

For per-function wall time during ordinary play, use `timing.json` with the same
pinned development source. It selects the full tile bake, pixel bake, mipmap build,
radar collection and waypoint layout. These are coarse scopes; ray-by-ray timing
would add substantial overhead to the hottest inner loop.

```sh
./modaccel perf --root DIORAMA_SOURCE --project :1.21.1 --probes bench/diorama/timing.json --duration 15
```

The report includes inclusive/self milliseconds, completed call counts, exceptional
exits, nearest timed callers and a downloadable nested-call timeline. Allow the
world to load before capturing; move into unmapped terrain to exercise baking.
Compare an equivalent capture without probes to assess overhead. These selectors
use development names; an installed remapped mod may require different descriptors.
See [function timing semantics](../../PERFORMANCE.md#custom-mechanics-and-deeper-probes).

Diorama's prescribed visual regression workflow is separate. These experiments
do not replace it or claim exhaustive coverage of caves, dimensions, terrain,
mod compatibility, GPU baking, web maps, or saved-map behavior.

## Compare the CPU air-run optimization

`air-runs.patch` is an experimental optimization for the pinned commit above.
It walks consecutive air cells directly inside the current decoded section,
preserving ray-step arithmetic and boundary decisions. It also reuses the last
face's ambient-occlusion corners within each bake; subpixel interpolation remains
unchanged. It adds no cross-bake cache or change in image quality.

Prepare a clean, disposable Diorama checkout with an unchanged reference baker:

```sh
python3 bench/diorama/prepare_comparison.py --root DIORAMA_SOURCE --patch bench/diorama/air-runs.patch
./modaccel game-stop --root DIORAMA_SOURCE --project :1.21.1
./modaccel compile --root DIORAMA_SOURCE --project :1.21.1
./modaccel world-check --root DIORAMA_SOURCE --project :1.21.1 --world DISPOSABLE_WORLD
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/compare.java
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/equivalence.java
```

Wait until the world is mapped. The comparison requires a tile with at least 50%
known pixels. It freezes chunk references, retains up to two terrain-heavy tiles,
warms both bakers, and randomizes their order for eight measured rounds per quality.
Every pair checks all mip pixels, coverage and auxiliary arrays. A mismatch fails
the experiment. The result preserves individual timing samples and restores quality
settings. The all-seen source policy measures rendering rather than fog discovery.
Run ordinary frame captures separately: this experiment blocks the render thread.

`equivalence.java` checks 32 combinations: four map facings, both quality settings,
and full surface tiles, partial updates, a cave slice, or an artificial roof/fog
policy over the captured Overworld chunks. Overlay and light arrays are compared
alongside pixels. This is not a real Nether fixture or exhaustive modded-block test.

The reference has independent copies of TileBaker, VoxelReader and CaveCut from
the pinned commit. Other supporting classes, including mip generation, are shared;
this comparison does not isolate arbitrary changes to those helpers. Same-JVM timings do not establish hardware FPS
gains, universal speedups, or independent-process confidence. Repeat fresh launches
and test representative terrain and modded content before distributing a build.

Remove the reference before packaging the modified mod:

```sh
python3 bench/diorama/prepare_comparison.py --root DIORAMA_SOURCE --remove-reference
./modaccel compile --root DIORAMA_SOURCE --project :1.21.1 --task :1.21.1:remapJar
```

The cleanup refuses to delete an edited reference and leaves the candidate changes
in place. Keep any distributed build clearly labeled as modified and experimental.

## Compare coherent ray and shading work

`coherent-bake.patch` is an alternative complete patch for the same clean pinned
checkout. It includes the air-run changes above, reuses exact camera-ray state for
adjacent samples with the same initial air path, retains section masks for shadow
rays, and caches repeated face shading and completed height-bound scans. A bounded
per-baker saved-spot cache avoids repeating the same missing-chunk lookup; projection
angle changes invalidate that cache. It does not reduce sample count or image size.
Do not stack this patch on top of `air-runs.patch`.

Use the preparation/build commands above with `--patch bench/diorama/coherent-bake.patch`.
Run `compare.java` for mapped terrain and `compare-saved.java` for saved terrain with
an artificial missing-chunk policy. The latter deliberately hides captured chunks;
its result is a separate workload, not a universal speedup. Both retain raw timing
samples and compare original and candidate output. Run `compare-saved.java` with
`fast = false` and `fast = true` as separate evals. Repeat in fresh JVM launches.

Additional differential checks:

```sh
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/equivalence-boundaries.java
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/equivalence-fixture.java
```

Run `equivalence-boundaries.java` with each `facingIndex` from 0 through 3. It checks
partial updates, saved pixels/light/spots, missing chunks, fog and a depth cave.
Its output reports which inputs were actually exercised, including negative chunk
coordinates and nonzero light. It changes policies between bakes of frozen snapshots;
it does not simulate live block mutation.

Run `equivalence-fixture.java` with each `scenario` from 0 through 5. It creates private
snapshots containing water, glass, slabs and glowstone, checks full and partial images,
and requires each material to affect the image when replaced by stone. It never writes
to the game world. Also repeat surface scenario 0 with `styleIndex` 1 through 3 for
low/high camera pitch and alternate shading. Synthetic cave/roof cases use fixed lighting and biome tints; they
do not substitute for representative dimensions, modded content or moving-camera tests.

Equality means zero differences in the compared arrays, including every mip level,
coverage, light, overlay metadata and patch coordinates. Passing finite cases cannot
prove all-world equivalence. Caches rely on the existing per-bake immutable snapshot,
looks and saved-data model; these are not cross-bake caches.

## Check GPU identity and the remaining budget

`gpu_exact.java` queues a GPU bake and polls it on a later invocation. Leave frames
running between calls. It reports exact pixel/channel and auxiliary-array mismatch
counts, rather than the upstream GPU check's tolerance. A disabled or stalled GPU is
reported as unavailable. Run with GPU baking enabled on the target hardware. This
checks one surface tile without fog or saved data and makes no speed claim.

```sh
./modaccel eval --root DIORAMA_SOURCE --project :1.21.1 --file bench/diorama/gpu_exact.java
# Allow frames to run; then repeat the same command to retrieve the result.
python3 bench/diorama/budget.py CAPTURE/report.json --study PAIRED_SAMPLES.json --target 100
```

The budget calculator accepts the decoded JSON `value` from `compare.java` or the
older study's `bake` samples. It reports the per-case time budget and a clearly labeled
sample-based Amdahl illustration. CPU samples do not measure GPU time or establish an
FPS ceiling. A 100× full-workload claim requires the same work and output in 1% of the
baseline time; cached reuse, smaller updates and lower quality are different comparisons.
