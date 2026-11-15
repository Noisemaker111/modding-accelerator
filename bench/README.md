# Benchmarks and porting reference

Plans, dated status and findings belong in PRs/issues. Documentation describes current use.

- `coverage/roadmap.json`: the existing goals, step and measure schema consumed by the bench tools and generated dashboard. Keep new plans and status in PRs/issues.
- `coverage/measures.json`: every number we show. A measure is one thing measured the same way (warm and cold are separate measures), with dated points that each name a source, and a target with a date and the step that reaches it.
- `coverage/ledger.json`: every port that was run, with its timing. A run not in the ledger did not happen. Measures such as mods working and updates working per loader are computed from it.

Recording, so no one edits those files by hand:

- `python bench/record.py port RECEIPT --mod NAME [--status S --detail TEXT] [--measure ID]` turns a port run into a ledger row, read from the receipt `modaccel port` printed.
- `python bench/record.py perf RECORD [RECORD ...]` keeps optimization-session runs (the record.json that `modaccel perf`, `perf-optimize` and comparison scripts write) in `coverage/perf-runs.json`, checked first. `knowledge/perf-patterns.json` cites accepted runs there as proof.
- `python bench/perf_sessions.py [--session NAME] [--local] [PATH ...]` sums those runs: wall time per phase, accepted and rejected candidates with the reasons, and the slowest steps. `python bench/test_perf_sessions.py` checks it.
- `python bench/measures.py add ID VALUE --source WHERE [--n N] [--note TEXT]` records a point.
- `python bench/measures.py table [IDS] [--area AREA] [--step N]` prints the standard Markdown table for a PR body or report.
- `python bench/measures.py check` validates the roadmap and the measures.

The porting scoreboard:

- `python bench/port_board.py` ports every pinned mod in `bench/port-cases.json` from a clean checkout with `modaccel port --fix`, and shows each against what is recorded and against the last run, then the errors still left grouped by the game update that caused them. `--only ID`, `--list`, `--record`, and `--modaccel PATH` to test another worktree's modaccel. Clones and results live in `.runs/port-board/`.

The port sweep, for what is missing across many mods rather than one:

- `bench/sweep/corpus.json` pins open-source mods (Fabric yarn and Mojang-named, NeoForge, multi-loader and Stonecutter builds, 1.16.5 to 26.3) to a commit; they are test subjects only, never a source of rules.
- `python bench/sweep.py plan` lists the ports: each mod's adjacent release and the common long jumps (older -> 1.20.1, 1.20.x -> 1.21.1, 1.21.x -> 1.21.6 and -> 26.3, 26.x either way).
- `python bench/sweep.py run --tag NAME [--jobs 2] [--resume] [--modaccel PATH]` builds each pinned source on its own version first, then ports it; per run it writes `.runs/sweep/runs/NAME/` with the build result, errors before and after, each error's class and API, wall times, and where it built a hidden load check (ui-check plus the loader's mod list) and a registry check of the blocks and items the mod's own assets name. Each mod's Gradle daemons and game are stopped as soon as its ports finish. `--modaccel` sweeps a frozen worktree while this one changes.
- `python bench/sweep.py summary --tag NAME [--compare OLD] [--save]` prints pass rates per jump and the ranked gap tables (error classes, then APIs, by mods blocked); `--save` keeps the compact result in `bench/sweep/results/NAME.json`.

- `python bench/rule_test.py [--only RULE_ID] [--write]` gives each port rule without a test one from real mod source: the first code in the pinned mods its `find` matches, and what the porter's own rewrite makes of it. `--source DIR:yarn|mojmap:LABEL` searches another checkout too.

Generated, on main only (`.github/workflows/generated.yml` runs these after every merge and commits the result):

- `python bench/bench_page.py` renders `docs/index.html` (the existing dashboard and porting map) and recreates `ROADMAP.md`; `--roadmap` writes only `ROADMAP.md`. The generators and their output are unchanged by this documentation cleanup.
- `python bench/test_measures.py`, `python bench/test_record.py` and `python bench/test_ledger_rule.py` are the PR checks for all of the above.

What a game session costs:

- `python bench/session_cost.py client --root PROJECT [--record]` measures one hidden client from cold, idle, warm, from the saved launch and with a dedicated server: memory, CPU, time and windows left on screen, read from the operating system.
- `python bench/session_cost.py parallel --root PROJECT --count 8 [--jvm FLAGS] [--record]` starts that many hidden clients at once from the saved launch, each to a 1280x720 capture.
- `--record` keeps the result in `bench/session-cost/` and adds its points to `coverage/measures.json`. `python bench/test_session_cost.py` (PR CI, all three systems) checks the process reader.

`bench/viz.py` holds the chart components (sparkline card, Gantt timeline, burn-up, columns, status chip). Draw any new number with one of them, or add a component there.

`bench/data.json`, `port/results.json` and `mods/results.json` are retained machine-readable measurements; cloned mods and logs are ignored. Compare runs only when commands, assertions, server builds and cache conditions match; historical AgentCraft runs used different setups and do not establish a speedup.

Performance evidence: [performance.md](performance.md) documents capture validation, per-run distributions and paired comparisons with uncertainty. Run `python3 bench/performance.py --help`. This analyzes supplied captures; it does not collect game telemetry.

Live JVM/game captures and local scenario reports: [PERFORMANCE.md](../PERFORMANCE.md).
