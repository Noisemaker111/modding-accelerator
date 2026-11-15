---
name: optimize-a-mod
description: Take an unfamiliar Minecraft mod's source to a measured, gated, accepted speedup with modaccel perf and perf-optimize, and turn each accepted change into a reusable pattern. Use when Jon names a mod and says "make it faster", "optimize this mod", "next mod to optimize", "where does this mod's time go", or asks to push knowledge/perf-patterns.json further.
---

Done means: one accepted `perf-optimize` run (cold and warm 95% intervals above 1, every gate passing) kept in
`coverage/perf-runs.json`, and a `knowledge/perf-patterns.json` entry when the change generalises. You write the
change; modaccel only profiles, measures and gates. Reference: PERFORMANCE.md.

1. Clone the mod into `.runs/<mod>` at a pinned commit; `modaccel compile --root R`. Pick a mod whose cost is in
   server ticks or worker threads (a hidden client draws ~8 fps). Make the run a player's: drop optional
   `localRuntime` test mods, log level INFO, one local commit both arms share.
2. Make one world and reuse it: `ui-check --root R --jit full`, build the scene with `eval` snippets (wait with
   `eval --until "mc.player != null"`), save, `game-stop`, copy the save to `.runs/<mod>-world/`. The workload must
   run continuously. Scale it until the mod costs ~0.5 ms a tick; if it never does, pick another mod.
3. Profile: `world-check --world NAME --jit full`, then `perf --root R --duration 30 --warmup 5`. Read your mod's row
   in `report.html`/`report.json` and its **Known patterns**: a matching pattern is the first candidate.
4. `perf --probes CAPTURE/probes.suggested.json`, then `perf-template --family experiment --from CAPTURE --root R`.
   Use `serverInclusiveMsPerTick` (server thread) or `threadCpu` (the mod's own threads) as the metric.
5. Fill the gates. `exact` gates hash frozen inputs ticked by hand (reseed randomness, no clocks); `expect` gates
   assert the live workload still did its job. Every gate must change its cached input mid-run (add a block, break a
   cable, replace a barrel) so a stale cache fails; keep `chunks.java` for any cached lookup (chunk tickets are
   behaviour). Run the exact gate in two launches (same text), then prove it fails on a deliberately wrong
   `sabotage-*.diff`. `perf-optimize --check-only --experiment E --root R --patch P` checks it all offline.
6. Write one idea per candidate diff (`git -C R diff > .runs/<mod>-cands/NAME.diff`).
7. Measure locally: `perf-optimize --root R --experiment E --patch P --dry-run --out .runs/<mod>-opt/NAME` (it
   interleaves baseline and candidate runs), then `game-stop`. A wide A/B sweep goes to
   `tools/cloud-run perf -f repo=URL -f commit=SHA -f experiment=E -f patch=P`.
8. Keep every decided run: `python bench/record.py perf record.json`. Copy the accepted experiment, snippets and diff
   into `bench/<mod>/` with a README. Add the pattern (symptom, change, gain, gate, `provenIn`, `test.fires`/`quiet`).
