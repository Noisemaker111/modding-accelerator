# Diorama CPU performance experiments

These snippets measure the real Diorama implementations inside its development
client through `modaccel eval`. They target Diorama 0.10.1, commit
`7aa1484cd5dfe4ec19d246a58fb23bbc48a8bc6e`, on Fabric / Minecraft 1.21.1.
They require that source checkout and its JDK 25 build environment.

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

The reference and candidate share supporting classes, including mip generation;
this comparison is suitable for the supplied TileBaker/VoxelReader patch, not for
arbitrary changes to shared helpers. Same-JVM timings do not establish hardware FPS
gains, universal speedups, or independent-process confidence. Repeat fresh launches
and test representative terrain and modded content before distributing a build.

Remove the reference before packaging the modified mod:

```sh
python3 bench/diorama/prepare_comparison.py --root DIORAMA_SOURCE --remove-reference
./modaccel compile --root DIORAMA_SOURCE --project :1.21.1 --task :1.21.1:remapJar
```

The cleanup refuses to delete an edited reference and leaves the candidate changes
in place. Keep any distributed build clearly labeled as modified and experimental.
