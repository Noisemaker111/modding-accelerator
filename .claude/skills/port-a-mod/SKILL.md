---
name: port-a-mod
description: Make mmt port carry mods across a Minecraft version step with no hand edits, by closing the step's whole list of changed game APIs rather than one mod's errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", or asks to push the porting map further.
---

The unit of work is a **version step**, not a mod. A step is done once and then serves every mod that crosses it;
long jumps chain steps. Fixing one mod's errors in the order it hits them grows as mods x steps x errors and never
finishes (Farmer's Delight 1.21.1 -> 1.21.2: weeks, 412 -> 202 errors, still not building).

**The list.** `coverage/changes/A__B.json` lists every game member the step changed (1.21.1 -> 1.21.2: 3,818).
`python bench/exposure.py run --loader L --from A --to B --save NAME` ranks the ones popular mods use, and gives
each a label:
- **automatic**: the map renames it, or the game's own code shows its replacement (GameDiff `rewrites` /
  `classRewrites`, mined from how vanilla and NeoForge rewrote their own call sites), or a general porter step
  (dropped arguments, added registry, id/key conversion) applies. Generated, with a test.
- **template**: a redesign with no one-to-one replacement. One transform per redesign, written once from the loader's
  porting primer (NeoForge: `neoforged/.github` `primers/B/index.md`), kept behaviour proven in game.
- **no equivalent**: the porter stops and names the construct and the primer section; never partial output.

**The number** is the exposure-weighted share of uses that are automatic or template, plus mods that port with zero
edits and load in game. Error counts of one port are diagnostics only.

Order of work, per step:
1. Run exposure; take the unlabelled member that the most mods use.
2. Ask why the game's own code did not give its replacement (no vanilla call sites, callers redesigned, votes below
   threshold, a field or class the miner does not follow). Fix the miner when it can say; that labels every member
   of that kind at once, in every step.
3. Otherwise one template from the primer section, or a rule generated from the game's mappings (`$^N`,
   `call-arguments`); a hand rule in `knowledge/fixes.json` only for what is left, with
   `python bench/rule_test.py --only ID --write`. Never from another mod's code: mod jars are only counted.
4. Re-run exposure; the step's number must rise. Check real ports with `python bench/port_board.py --changed`; only
   the whole corpus runs in the cloud (`tools/cloud-run ports`, once per PR).
5. Prove in game at the smallest scope: a ported mod loads hidden (`ui-check`, `world-check`) and an `eval` check like
   `tools/tests/AppleSkinHudCheck.java`, broken once on purpose. Record with `python bench/record.py port RECEIPT
   --mod NAME`.
