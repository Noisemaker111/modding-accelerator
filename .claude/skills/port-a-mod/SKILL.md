---
name: port-a-mod
description: Port a Minecraft mod from one version to another with mmt and turn everything that breaks into rules, so the next mod on the same step ports by itself. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", or asks to push the porting map further.
---

The goal is a porter that needs no hand edits: every failure becomes a rule, a guide or a porter fix. Ledger
statuses: `stop` (errors left), `build`, `load`, `behaviour` (a game check that fails when the mod is broken on purpose).

1. Pin the port in `bench/port-cases.json` (repo, commit, loader, from, to). Baseline: `mmt compile` on its own version.
2. `mmt port --root R --from A --to B --fix --json`. Before writing anything, cluster: every sweep error carries
   `changed` (the game change GameDiff links it to); group by it across the whole corpus
   (`.runs/sweep/runs/TAG/*.json`, or a cloud sweep's download), largest number of mods first. Fix in this order:
   - the map already knows it (`renamed`/`moved`) but it is still an error: a porter gap in `tools/VersionMap.java`
     (`Fix.run`) or `tools/cli/Port.java`, never a rule (overrides are renamed by the class's own supertypes,
     on-demand imports of moved packages are expanded);
   - one game change, many names: generate one rule from the game's own mappings (`~/.mmt/mappings/client-V.txt`,
     `yarn-V*.jar`); a replace can use `$^N` for group N in UPPER_SNAKE (getter → constant: `core-shaders-1212`);
   - only what is truly semantic by hand. Then sort what is left by the update that caused it:
   a mechanical change → a rule in `knowledge/fixes.json` plus `python bench/rule_test.py --only ID --write`;
   new code needed → a guide in `knowledge/structural.json`; build setup → `tools/cli/Target.java`; a porter bug →
   `tools/cli` with a self-test case.
3. Check locally with `python bench/port_board.py --changed` (only the cases your rules cross; it fails on any error
   a case's last run did not have). Only the whole corpus runs in the cloud: `tools/cloud-run ports`.
4. Repeat until it builds; then load it hidden (`ui-check`, `world-check`) and prove its main feature with an `eval`
   check like `tools/tests/AppleSkinHudCheck.java`, broken once on purpose.
5. Record: `python bench/record.py port RECEIPT --mod NAME` (`load`/`behaviour` need `--status` and `--detail`).
