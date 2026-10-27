# Benchmarks

The bench page (`docs/index.html`) is built by `python bench/bench_page.py` from:

- `coverage/ledger.json`: every port that was run, with its timing. A run not in the ledger did not happen.
- `bench/data.json`: edit-loop timings, with `n` where a number was repeated. Never mix warm and cold in one row.

`port/results.json` and `mods/results.json` are earlier real-mod measurements; the cloned mods and their logs are
ignored on purpose.
