---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by teaching the porter one changed part of the game at a time rather than fixing one mod's compiler errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

You never work on "this mod's errors". You teach the porter one changed part of the game, and every mod that uses
that part is fixed with it. Read this whole file before the first command: it is short, and each part was measured.

## Where porting stands (2026-11-18)

- A mod comes in as source or as a `.jar`. `mmt port --jar MOD.jar --to 1.21.2` (tools/JarPort.java) moves a compiled
  NeoForge mod with no source and no build, and names every reference that does not link instead of guessing.
- `python bench/exposure.py jars --loader neoforge --from 1.21.1 --to 1.21.2` (90 s) ports the 150 most-downloaded
  NeoForge mods that way: **31 link against 1.21.2**, and it ranks what holds the others back. 87 of the 150 still
  have six or more broken references, so the count moves in steps once the top entries are done, not per entry. This is the number to
  move. `exposure.py run` says 58, but it cannot see mixins, field types or access transformers: of the 35 standalone
  mods it cleared, 6 failed on a real server, and the link check had named all 6.
- `python bench/exposure.py load ...` is the proof: those 35 ported jars start on a real 1.21.2 server, 29 load, and
  every mod the link check says links loads. A server never runs client code, so a client proof is step 51.
- 16 of the 126 corpus ports build (`python bench/measures.py table port.corpus-builds`). 23 of the 126 are mods
  whose untouched original does not build on the runner, and 72 cross many updates at once, so this number only
  moves after single updates work. Use it as the proof at the end of a PR, never to choose what to do next.

## What was tried, with its result. Don't repeat these

| Tried | Result |
|---|---|
| A rule for each compiler error of the mod in front of you (Nov 14 to 17, 355 rules) | 10 to 15 ports in a day; a rule freed 0 to 2 ports |
| Building from the loader's starter project instead of the mod's build script (Nov 18, PR #109, closed) | 16 to 14 ports: it freed none |
| Choosing work from `exposure.py run` (Nov 18) | 58 cleared; 6 of its 35 standalone mods fail on a real server, on mixins and field types it cannot see |
| Choosing work from `mmt port-plan`'s list (Nov 18) | of the 21 ports it said had nothing left, 7 built. It reads NeoForge mods (109 changed game parts a port) but almost nothing in Fabric mods (5 a port) |

What they share: the work was chosen from a list nobody had checked against real builds. **A list may choose the
work only after it predicts the result:** the mods it clears build, and the ones it doesn't have errors where it says.

## What comes first: two jobs on the tool

Step 50 is done: a `.jar` is ported by changing its classes, so these two are now about jars (`coverage/roadmap.json`,
Goal 2). Each needs someone who can change `tools/JarPort.java`, `tools/Exposure.java` and `bench/exposure.py`
safely. If that is not you, say so to Jon and stop; adding text rules instead is the mistake in the first row above.

1. **Step 44: one worklist, read from jars, for every loader.** `exposure.py jars` replaces `exposure.py run`, and
   reads Fabric mods too: they ship in intermediary names, so they are read through the game's own mappings to
   Mojang's names. Done when `run` is gone and the count covers the 150 most-downloaded Fabric mods as well.
2. **Step 47 (doing): a fix names a game part and is applied in the class.** What the game's own change map knows
   (calls switched, added arguments, chains, narrower fields) is applied in classes already; rules are not yet. A rule in `knowledge/fixes.json` names the game
   member it replaces and what replaces it (a call, a chain, a field read through a method); JarPort applies it where a
   class references that member, and a source port where the compiler resolved it. Its test links `before` against
   the old release and `after` against the new one. The jar scoreboard's top entries are the order.

## The loop, once steps 44 and 47 are in. One changed game part per turn

1. `python bench/exposure.py jars --loader neoforge --from A --to B --save NAME`. On a new PC first:
   `python bench/exposure.py fetch --loader neoforge --version A`. It prints the mods that link, and the references
   that do not, ranked by how many mods they hold back; `exposure.py run` adds what the game's own code did about each.
2. Take the top part whose line says the game shows the answer (`game: replaced`, `split`, `linked`, `template`).
   The detail names what the game's own code calls now. Skip `redesigned` and `unused` unless you can read both
   versions of the game class and say what replaces it; if you can't, leave it. A wrong rule is worse than none.
3. Write one rule in `knowledge/fixes.json` with `members` naming that game part, then
   `python bench/rule_test.py --only ID --write`. Copy the nearest rule that has `members`; never name a mod.
4. Run step 1 again. The part must be gone from the list and the mods count must not drop. If neither moved, the rule
   does not do what you think: fix it or delete it.
5. `python bench/exposure.py load --loader neoforge --from A --to B` starts the ported standalone mods on a real server;
   no mod that links may fail. `python bench/port_board.py --changed` runs the pinned source ports your rule crosses. Commit.

Stop and tell Jon when: three parts in a row each freed no mod (the list has reached parts that need the game class
read in both versions; say which); a rule needs to name a mod; or the count of mods with nothing left drops.

## Rules that don't bend

- Sources are the game's and the loaders' own files (jars, mappings, starter projects). Never other people's porting
  work: not guides or primers, not how another mod was ported. Mod jars are only counted.
- No AI call inside the tool. What you work out is saved as a rule with a test.
- Prove in game at the smallest scope before the PR ends: the sweep without `--no-game` loads the ported mod and
  checks what it registers; `./mmt game-stop` afterwards. The whole corpus runs in the cloud once, on the PR's final
  commit: `tools/cloud-run ports`.
- End the session by writing the state back: the new number (`python bench/measures.py add`), the roadmap step's
  result, and this file if a row belongs in the table above.
