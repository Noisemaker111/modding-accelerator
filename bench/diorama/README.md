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
  flight over the whole path so every chunk is generated and saved.
- Each launch starts with Diorama's map folder deleted (`reset`) and the production JIT
  (`"jit": "full"`) and turns server saving off (`save-off.java`). Right before the cold capture
  `fly.java` flies east for 30 s, right before the warm capture south for 30 s, at 16 blocks/s
  (`measure.before`), so each 60 s window holds its whole flight and 30 s of settling.
- The metric is Diorama's live-mapping CPU: its baker threads and the render thread. The save
  import is a background job that takes whatever time the bakers leave, so in a fixed window it
  hides savings; it and Diorama's all-thread CPU are scopes. Scopes also report the GPU mesher,
  spawn spots, tint blends, shadow casters, the save import, tile writes, mipmaps and the
  fog mask as inclusive sampled CPU, and minimap render, radar, waypoint layout, session tick
  and map update as mean milliseconds per call (`scopes.json` probes).
- Gates: `bake-digest.java` digests the GPU mesh, the traced image (full and partial over saved
  pixels), every mip level, spawn spots with and without saved spots, the GPU merge and the
  tints of six tiles along the path, read from the experiment's own frozen copy of the region
  files (`files`), so it is identical between launches. `tints-live.java` requires every tint
  the live map holds to equal a fresh blend; `mapped.java` requires the flight to have ended
  with GPU-baked tiles.
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
under a millisecond a call before and after and are not changed. On a machine at full load the
patched build showed more long time-to-safepoint pauses on baker threads in some runs (0–12 over
250 ms per flight, against 1–2 unpatched); confirm on an idle machine before distributing.

Hidden clients present about 8 frames a second whatever `maxFps` and vsync say, so frame-driven
work (minimap drawing, GPU tile readback) runs less often than in a visible game; the per-call
render scopes stay comparable, totals per second do not. Measure frames visibly with `perf`.

Start a visible development client with `modaccel perf --root DIORAMA_SOURCE
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
