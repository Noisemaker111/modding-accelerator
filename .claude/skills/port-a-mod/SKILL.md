---
name: port-a-mod
description: Port a Minecraft mod from one version to another with mmt and turn everything that breaks into rules, so the next mod on the same step ports by itself. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", or asks to push the porting map further.
---

The goal is a porter that needs no hand edits: every failure becomes a rule, a guide or a porter fix. Ledger
statuses: `stop` (errors left), `build`, `load`, `behaviour` (a game check that fails when the mod is broken on purpose).

1. Pin the port in `bench/port-cases.json` (repo, commit, loader, from, to). Baseline: `mmt compile` on its own version.
2. `mmt port --root R --from A --to B --fix --json`. Sort the errors by the update that caused them:
   a mechanical change → a rule in `knowledge/fixes.json` plus `python bench/rule_test.py --only ID --write`;
   new code needed → a guide in `knowledge/structural.json`; build setup → `tools/cli/Target.java`; a porter bug →
   `tools/cli` with a self-test case.
3. Check locally with `python bench/port_board.py --changed` (only the cases your rules cross; it fails on any error
   a case's last run did not have). Only the whole corpus runs in the cloud: `tools/cloud-run ports`.
4. Repeat until it builds; then load it hidden (`ui-check`, `world-check`) and prove its main feature with an `eval`
   check like `tools/tests/AppleSkinHudCheck.java`, broken once on purpose.
5. Record: `python bench/record.py port RECEIPT --mod NAME` (`load`/`behaviour` need `--status` and `--detail`).
