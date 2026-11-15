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

`modaccel perf-optimize --replay OUT1,OUT2` decides finished runs again under the current stopping rule and prints
the wall time they would take, sequentially and with paired arms. Diorama's four `perf-optimize` runs replay to the
same verdicts in 158.6 min instead of 175.5 sequentially, and about 86 min with paired arms.

## Steps

1. **Pin and build.** Clone into `.runs/<mod>` at a commit and note it. `modaccel compile --root .runs/<mod> --json`.
   Pick a mod whose cost is in **ticks or worker threads**: a hidden client presents about 8 frames per second, so
   per-frame work (overlays, HUDs, renderers) barely registers in it. Jade cost 0.3 ms/s hidden; Pipez's item pipes
   cost about 1 ms of every server tick.
2. **Make a world once and reuse it.** Start the client with `ui-check --root R --jit full`, create a fixed-seed
   superflat world with an eval snippet (`bench/pipez/create-world.java`), build the scene a player would have
   (`bench/pipez/scene.java`: a base whose pipes keep moving items), then save (`saveEverything`), `game-stop`, and copy
   the save to `.runs/<mod>-world/`. Every launch of every run copies that save; nothing is generated per launch.
   The workload must keep running for as long as the world does (a loop, not a one-off burst), so cold and warm
   captures see the same work.
3. **Profile.** `modaccel perf --root R --duration 30 --warmup 5 --allow-hidden --session <mod>-optimize`. Open
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
     plain filters, full destinations, an upgrade whose speed depends on the game time).
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
8. **Run perf-optimize.**
   ```sh
   modaccel perf-optimize --root R --experiment bench/<mod>/experiment.json --patch NAME.diff \
     --runs 8 --min-runs 3 --dry-run --session <mod>-optimize --label NAME --out .runs/<mod>-opt/NAME
   ```
   Pairs run until the rule decides: the first failed gate stops the run; from `--min-runs` pairs, 99% t intervals
   entirely above 1 (cold and warm) or below `--min-speedup` (either) stop it; otherwise it goes to `--runs`. The
   verdict is always the 95% bootstrap rule over the pairs measured. Read `optimize.html`: verdict, intervals, scopes,
   every gate value.
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

## Landing
Tooling changes go on their own branch with the offline gate from AGENTS.md (`self-test`, `bench/test_*.py`). Kept
runs, `bench/<mod>/` and new patterns land in the same PR as the evidence that produced them.
