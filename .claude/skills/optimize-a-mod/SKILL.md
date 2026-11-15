---
name: optimize-a-mod
description: Take an unfamiliar Minecraft mod's source to a measured, gated, accepted speedup with modaccel perf and perf-optimize, and turn each accepted change into a reusable pattern. Use when Jon names a mod and says "make it faster", "optimize this mod", "next mod to optimize", "where does this mod's time go", or asks to push knowledge/perf-patterns.json further.
---

# Optimize a mod

The goal is a speedup players get, proved to change nothing they can see: one accepted `perf-optimize` run whose
cold and warm 95% intervals both lie above 1 and whose gates all pass, kept in `coverage/perf-runs.json`, and, when
the change generalises, a pattern in `knowledge/perf-patterns.json` so the next mod's report names it.

You write every optimization. modaccel only profiles, measures and gates; it makes no AI or network calls.

## Rules
- Launches stay hidden and muted (`~/.modaccel/preferences.json` is `{"background": true, "quiet": true}`; never change
  it). Jon may be playing, and other agents may be measuring: run at most 2 heavy jobs (a build, a game, a
  `perf-optimize`) at a time, and keep the load each run records (`machine.load` in record.json).
- Use modaccel for builds and games, never raw Gradle. Clones, worlds and outputs go in `.runs/`.
- Never push to the mod's upstream or open issues there without asking Jon. The accepted diff stays in our clone and
  in `bench/<mod>/`.
- One idea per candidate diff. A bundle that is rejected tells you nothing about its parts.

## Where the time goes (read this before planning)
`python bench/perf_sessions.py --session NAME` sums a session's run records by phase. Diorama's first session
(`diorama-10x`) took 223 min for 1 accepted change: 56% measuring, 18% launching, 9% gates. It lost the most time to:
- **runs that went on after they were decided:** an exact gate had already failed (its baselines disagreed) after
  2 pairs and 2 more pairs ran (17 min). `perf-optimize` now stops at the first failed gate;
- **arms run one after the other:** each pair took both arms' time. With 12 or more cores, `perf-optimize` now starts
  both arms of a pair together (`--arms auto`), and both see the same machine load;
- **candidates that only looked faster under C1:** 45 min of hand-run comparisons rejected two of them. Measure with
  `"jit": "full"` from the start (the experiment template does);
- **typos found after a launch:** `perf-optimize` checks the experiment offline before anything starts.

The second session, Pipez (`bench/pipez`, session `pipez-optimize`), took 35 min of run time for 1 accepted change
(cold 1.30x, warm 1.20x): 8 pairs in 21.5 min with paired arms, a broken candidate rejected in 5.6 min, and about
60 min of wall clock from clone to accepted change, a discarded first mod (Jade) included.

The third, Simple Storage Network (`bench/storagenetwork`, session `storagenetwork-optimize`), stacked five accepted
changes into cold 5.12x and warm 5.95x against the unmodified mod (one cumulative run, 3 pairs). It took 14 runs and
173 min of run time; launching games was a third of it. The first accepted change came 36 min after the clone.
It lost the most time to a candidate run that died on a tool bug (fixed: children write UTF-8), a gate edited
during a run (fixed: runs read a snapshot of the experiment), a run aborted by hand that left no record (fixed:
`OUT/stop`), and small candidates that ran all 8 pairs to be rejected near 1.0x: time each part of the remaining cost
directly first, and skip a candidate worth a few percent.

The fourth, Cyclic's auto-crafters (`bench/cyclic`, session `cyclic-optimize`), stacked three accepted changes into
cold 15.70x and warm 15.90x against the unmodified mod (one cumulative run, 3 pairs). The first, keeping each
crafter's recipe until its grid or the server's recipes change, was accepted 28 min after the clone at 10.5x by
itself. Five runs took 70 min of run time and 84 min of wall clock from clone to the cumulative verdict: two
candidates ran at the same time, and a candidate the exact gate rejected was fixed and checked against the baselines'
recorded gate value in one launch before its rerun. Launching games was 42% of the run time.

`modaccel perf-optimize --replay OUT1,OUT2` decides finished runs again under the current stopping rule and prints
the wall time they would take, sequentially and with paired arms. Diorama's four `perf-optimize` runs replay to the
same verdicts in 158.6 min instead of 175.5 sequentially, and about 86 min with paired arms.

## Steps

1. **Pin and build.** Clone into `.runs/<mod>` at a commit and note it. `modaccel compile --root .runs/<mod> --json`.
   Pick a mod whose cost is in **ticks or worker threads**: a hidden client presents about 8 frames per second, so
   per-frame work (overlays, HUDs, renderers) barely registers in it. Jade cost 0.3 ms/s hidden; Pipez's item pipes
   cost about 1 ms of every server tick; Simple Storage Network's master cost 3.2 ms of every tick in a 256-barrel base.
   Make the launch a player's, in one local commit both arms share, and say so in the README: comment out the
   build's optional `localRuntime` test mods (Storage Network loaded AE2, Refined Storage, JEI, Curios, Patchouli), and
   set the run's `logLevel` to INFO. A mod's dev run often logs DEBUG to the console, where one line cost 15 µs; a
   player's game writes DEBUG only to the buffered `debug.log` (0.7 µs), so the dev console inflates fixed costs.
   A fresh clone has no git identity: set a local `user.name` and `user.email` in it before that commit.
2. **Make a world once and reuse it.** Start the client with `ui-check --root R --jit full`, create a fixed-seed
   superflat world with an eval snippet (`bench/pipez/create-world.java`), build the scene a player would have
   (`bench/pipez/scene.java`: a base whose pipes keep moving items), then save (`saveEverything`), `game-stop`, and copy
   the save to `.runs/<mod>-world/`. Every launch of every run copies that save; nothing is generated per launch.
   The workload must keep running for as long as the world does (a loop, not a one-off burst), so cold and warm
   captures see the same work.
3. **Profile.** Load the save first (`modaccel world-check --root R --world NAME --jit full` leaves the game in it),
   then `modaccel perf --root R --duration 30 --warmup 5 --allow-hidden --session <mod>-optimize`. Open
   `report.html`: **Where the cost is, by mod and feature**. Read your mod's row (★): ms per tick and per frame with
   95% ranges, its features (block entity, mixin, event handler) and their top methods, self and inclusive.
   Too few samples (under ~50 for the mod) means the scene is too small: scale it until the mod's cost is real.
4. **Match known patterns.** The report's **Known patterns** section lists `knowledge/perf-patterns.json` entries whose
   symptom matches the capture. Read each `change` and `gate`; a match is the first candidate to write.
5. **Write the experiment.** `modaccel perf-template --family experiment --from CAPTURE --root R --out bench/<mod>`
   writes `experiment.json` (metric, scopes, the full JIT), `probes.json` from the capture's suggestions, `options.txt`,
   and two gate snippets to fill in. The metric is a timing probe's `inclusiveMsPerTick` when the server thread
   carries the cost (exact, and not diluted by a few samples), else the mod's sampled CPU on its threads. Drop probes on
   tiny methods called thousands of times a tick: probe overhead then distorts what you measure. Point `world` and
   `worldSource` at the save from step 2.
6. **Make the gates exact.** Gates prove the candidate changes nothing a player sees:
   - an `exact` gate runs the changed code on **frozen inputs** and returns a hash of everything it produced: build a
     copy of the scene far from the player (a chunk that never ticks), tick the block entities by hand in a fixed
     order with a fixed game time, and hash every inventory and index after every round (`bench/pipez/trace.java`).
     Two baseline launches must agree, so no clocks, randomness, identity hashes or live-world state in the result;
   - an `expect` gate asserts the live workload still did its job after the captures (`bench/pipez/count.java`: every
     item conserved and both sides of every row moving), so a candidate cannot win by doing less;
   - cover every branch the candidate touches (Pipez: NEAREST, FURTHEST and ROUND_ROBIN distribution, inverted and
     plain filters, full destinations, an upgrade whose speed depends on the game time). A candidate with a fast path
     and a fallback needs both in the frozen input: Storage Network's trace runs two networks, one with a furnace (the
     fallback) and one with only barrels and chests (the fast path), and changes the world mid-trace (a cable broken
     and put back, a barrel replaced, a redstone link powered, a priority changed) so cached state must notice;
   - a workload in steady state can look still: Storage Network's exports and imports move the same items into the
     same slots in the same tick, so two snapshots agree. Perturb it reversibly and check it recovers
     (`bench/storagenetwork/count.java` rotates the buffers' contents and requires each to hold its own item 2 s later).
   Run the exact gate in two separate launches and compare: it must return the same text. Then **break it once on
   purpose**: a candidate that changes behaviour slightly (Pipez: nearest-first sorted the other way) must fail the
   exact gate. `perf-optimize` stops at the second pair (an `expect` gate at the first), so this costs two pairs.
   `modaccel perf-optimize --check-only --experiment E --root R [--patch P]` checks the experiment offline: every
   file, the world, probe labels, unwritten template gates, clock reads in exact gates, a frame metric on a hidden
   client, the JIT, and whether the patch applies. It runs again at the start of every `perf-optimize`.
7. **Write a candidate.** From the hottest self and inclusive methods, write the smallest change that keeps every
   output identical: cache what is recomputed from unchanged inputs (key it on the identity of an input the code
   replaces rather than mutates), skip work whose result is discarded, replace streams in per-item loops. Save it:
   `git -C R diff > .runs/<mod>-cands/NAME.diff && git -C R checkout -- .`.
   To stack candidates, write them as commits in a second worktree of the clone
   (`git -C R worktree add ../<mod>-stack HEAD`), one commit per idea, and diff each against its parent; fast-forward
   `R` to a candidate's commit when it is accepted, so the next one runs against it, and rebase the rest when one is
   dropped. Once a sampled capture shows few samples for the mod (it is fast now), time each part of the remaining
   cost directly before writing the next candidate: an eval snippet that calls each method a few thousand times on the
   server thread (`ms per call`) shows what is left better than 20 ms samples do.
8. **Run perf-optimize.**
   ```sh
   modaccel perf-optimize --root R --experiment bench/<mod>/experiment.json --patch NAME.diff \
     --runs 8 --min-runs 3 --dry-run --session <mod>-optimize --label NAME --out .runs/<mod>-opt/NAME
   ```
   Pairs run until the rule decides: the first failed gate stops the run; from `--min-runs` pairs, 99% t intervals
   entirely above 1 (cold and warm) or below `--min-speedup` (either) stop it; otherwise it goes to `--runs`. The
   verdict is always the 95% bootstrap rule over the pairs measured. Read `optimize.html`: verdict, intervals, scopes,
   every gate value. Each pair prints a line (its speedups, the running means with 99% intervals, every gate); a
   candidate near 1.0x runs all `--runs` pairs, so when its lines show it will not decide (or a later candidate makes
   it moot), create `OUT/stop`: the run ends after the pair in progress and is decided and recorded like any other.
   A run reads a copy of the experiment's files taken when it started, so editing gates while it runs is safe.
9. **Record.** Keep every decided run, accepted or not: `python bench/record.py perf .runs/<mod>-opt/NAME/record.json`.
   Put the experiment, snippets, probes and accepted diff in `bench/<mod>/` with a README saying how to rerun it.
   `python bench/perf_sessions.py --session <mod>-optimize` gives the session's time by phase; compare it with
   `diorama-10x`.
10. **Add the pattern.** For an accepted change that would apply to other mods, add an entry to
    `knowledge/perf-patterns.json`: a symptom (`when` clauses over methods, features or threads with a regex and a
    minimum share), the change in words, the gain with its intervals, the gate that proved it, `provenIn` with the
    kept run's id, and `test.fires` / `test.quiet` attribution rows. `modaccel self-test --only perf` checks it.

## Lessons the records show
- **C1 versus the full JIT.** Hidden test clients compile with C1 alone unless the experiment says `"jit": "full"`.
  Diorama's air-over-ground candidate measured faster under C1 and was 0.77x with the full JIT; integer rows were
  0.95x. Never accept or reject from a C1 capture.
- **Noise on a loaded machine.** With other agents at 100% CPU, Diorama's per-pair ratios ranged 0.8 to 2.5 and a real
  1.3x needed 6 pairs. Prefer exact probe metrics on the server thread or sampled CPU (both robust to stretched wall
  time) over wall-clock spans; run paired arms together so a load burst hits both; read `machine.load` before
  trusting a rejection.
- **Hidden clients present about 8 frames per second.** Frame time, FPS and per-frame costs in a hidden client
  measure the throttle. `perf-optimize --check-only` refuses a frame metric when launches are hidden.
- **Exact gates need frozen inputs.** Live state diverges between launches (AI, random ticks, timing of the capture).
  An exact gate on live state fails baseline-to-baseline; assert live behaviour with `expect` and hash frozen runs.
- **Identity hashes reorder ties between launches.** An enum's `hashCode()` is an identity hash, so a `HashMap` keyed
  on a record holding a `Direction` iterates in a different order in every JVM. Pipez sorts its connections by
  distance from such a map, so equal-distance destinations swap between launches, and the first Pipez trace gate
  differed launch to launch. Run a new exact gate in two separate launches before trusting it, and build its frozen
  input without ties (one extractor at the end of each line). `perf-optimize` only stops on an exact gate from the
  second pair, when two baseline launches show whether the gate is reproducible.
- **Probe overhead.** A few hundred nanoseconds per timed call: time the caller of a method called thousands of times
  per tick.
- **Timing probes are wall time.** On a machine at 90% CPU a probe includes the time its thread was descheduled; one
  Storage Network capture put an insert at 24 µs a call when a direct timing gave 2 µs. Paired arms see the same load,
  so ratios hold; absolute values do not.
- **A mechanism is not a candidate.** Storage Network's capability caches alone measured 1.0x (a direct lookup was
  already cheap after the first change); they were what let the next change keep a plan until NeoForge says something
  changed, which measured 1.46x. Fold a mechanism into the change that needs it. Likewise a later candidate can make an
  earlier one moot (planning the storage list made sorting its positions once per refresh redundant): stop that run.
- **NeoForge capability-cache listeners.** `BlockCapabilityCache.create(..., isValid, listener)` calls the listener
  only if the cache was queried since its last invalidation. Query every cache a plan depends on when making the plan,
  or the plan never hears of a change (Storage Network's walk replay kept a broken cable until its trace gate failed).
- **A write that looks like a no-op can be an alias.** NeoForge's `ItemStackHandler.insertItem` keeps the very stack it
  is given in an empty slot. Cyclic's crafter put its result stack in its preview slot and then in an empty output slot
  on a craft tick, so the two slots held one object until the next tick wrote the preview again. A candidate that kept
  the preview while it was equal let the next craft's `grow` change the preview too: the exact gate failed in both
  pairs. Before skipping a write, ask whether the object it would replace is held anywhere else.
- **Rewrites of quirky loops.** Replacing a loop over copied lists, keep what the copies did by accident: `copy()` of
  any empty stack is the one `ItemStack.EMPTY`, so Cyclic's `removeAll` (identity) took every empty grid slot as soon
  as it took one. Put the quirk in the exact gate's frozen input (`bench/cyclic/trace.java`, crafters 10 and 11).
- **The probe can outweigh the method.** A timing probe records every call: about a microsecond each on a loaded
  machine. Once Cyclic's crafter tick was down to about a microsecond, its probe (128 calls a tick) was half of what
  the metric measured, so later candidates measured smaller than they were. Time the remaining parts directly
  (`bench/cyclic/parts.java`) to decide what to write next, and say in the README that the metric includes the probe.
- **Check a fixed candidate against a recorded baseline gate value.** Every run keeps the baselines' gate values. After
  an exact gate fails, run the fixed candidate's gate in one launch on a fresh copy of the save and compare it with
  that value (3 min) before spending pairs on it.
- **Independent candidates can run together.** Two `perf-optimize` runs are two heavy jobs; Cyclic's second and third
  candidates ran at the same time against the same accepted build, each with its own worktrees (first launches took
  160 s instead of 145 s).
- **Patterns travel.** Pipez's `cache-sorted-copy-until-source-replaced` fired on Storage Network's first capture
  (its storage list was sorted on every use); that was the candidate to write.

## Landing
Tooling changes go on their own branch with the offline gate from AGENTS.md (`self-test`, `bench/test_*.py`). Kept
runs, `bench/<mod>/` and new patterns land in the same PR as the evidence that produced them.
