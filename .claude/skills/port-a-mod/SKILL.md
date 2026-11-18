---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by fixing changed game parts once for every mod rather than one mod's compiler errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

**Start from the files, not the conversation.** Chat gets summarized and loses decisions; these do not:
- `coverage/roadmap.json`, Goal 2: the decisions (latest: 2026-11-17, why error-text rules stopped scaling) and the
  steps marked `doing` and `next`. Work on a `doing` step.
- `coverage/measures.json`: `port.corpus-builds` (whole corpus, every target version) is the number. `python
  bench/measures.py table port.corpus-builds` prints where it stands.
- The open PR's Approach section (`gh pr list --author @me`), for what is in flight.
If an instruction pulls against the latest decision, say so to Jon before following either.

**The method (decided 2026-11-17).** Progress grew one port at a time while the porter read compiler error text
(355 of 358 rules matched on it). The way forward scales with changed game parts and versions, not with mods:
1. **Resolve, then one typed pass.** `mmt port-plan --root MOD [--map CHANGE-MAP]` compiles the mod on its own version
   and lists every place it touches game code, with what the target did to each. `python bench/port_plans.py run`
   then `report --left` ranks, over the whole corpus, the changes no automatic step or rule handles, by ports blocked:
   that list is the work, top first. Still to build: apply every known change at those places in one pass, before
   the first compile. A fix is stored once per changed game part.
2. **Generate the build.** The target build comes from the loader's own starter project for that version; the mod's
   code, resources and dependencies move into it.
3. **Redesigns, once each, ranked by mods freed** (`python bench/exposure.py run`): a migration written from the
   game's own API and proven in game; a removed method whose old body still compiles is inlined from it.

**Sources.** Only the game's and the loaders' own files (jars, mappings, starter projects) and tools we build. Never
other people's porting work: not guides or primers, not other mods' ports; mod jars are only counted.

**End every working session by writing the state back:**
- a new corpus number: `python bench/sweep.py run --tag T --no-game --jobs 2` (or the cloud run on a PR's final
  commit), then `python bench/measures.py add port.corpus-builds N --n PORTS --source WHERE`;
- the roadmap step's status, and a decision entry when the method changes;
- the PR's Approach section (method, numbers, next step).

**Prove in game** at the smallest scope: the sweep without `--no-game` loads the ported mod and checks its
registries; `game-stop` afterwards. Only the whole corpus runs in the cloud (`tools/cloud-run ports`, once per PR).
