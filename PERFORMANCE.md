# Profile a mod or modpack locally

Requires a **JDK 17 or newer**, a game/server using Java 17+, and the same OS user
for the game and profiler. Start Minecraft normally through your preferred launcher.
No mod source, Python, uploaded data or profiler configuration is required for a
basic capture. Use `modaccel.cmd` instead of `./modaccel` on Windows.

```sh
./modaccel perf
```

Play normally during the capture (five seconds of warmup, then 30 seconds of
recording). Open the printed `report.html` in your browser. If multiple games are
running, select one:

```sh
./modaccel perf --list
./modaccel perf --attach 12345 --duration 60 --out ./my-capture
```

`--instance /path/to/instance` narrows automatic selection by the running JVM's
working directory. It does not launch or modify a launcher-managed instance.
If the launcher uses another working directory, use `--attach`.

For a source project supported by Mod Accel's existing launcher:

```sh
./modaccel perf --root /path/to/my-mod --duration 60
```

This starts a **visible** client if none is running. Enter your world and perform
the actions you want to measure. Increase `--warmup` (up to 600 seconds) when you
need more time. Existing hidden/throttled source sessions are refused: stop that
session with `game-stop`, then run `perf --root` again. Profiling does not cap FPS,
change vsync, mute your game, modify your saves or stop a game you attached to.
Source launches retain the existing control agent's focus behavior.

## What the report contains

- Frame intervals, average FPS, 1% lows, p95/p99 and frames over 50/100 ms.
- Completed server tick durations, ticks over 50 ms and observed tick rate.
- Heap samples, process CPU, allocation estimates and GC pauses.
- Sampled hot methods, threads and loaded-code origins; allocation and lock sites.
- Time-aligned graphs, explicit coverage gaps, raw JFR, JSON and a Speedscope
  sampled-stack export. Open the latter in a compatible local viewer for flame graphs.
- Installed top-level jar inventory and hashes where a `mods` directory is found.
  This is distinct from proof that every jar loaded.
- A 27-metric checklist with values, units and measurement basis. Missing metrics
  remain unavailable, including events that were not recorded; they are not zero.
- Evidence-linked investigation steps for frame stalls, slow ticks, GC pauses,
  monitor contention and the leading sampled method. Each includes a suggested
  change and a controlled retest with correctness checks. These rules do not
  identify an exact cause automatically or mark an untested fix as verified.

A server capture has no client FPS. A client connected to a dedicated server does
not measure that server's tick time: capture the server separately. Menus, pauses,
loading, FPS caps and background throttling can all change observed results.
The report does not infer whether you were playing or standing in a menu.

The timing hooks recognize common named, Fabric intermediary and Forge method
names. Official obfuscated **1.21.1** client/server mappings have explicit selectors.
If a game version or loader changes these methods, missing timing metrics are
reported; JVM profiling can still work. Java 8/11 games are not supported by this
agent. GPU time/VRAM, per-mod retained heap, mixin-origin attribution and arbitrary
mod behavior are not automatically measured. Code origin means where sampled code
was loaded from, not which mod caused its work. Optimization claims still require
controlled experiments and correctness checks.

1% low FPS is `1000 / mean(slowest ceil(1% of frame intervals))`. Tick durations
cover normally completed tick methods. Allocation weights are estimates; recorded
I/O and contention events may omit events below JFR thresholds. Optional probes
add overhead. No overhead correction or universal performance score is applied.

## Compare, reopen, and use startup capture

```sh
./modaccel perf-diff --baseline ./before --candidate ./after --out ./comparison
./modaccel perf-report --file ./my-capture --out ./rendered-report
./modaccel perf-report --file ./external-recording.jfr
```

`perf-diff` reports observed changes and differing environment fields. It does not
claim statistical significance or equal workloads. For paired independent JVM
experiments with practical regression budgets, the separate
[performance evidence analyzer](bench/performance.md) accepts its documented capture
contract. Observation reports are deliberately not silently promoted to benchmark evidence.

If a runtime disables dynamic agent loading, prepare a startup argument:

```sh
./modaccel perf --prepare --warmup 60 --duration 60 --out ./startup-capture
```

Put the printed `-javaagent:...=...` argument into your launcher's JVM arguments as
one argument (quote it if paths contain spaces). After the game finishes recording,
run `perf-report --file ./startup-capture`. Use a new output folder for each recording.
`--no-hooks` captures JVM events without adding game timing hooks, useful for
comparing instrumentation modes. Restart the game after updating the profiler;
an already-loaded agent from another build is rejected rather than silently reused.

For an explicitly configured Java launch, `--launch launch.json` accepts:

```json
{
  "command": ["/path/to/jdk/bin/java", "-Xmx2G", "-jar", "server.jar", "nogui"],
  "directory": "/path/to/disposable-server",
  "readyText": "Done ("
}
```

No shell is used. The profiler waits for `readyText`, then attaches to that JVM.
The process remains running after a successful capture; stop it normally when
finished. A failed startup is terminated. Launch configuration and server EULA
acceptance remain under your control.

## Stack sizes, transport lengths and block scenes

Generate a ready-to-edit transport scenario:

```sh
./modaccel perf-template --family transport --out ./scenarios
```

This produces **nine vanilla-hopper cases**, crossing lengths 1, 8 and 32 with
stack sizes 1, 4 and 16. All cases transfer the same 16 stone items, distributed
across different numbers of source slots. A powered source hopper holds the items
until recording begins. Assertions check the destination, source and every hopper.
The workload is a single burst followed by idle time, not steady-state capacity.
It establishes a reusable baseline; it does not claim to benchmark Pipez without
an adapter that configures Pipez correctly.

Run only in a **disposable local server world with RCON enabled**. These explicit
scenarios place/remove blocks, change chunk force-loading and, for scenes, change
player camera/game mode. They do not back up or restore a valuable world for you.

```sh
# POSIX; PowerShell uses $env:MODACCEL_RCON_PASSWORD = 'your-local-password'
export MODACCEL_RCON_PASSWORD='your-local-password'
./modaccel perf --attach SERVER_PID --scenario ./scenarios/transport.json --duration 20 --warmup 0 --out ./transport-results
```

Use `--rcon-port` for a nondefault port, `--only length-32-stack-1` to select a case,
and `--repeat` / `--seed` for reproducible shuffled order. RCON is restricted to
localhost; the password comes from an environment variable and is not copied into
reports. Open `index.html` for a parameter heatmap, per-case metrics and assertions.
All cases and failures remain in `suite.json`. Repeats share one JVM and are not
independent-launch evidence. Setup and command traffic can affect results.

```sh
./modaccel perf-template --family block-scene --block minecraft:stone --out ./scene-scenarios
```

The scene template places 1, 16, 64 and 256 instances and establishes a camera
position. Connect a client to the disposable server and attach to the **client PID**
for frame measurements; RCON controls the server. Substitute a valid mod block ID
to exercise its default placed-block rendering. Diorama is a minimap/world-map
renderer: placed-block scenes do not exercise its map baking, radar or waypoint
paths. Use the dedicated [Diorama CPU experiments](bench/diorama/README.md) for
those subsystems. Other mods' animations, inventories and custom state need
appropriate scenario commands or an adapter. Inspect the rendered scene to
confirm the intended content.

## Custom mechanics and deeper probes

Scenario files have `schema: 1`, `name` and `cases`. Each case has `id`, descriptive
`parameters`, and ordered `setup`, `before`, `during`, `after`, `cleanup` steps.
`after` must contain assertions. Each step has a Minecraft `command` and a literal
`expect` substring or `expectAny` list. `expectEmpty: true` also accepts an exactly
empty reply for idempotent commands; follow it with an explicit game-state assertion. Optional `waitMs` is bounded to 0..5000;
`during` steps also have `atSeconds`, relative to observing recording startup.
Commands and actual response timestamps are retained. All cleanup steps are
attempted even when another cleanup step fails. No regular expression or shell
execution is needed for assertions.

Use these files for inventory fullness, filter settings, recipes, topology changes,
entity counts, camera changes and other mechanics your mod exposes through commands.
A passing case means its declared assertions passed, not that unasserted behavior
was proven. The tool cannot infer correct semantics for an arbitrary mod.

Optional method-entry counters can test a hypothesis found in the sampled stacks:

```json
{
  "probes": [
    {"class":"com.example.Transport", "method":"rebuildRoutes",
     "descriptor":"()V", "label":"routeRebuilds"}
  ]
}
```

```sh
./modaccel perf --attach PID --probes ./probes.json --duration 30
```

Use exact runtime class/method names and JVM descriptors. Up to 32 selectors are
allowed. Matched but uncalled methods have zero counts; unmatched selectors are
listed as unavailable. Counters measure entries, including simulations/failures,
not successful transfers or CPU duration. Probes can be expensive in hot loops.

Add `"mode":"timing"` to a selector to measure each completed invocation instead
of only counting entries. The default remains `"count"`. Timing probes provide:

- Inclusive wall time, self wall time, call count, mean/p50/p95/p99/max and exceptional exits.
- Nearest timed caller relationships, invocation/parent IDs and per-thread start times.
- Automatic frame/tick method scopes and the slowest 200 retained invocations.
- An expandable call-tree preview for the 20 slowest retained roots, bounded to
  2,000 displayed nodes; the downloadable timeline contains all retained calls.
- `functions.trace.json`, a Chrome Trace-compatible timeline for nested-call inspection
  in a viewer such as Perfetto, with recorded sleep, park, monitor, I/O and GC context.
  Context events are thresholded and capped at 100,000; omissions are reported.
  Raw timing events also remain in the JFR.

```json
{"probes":[
  {"class":"com.example.Transport","method":"tick","descriptor":"()V","label":"transportTick","mode":"timing"},
  {"class":"com.example.Transport","method":"rebuildRoutes","descriptor":"()V","label":"routeRebuild","mode":"timing"}
]}
```

Begin with a few coarse methods found by sampling, then add their expensive callees.
Self time subtracts immediate **timed** children on the same thread; it still includes
untimed callees, waiting and instrumentation overhead. The caller is the nearest timed
ancestor, not necessarily the direct Java caller. Async work runs on its own thread.
Do not sum nested inclusive time or treat summed thread-ms as elapsed capture time.
Frame method duration differs from frame interval, displayed presentation and GPU time.
Nanosecond clock storage does not guarantee nanosecond measurement accuracy.

Only invocations entered and completed during measurement are retained. Propagated
exceptions close scopes; internally caught exceptions do not mark the method failed.
At most 100,000 completed timing events and nesting depth 128 are recorded per capture.
The report flags dropped/evicted history and uses retained calls for totals/percentiles.
Entry counters can exceed timed completions. A missing duration is unavailable, not zero.
Use shorter captures or fewer probes if limits are reached. Compare against a capture
without timing probes: deeply instrumenting a hot loop can substantially distort it.
Exact runtime names/descriptors are required; constructors, native and abstract methods
are not supported. GPU execution, native internals and cross-thread causal ownership
require additional tools or domain-specific probes.

## Files and troubleshooting

Reports stay local. Raw JFR/class-origin files can contain machine paths, launch
arguments and addresses: inspect before sharing. Recordings are bounded to one hour
and 512 MiB of JFR history; large captures can evict earlier events. The report limits
stored frame/tick samples and stack exports and reports truncation. Prefer multiple
shorter captures when investigating long sessions.

- **No matching game:** use `perf --list`, then `--attach PID`.
- **Attach denied:** use the same OS account and a full JDK; try `--prepare`.
- **No FPS/ticks:** check report coverage and hook errors; the game may be paused,
  on a different side, or use an unsupported mapping.
- **Scenario failure:** inspect exact commands/responses in `suite.json`. Command
  syntax and messages vary across game versions. Invalid results remain failures.
- **Read-only instance:** capture output can be redirected with `--out`; the basic
  profiler does not write into your game directory.
