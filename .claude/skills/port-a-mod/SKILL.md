---
name: port-a-mod
description: Port a Minecraft mod from one version to another with modaccel and turn everything that breaks into rules, so the next mod on the same step ports by itself. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", or asks to push the porting map further.
---

# Port a mod

The goal is not one ported mod. It is a porter that needs no hand edits: every failure a mod shows becomes a rule,
a repair guide or a porter fix, and the next mod across the same step gets it for free. A hand edit to the mod is a
failure of this pipeline; if one is unavoidable, name the file in the ledger row.

## The ladder

A port of any distance is one command: `port` walks the porting map (`coverage/changes/index.json`) from the source
version to its family hub, hub to hub, then down to the target, and runs only the rules whose `jump` covers a step it
crosses. So work grows in two directions:
- **Bigger mods on a proven step.** A small mod touches little of the game. Prove the step with a small mod, then a
  mod that uses more of the game (rendering, networking, items, world generation, mixins). Each one finds rules the
  smaller one never needed.
- **The same mod over more steps.** Once a mod reaches behaviour on one step, take it one family further.

Pick the next (mod, loader, from, to) from what is weakest on the map: a step with no `behaviour` row in
`coverage/ledger.json`, or a family hub no mod has reached. Prefer open-source mods people use (Modrinth downloads).

## Statuses (the ledger's words)
- `stop`: the port ran and errors remain. Record the count.
- `build`: it compiles for the target.
- `load`: the game starts with it, hidden and quiet.
- `behaviour`: a check proves the mod's own feature works in the real game, and fails when the mod is broken on purpose.

## Steps

1. **Pin it.** Add the port to `bench/port-cases.json`: the mod's name, repository, the commit, loader, from and to
   (and a `measure` once it has an errors-left measure). `python bench/port_board.py --only <id>` then clones it into
   `.runs/port-board/<id>`, puts it back to that commit before every run, and refuses a pin whose `gradle.properties`
   is on another version than `from`. Check `owl answers whats-running` before heavy work.
2. **Baseline.** `modaccel compile --root <mod> --json` on its own version. If it does not build there, that is not a
   porting problem: fix the setup first or pick another mod.
3. **Port.** `modaccel port --root <mod> --from A --to B --fix --json`. A cold NeoForge setup decompiles the game once
   per NeoForge version per machine: about 2 minutes on an idle 8-core machine (the boat Linux host), hours on a busy
   PC. Every forward port moves to the same NeoGradle, so the next mod on that version reuses the setup from NeoGradle's
   shared cache; `python -I bench/neoform_steps.py <mod> --since <port start>` shows each step's time and how many cache
   entries the build made (0 = nothing decompiled again). Run NeoForge ports one at a time, so the second finds the
   first one's setup. `python bench/port_board.py --only <id>` runs the same port from the pinned source and shows
   the errors left against the record and the last run, grouped by the update that caused them; add `--record` to
   write the ledger row and the measure point when the result changed.
4. **Sort the errors by cause, not one by one.** The port names the update behind each error. For each cause:
   - **A mechanical change** (rename, moved package, new signature, replaced call): a rule in `knowledge/fixes.json`
     with `jump` and `match`. Write it in Mojang names and, for Fabric, yarn names too. Then
     `python bench/rule_test.py --only <rule id> --write` gives it its `test {error, before, after}` from the pinned
     mods' real source (`--source DIR:yarn|mojmap:LABEL` for a mod that is not pinned); a rule with no test fails
     `modaccel self-test`.
   - **Needs new code** (a removed system, a redesigned API): a guide in `knowledge/structural.json`, so a stopped port
     names it with the fix to write.
   - **Build setup** (Loom, Gradle, NeoForge or Fabric versions, Parchment, Java): the planner in
     `tools/cli/Target.java`, with a `PortableTests` case. Anything in the build tied to the source version is suspect:
     Parchment names for 1.21.5 once compiled the 26.3 game wrong and no world loaded.
   - **The porter crashed or said something unclear**: fix it in `tools/cli`, with a `StrangerTest` or `PortableTests`
     line.
   - **The network** (a timeout, a 504): not code. Rerun; if it keeps happening, make the failure retry or say so.
   Then run `python bench/port_board.py` again: it ports every pinned mod from its original source, so the error
   must be gone with no hand edit, and no other mod may have got worse (it exits 1 if one did).
5. **Repeat 3 and 4 until it builds.** Ledger status `build`.
6. **Load.** Start the game hidden through modaccel (`ui-check`, then `world-check` or `eval --until` a world is
   loaded). If the world never loads, suspect the build setup before the mod: disable the mod's own registrations to
   tell the two apart. On a Linux machine with no screen (the boat T3 host), run the client and the checks under
   `xvfb-run -a -s "-screen 0 1280x720x24"` with Mesa's software OpenGL, as `bench/behaviour_checks.sh` does. Ledger
   status `load`.
7. **Behaviour.** Pick the mod's main feature and check it in the real game through `modaccel eval` snippets, the
   way `tools/tests/AppleSkinHudCheck.java` checks AppleSkin's HUD against real regeneration and
   `tools/tests/ZombieAiCheck.java` checks zombie AI and `tools/tests/NotesPinCheck.java` checks Notes' pinned-note HUD
   layer. Then break the mod on purpose once (one changed number in its
   source, restored in `finally`) and see the check fail at the comparison. Ledger status `behaviour`.
8. **Record and check**, on the `porting` branch:
   - the ledger row: `python bench/record.py port RECEIPT --mod NAME` writes it from the receipt `modaccel port`
     printed. `load` and `behaviour` are claims about the game, so they need `--status` and a `--detail` saying what
     was checked, by which command, and any hand-written file;
   - measure points with `python -I bench/measures.py add`, and the roadmap step with
     `python bench/record.py step ID done --result "what ran and what decided the pass"`;
   - `modaccel self-test` from a freshly packaged jar (a failure is reported first; `--only SUITE` reruns one suite),
     `python bench/test_measures.py`, `python bench/test_record.py` and `python bench/test_ledger_rule.py`.
     `ROADMAP.md` and `docs/` are written on main after the merge; don't regenerate them;
   - every new check broken once on purpose and seen to fail.
9. **Next rung.** A bigger mod on the same step, or the same mod one family further. Back to step 1.

## Landing
Porting commits go on the `porting` branch, in one draft PR titled "Porting" whose body is the checklist. It merges at a
milestone (a big porting jump, for example a mod family reaching a new hub with behaviour proven), with CI green on all
3 OS, then a release tag makes a draft release. See `AGENTS.md`.
