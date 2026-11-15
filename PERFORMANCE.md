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

This starts a client if none is running, the way `modaccel preferences` says:
hidden with background on (frame timing is then not representative, and the report
says so; server ticks and timed methods are unaffected), visible otherwise. It runs
the full JIT even when hidden, unlike other hidden test sessions, which compile with
C1 alone: C1 code is slower than players' and gives JFR far fewer samples. A session
that is already running is profiled as it is; the report warns when it is C1-only.
Enter your world and perform the actions you want to measure. Increase `--warmup`
(up to 600 seconds) when you need more time. Profiling does not cap FPS, change
vsync, mute your game, modify your saves or stop a game you attached to.

## Where your mod's cost goes

The report opens with **Where the cost is, by mod and feature**: one row per mod,
ranked, with its share of CPU samples, server milliseconds per tick and render
milliseconds per frame (each with a 95% range), allocation rate, live heap of its
own classes, lock wait and I/O. With `--root` your own mod is marked ★ and its
packages come from the checkout; for an installed pack, mods are named from each
jar's `fabric.mod.json` or `mods.toml`. Use `--mod-package com.example.mymod` to
name your code explicitly, or `--mod-root my-mod` with `--attach PID`.

`perf --attach` refuses a hidden, throttled game because its frame timing is not
representative. When only server ticks or timed methods matter, `--allow-hidden`
captures it anyway and the report says frame values are not representative.

Each sampled stack, allocation, lock wait and I/O event is charged to the
**innermost mod frame** on its stack. Game, JVM, loader and library code above that
frame is work the mod asked for, so it counts as the mod's. Work inside Mod Accel's
own frame/tick hooks and timing probes is profiler overhead, reported apart and never
charged to a mod. JFR weighs an allocation sample by what its thread allocated since
its previous sample, so each thread's first sample, which also covers time before the
capture, only starts that thread's count. Expand a mod to see its
**features**, named by how the game entered the mod:

- `event handler · EntityTickEvent.Pre → EventHandler.onEntityTick` when an event bus
  (NeoForge, Forge, Fabric API) called it;
- `mixin · LevelRenderer ← onRender` for a Fabric mixin handler merged into a game class;
- `block`, `block entity`, `entity`, `entity renderer`, `block entity renderer`,
  `AI goal`, `item`, `screen`, `menu` or `saved data` from the class's own type chain;
- otherwise `other code · Class.method`.

Each feature lists its top methods and threads. A threads table gives each
thread's operating-system CPU load. Per-tick and per-frame values are the mod's share
of server-thread or render-thread samples times the measured mean tick duration or
frame interval. They are **sampled estimates** (marked ≈). Waits inside a tick, GPU
work and vsync are not sampled.

For **exact** numbers, the capture also writes `probes.suggested.json`: timing
selectors for each mod's most-sampled methods, with their JVM descriptors. Capture
again with them:

```sh
./modaccel perf --root my-mod --probes ./capture/probes.suggested.json --duration 30
```

The mod's rows then show every timed method's exact call count, calls per tick,
milliseconds per tick and mean per call. These totals are kept by the agent for the
whole capture, so they stay complete even when the per-call history is capped.
Every timed call costs a few hundred nanoseconds of probe overhead. For a method
called thousands of times per tick, time its caller instead, or compare captures
with the same probes.

## What else the report contains

- Frame intervals, average FPS, 1% lows, p95/p99 and frames over 50/100 ms.
- Completed server tick durations, ticks over 50 ms and observed tick rate.
- Heap samples, process CPU, allocation estimates and GC pauses.
- Sampled hot methods, threads and loaded-code origins; allocation and lock sites.
- Live instance count and shallow bytes per class from a class histogram taken
  after recording stops. This runs a full GC once; `--no-heap` skips it.
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
agent. GPU time and VRAM, retained (reachable) heap per mod, mixin overwrites,
unnamed mixin handlers and arbitrary mod behavior are not measured. Heap per mod
counts instances of the mod's own classes only, not game objects it holds.
Charging work to the innermost mod is not proof of cause: a mod that triggers
another mod's work through a shared event is not charged for it.

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

### Your mod's own content

```sh
./modaccel perf-template --family mod-content --root my-mod --out ./my-scenarios
./modaccel perf-template --family entity-crowd --entity mymod:ghoul --counts 1,16,64,256 --out ./crowd
```

`mod-content` reads the `block.<mod>.<id>` and `entity.<mod>.<id>` keys of the mod's
`en_us.json` lang files and writes a 64-block scene per block and 16- and 64-entity
crowds per entity (`--counts` changes the sizes). `entity-crowd` does the same for one
entity ID, which can be a vanilla mob your mod changes. Crowds stand in a barrier pen,
are tagged `modaccel_perf`, and are removed in cleanup.

Run a scenario inside your source client's own singleplayer world, with no server
or RCON: enter a disposable world, then

```sh
./modaccel perf --root my-mod --scenario ./my-scenarios/mod-content.json --duration 20
```

With `--root` and no `--rcon-port`, each command runs on the integrated server through
the game console and its feedback text is checked like an RCON reply. This needs the
game's Mojang or Yarn names in the development client.

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

## Accept a change only when it is equivalent and faster

`perf-optimize` decides one candidate change. You, or your own agent, write the
change from what the report shows and save it as a git diff; modaccel never writes
or proposes code, and makes no network or model calls.

```sh
git -C my-mod diff > candidate.diff && git -C my-mod checkout -- .
./modaccel perf-optimize --root my-mod --experiment experiment.json --patch candidate.diff --runs 5
```

It makes two git worktrees of the mod's `HEAD`: **baseline** and **candidate**
(`HEAD` plus the diff). For each of `--runs` pairs it launches each arm in a fresh
game, alternating which goes first, and in every launch:

1. copies the experiment's `files` (configs, `options.txt`) and a fresh copy of
   `worldSource` into the arm, then starts it (`world-check --world`, or `ui-check`
   when there is no world) with your saved window and sound preferences;
2. runs the `setup` eval snippets in order (`expect` checks a returned value);
3. records a **cold** capture right after setup and a **warm** capture after
   `measure.warmup` more seconds, attaching by PID (hidden sessions allowed), and
   reads the metric from each;
4. runs every gate and stops the game.

The metric is a timing probe's `inclusiveMsPerTick` (or another statistic of its
exact row, such as `meanInclusiveMs`) or any numeric `report.json` path such as
`metrics.tick_ms.mean`. Choose a server-side or timed metric when launches are
hidden: hidden clients are throttled, so FPS and frame intervals are not
representative. Speedup is baseline ÷ candidate per pair; the report gives
the geometric mean and a 95% bootstrap interval over pairs, separately for cold and
warm.

Gates compare each candidate launch with its paired baseline launch:

| `compare` | Passes when |
|---|---|
| `exact` | the snippet's returned text is identical, and identical across all baseline launches |
| `expect` | every launch of both arms returns `expect` (behaviour assertions) |
| `tolerance` | every number differs by at most the relative `tolerance`, and baseline launches agree within it |
| `pixels` (`frame: true`) | the 1280x720 framebuffer captures differ in at most `maxDifferentPixels` pixels; baseline launches must agree too |

A frame gate's optional `file` runs first to set up a fixed view; inspect the saved
`frame-*.png` files to confirm the view shows what you meant to compare. A gate
whose baseline launches disagree fails: it cannot tell a change from noise, so
freeze or isolate what it reads. `"tests": true` also runs `fast` in both
worktrees and requires the candidate to pass if the baseline does.

The change is **accepted** only when every gate passes and both cold and warm
intervals lie above 1. An accepted diff is applied to `--root` (not with
`--dry-run`). `--keep` keeps both worktrees. `optimize.html` and `optimize.json`
hold every launch, capture, gate value and frame.

```json
{
  "schema": 1,
  "gameDirectory": "runs/client",
  "world": "bench", "worldSource": "saves/bench",
  "files": {"runs/client/options.txt": "options.txt"},
  "setup": [{"file": "setup.java", "thread": "control"}],
  "measure": {"seconds": 20, "warmup": 10, "probes": "probes.json",
              "metric": {"probe": "onEntityTick", "statistic": "inclusiveMsPerTick"}},
  "gates": [
    {"name": "behaviour", "file": "assert.java", "compare": "expect", "expect": "true"},
    {"name": "decisions", "file": "digest.java", "thread": "control", "compare": "exact"},
    {"name": "frame", "frame": true, "file": "view.java", "thread": "control", "maxDifferentPixels": 0}
  ]
}
```

Paths are relative to the experiment file. Measurements apply to that scenario,
world and machine; other load on the machine widens the intervals, and a change too
small to separate from that noise is rejected. If a run is interrupted, stop its
games with `game-stop --root OUT/baseline` (and `candidate`) and run
`git worktree prune` in the mod. Each arm builds in its own worktree, so the first run of an
experiment includes two cold builds. [`bench/zombie-siege`](bench/zombie-siege/README.md)
is a complete experiment for a real NeoForge mod.

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
