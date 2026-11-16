# spark in All the Mods 9: process load from CPU time

spark (GPLv3, [lucko/spark](https://github.com/lucko/spark)) reads the process's CPU load once a second on its
`spark-monitoring-thread` with `OperatingSystemMXBean.getProcessCpuLoad()`. On Windows that call queries a
performance counter of every process on the machine: 212 ms of CPU a call on this machine with a few hundred
processes running. In All the Mods 9 1.1.1 the thread used 141-219 ms of CPU a second, standing still or flying, on
every Windows player's game. `process-load-from-cpu-time.diff` computes the same quantity (CPU time the process used
since the last reading over the time that passed, on every processor) from `getProcessCpuTime()`, a cheap system call,
behind the switch `CpuMonitor.FROM_CPU_TIME` (on). The JDK's own call stays the fallback when CPU time is unavailable.

| Run | Verdict | Cold | Warm |
|---|---|---|---|
| `2026-11-16T09-51-22.409782400Z-eb67dc2f` | accepted | 97.9x (95% 63.6-123.1) | 117.1x (95% 107.0-126.0) |
| `2026-11-16T09-31-06.845525200Z-697c5a4b` | rejected: its equivalence gate read the load twice back to back, which the baseline's counter cannot do | - | - |

Metric: the operating system's CPU time of `spark-monitoring-thread`, 95 → 1.0 ms a second cold and 92 → 0.8 warm
(the candidate is floored at one 15.6 ms clock tick over the window, so the ratios are conservative). 3 pairs, both
arms at once in their own All the Mods 9 client, on a shared machine: approximate.

Same game, before the paired run (`mmt pack-run --mod`, switch flipped live, four interleaved rounds): 153, 191, 184
and 219 ms a second with the JDK call against 0.0 with CPU time; over 20 shared one-second windows the two readings
averaged 7.65% and 7.55% of the machine.

Gates (`expect`, every launch of both arms): `monitor.java` (spark's monitor runs and reports a fraction of the
machine) and `agrees.java` (spark's own 10-second average is within 5 points of the process's CPU time over the same
10 s, measured without asking any load counter).

## Run it

The experiment measures spark inside the pack (`"pack"` in `experiment.json`): each arm builds spark's jar and
`pack-run --mod` starts it in place of the pack's spark.

1. The pack, once per machine: download All the Mods 9 1.1.1 (CurseForge project 715572, file 7097953), run
   `mmt pack-check --pack atm9-1.1.1.zip --fix` (LionfishAPI's file is gone; the repair swaps in 2.7), and copy the
   fixed export to `.runs/atm9/atm9-1.1.1-fixed.zip`. JourneyMap is left out (`"without": "journeymap"`).
2. spark at the version the pack ships: `git clone https://github.com/lucko/spark .runs/spark`,
   `git -C .runs/spark checkout 8c253ae` (v1.10-53), then commit `setup.diff` (only the Forge platform builds; the
   2023 bytesocks snapshot is no longer published, so the one that is; a dev client run). Both arms share it.
3. `mmt perf-optimize --root .runs/spark --experiment bench/spark/experiment.json --patch bench/spark/process-load-from-cpu-time.diff --dry-run`
