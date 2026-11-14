# Performance evidence

`performance.py` analyzes externally collected measurements for one mod, a variant,
or an entire modpack. It does not launch Minecraft, collect samples, attribute costs
to mods, or establish that a particular mod is slow. Requires Python 3.12+, no packages.

```sh
python3 bench/performance.py report capture.json
python3 bench/performance.py compare baseline.json candidate.json --metric tick_ms --statistic p99 --threshold-percent 5
python3 bench/test_performance.py
```

Output is JSON. Comparison exits 1 for a regression, 2 for invalid/incomparable
input, 3 for inconclusive or zero-baseline evidence, and 0 for improvement or within
budget. Report exits 0 on valid evidence. Preserve the output alongside captures;
it includes SHA-256 fingerprints of the input files.

## Capture contract (schema 1)

The top-level object contains `schema: 1`, `subject`, `conditions`, and `runs`.

- `subject`: `name` and `artifact_sha256` identify the tested mod/pack. For packs,
  hash a canonical sorted manifest containing the SHA-256 of every installed jar.
- `conditions`: all fields below must be present and identical for comparison.
  Extra condition fields also participate in equality checks.
- `runs`: independent process launches. Each run has a globally unique string `id`,
  a string `pair` shared only with its corresponding run in the other capture,
  `correctness_passed: true`, an `evidence` path/URI to the workload assertion receipt,
  and `metrics`, mapping metric names to arrays of finite nonnegative numbers.

Conditions:

| Field | Contents |
|---|---|
| `hardware` | CPU, GPU, RAM, power mode and machine identity |
| `os`, `java`, `jvm_flags` | Exact versions and runtime arguments |
| `minecraft`, `loader` | Exact game and loader versions |
| `world_sha256` | Digest of immutable starting world snapshot |
| `background_mods_sha256` | Digest of all unchanged mod dependencies |
| `config_sha256` | Digest of unchanged configs, resource packs, shaders and settings |
| `scenario`, `workload` | Scenario version and parameters; include camera path, player count, render/simulation distances and useful work target |
| `side` | `server`, `client` or `integrated` |
| `cache` | `cold` or `warm`; document which caches were reset in workload |
| `warmup_seconds`, `measurement_seconds` | Nonnegative warmup and positive measured duration |
| `collector` | Collector name, version, sampling settings and instrumentation mode |

All SHA-256 values are 64 lowercase hexadecimal characters. Changed subject
artifacts/configuration belong in its manifest, not in the unchanged conditions.
When a substitution needs a different world layout, use a versioned scenario that
constructs each variant from the same snapshot and validates equivalent useful work.
Do not relabel differing conditions to force a comparison.

Available metrics and units are defined in `performance.py:METRICS`: tick/frame
latency, CPU per tick, allocation rate, heap/RSS, GC pauses, blocking, network/disk
rates, startup/reload, chunk generation/meshing, GPU frame time, transfer throughput,
and CPU cost per transfer. Unsupported/uncollected metrics appear as `unmeasured`.
Metric arrays contain measurements in the declared unit, not cumulative counters.
A GC pause array measures pause duration distribution, not GC duty cycle. I/O rates
are costs only when useful work is equivalent. A frame-time series does not measure
GPU time. Missing telemetry is never zero.

## Experiment rules

Use at least five paired independent JVM launches (more for noisy results),
alternating/randomizing baseline/candidate order in each pair. Restore the world,
wait for the stated warmup, perform exactly the same workload and assert correctness
before accepting the run. Keep raw collector output and record discarded/failed
runs separately; do not cherry-pick successful fast runs. The analyzer validates
receipt structure, not truth of assertions or existence of external artifacts.

Each run gets its own mean, p50, p95, p99 and maximum (linear-interpolated quantiles).
Comparison averages the selected per-run statistic; mean-of-run-p99 is not pooled
p99. A seeded paired bootstrap (10,000 resamples of whole run pairs) gives an
exploratory 95% interval for candidate minus baseline. Tick/frame samples within a
run are not treated as independent repetitions. Five pairs are a floor, not a
precision guarantee. Small tail sample counts and identical runs need investigation.

A regression requires the entire interval to exceed the practical percent budget
in the worse direction. Improvement requires the opposite; within-budget requires
the entire interval inside the budget. Other outcomes are inconclusive. A zero
baseline has no relative percent verdict. Intervals are not adjusted for multiple
metrics: preselect a primary metric for gating and investigate secondary signals.

No universal performance score is computed. These measurements apply only to the
recorded workload and environment; per-mod causal attribution requires controlled
removal/substitution experiments and dependency/interaction checks.
