---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by teaching the porter one changed part of the game at a time rather than fixing one mod's compiler errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

You never work on "this mod's errors". You teach the porter one changed part of the game, and every mod that uses
that part is fixed with it. Read this whole file before the first command: it is short, and each part was measured.

## Where porting stands (2026-11-18)

- `python bench/exposure.py run --loader neoforge --from 1.21.1 --to 1.21.2` (33 s, no build) reads the 150
  most-downloaded NeoForge mods: **58 need nothing changed that the porter can't do; the other 92 are held by 637
  changed game parts**, 313 of them used by one mod each. That is the size of one big update, and the number to move.
- 16 of the 126 corpus ports build (`python bench/measures.py table port.corpus-builds`). 23 of the 126 are mods
  whose untouched original does not build on the runner, and 72 cross many updates at once, so this number only
  moves after single updates work. Use it as the proof at the end of a PR, never to choose what to do next.

## What was tried, with its result. Don't repeat these

| Tried | Result |
|---|---|
| A rule for each compiler error of the mod in front of you (Nov 14 to 17, 355 rules) | 10 to 15 ports in a day; a rule freed 0 to 2 ports |
| Building from the loader's starter project instead of the mod's build script (Nov 18, PR #109, closed) | 16 to 14 ports: it freed none |
| Choosing work from `mmt port-plan`'s list (Nov 18) | of the 21 ports it said had nothing left, 7 built. It reads NeoForge mods (109 changed game parts a port) but almost nothing in Fabric mods (5 a port) |

What they share: the work was chosen from a list nobody had checked against real builds. **A list may choose the
work only after it predicts the result:** the mods it clears build, and the ones it doesn't have errors where it says.

## What comes first: two jobs on the tool

Read roadmap step 50 before either: the mod is to go in and come out as a `.jar` (Jon, 2026-11-18), and how that is
done decides whether these two jobs stay as written. Until step 50 has chosen, don't start them.

These are roadmap steps 44 and 47 (`coverage/roadmap.json`, Goal 2). Each needs someone who can change
`tools/cli/Port.java` and `tools/cli/PortPlan.java` safely. If that is not you, say so to Jon and stop; adding rules
instead is the mistake in the first row above.

1. **Step 44: the worklist sees every mod.** `mmt port-plan` compiles a mod on its own version and lists every place
   it touches game code, with the exact game method or field. For Fabric mods it finds almost nothing: betterf3 has
   5,289 lines and 28 places. Find why and fix it. Two guesses nobody has checked yet: the game jar Loom builds
   against is not on the compile path, and yarn names are never joined with the change map's Mojang names.
   Check: `python bench/port_plans.py run`, then compare with the last full sweep. Done when no port the list clears
   stops on compiler errors (8 do today) and Fabric ports list changed game code at the rate NeoForge ports do.
2. **Step 47: a rule names a game part and is applied only there.** Today a rule is a pattern on the compiler's error
   text plus a find-and-replace on the file. It cannot tell `LocalPlayer.sendSystemMessage` (removed in 1.21.2) from
   `ServerPlayer.sendSystemMessage` (still there): both are `.sendSystemMessage(` in the text. port-plan already has
   each place's start, end and resolved game member, so a rule's `members` can be applied at exactly those places,
   before the first compile. A rule's test then compiles its `before` on the old game and its `after` on the new one.
   Done when the 63 rules that name `members` are applied that way and the 16 building ports still build.

## The loop, once steps 44 and 47 are in. One changed game part per turn

1. `python bench/exposure.py run --loader neoforge --from A --to B --save NAME`. On a new PC first:
   `python bench/exposure.py fetch --loader neoforge --version A`. It prints mods with nothing left, and the changed
   game parts ranked by how many mods use them, each with what the game's own code did about it.
2. Take the top part whose line says the game shows the answer (`game: replaced`, `split`, `linked`, `template`).
   The detail names what the game's own code calls now. Skip `redesigned` and `unused` unless you can read both
   versions of the game class and say what replaces it; if you can't, leave it. A wrong rule is worse than none.
3. Write one rule in `knowledge/fixes.json` with `members` naming that game part, then
   `python bench/rule_test.py --only ID --write`. Copy the nearest rule that has `members`; never name a mod.
4. Run step 1 again. The part must be gone from the list and the mods count must not drop. If neither moved, the rule
   does not do what you think: fix it or delete it.
5. `python bench/port_board.py --changed` runs the pinned ports your rule crosses and fails on any new error. Commit.

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
