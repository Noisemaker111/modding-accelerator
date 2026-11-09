# Benchmarks and the roadmap page

There is one roadmap and one way to show a number.

- `coverage/roadmap.json`: the four goals (AGENTS.md), each with its steps: status, start, target and done dates, the part of the goal a step belongs to, and the measures it moves. A goal's `headline` names the measures that say most about it.
- `coverage/measures.json`: every number we show. A measure is one thing measured the same way (warm and cold are separate measures), with dated points that each name a source, and a target with a date and the step that reaches it.
- `coverage/ledger.json`: every port that was run, with its timing. A run not in the ledger did not happen. Measures such as mods working and updates working per loader are computed from it.

Commands:

- `python bench/measures.py add ID VALUE --source WHERE [--n N] [--note TEXT]` records a point and rewrites `ROADMAP.md`.
- `python bench/measures.py table [IDS] [--area AREA] [--step N]` prints the standard Markdown table for a PR body or report.
- `python bench/measures.py check` validates both files.
- `python bench/bench_page.py` renders `docs/index.html` (roadmap, timeline, every measure, the porting map, the ledger); `--roadmap` rewrites only `ROADMAP.md`.
- `python bench/test_measures.py` fails when the files disagree or `ROADMAP.md` is behind them. PR CI runs it.

What a game session costs (step 33 onward):

- `python bench/session_cost.py client --root PROJECT [--record]` measures one hidden client from cold, idle, warm, from the saved launch and with a dedicated server: memory, CPU, time and windows left on screen, read from the operating system.
- `python bench/session_cost.py parallel --root PROJECT --count 8 [--jvm FLAGS] [--record]` starts that many hidden clients at once from the saved launch, each to a 1280x720 capture.
- `--record` keeps the result in `bench/session-cost/` and adds its points to `coverage/measures.json`. `python bench/test_session_cost.py` (PR CI, all three systems) checks the process reader.

`bench/viz.py` holds the chart components (sparkline card, Gantt timeline, burn-up, columns, status chip). Draw any new number with one of them, or add a component there.

`bench/data.json`, `port/results.json` and `mods/results.json` are earlier real-mod measurements still shown in the evidence section; the cloned mods and their logs are ignored on purpose.
