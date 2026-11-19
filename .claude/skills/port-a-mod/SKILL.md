---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by teaching the porter one changed part of the game at a time rather than fixing one mod's compiler errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

You never work on "this mod's errors". You teach the porter one changed part of the game, and every mod that uses
that part is fixed with it. Read this whole file before the first command: it is short, and each part was measured.

## Where porting stands (2026-11-19)

- A mod comes in as source or as a `.jar`. `mmt port --jar MOD.jar --to 1.21.2` (tools/JarPort.java) moves a compiled
  NeoForge mod with no source and no build, and names every reference that does not link instead of guessing.
- **The number to move:** `tools/cloud-run exposure` (8 minutes; `python bench/exposure.py all` runs the same here)
  ports the 150 most-downloaded mods of each release to the next and links them, for 43 Fabric updates from 1.14.4
  and 18 NeoForge updates from 1.20.5: **5,258 of 9,149 mod ports link (57.5%)**, table in `bench/exposure/all.json`
  and `port.jar-links.every-update`. It also ranks what holds the most mods back across every update. Run it on your
  PR's last commit and put the before and after in the PR (#120 moved it from 5,018).
- One update: `python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2` (about 2 minutes):
  NeoForge 45 of 150 link, Fabric 40. Fabric jars are read in Mojang's names through intermediary first
  (tools/ModNames.java). Each line of its list says what the game's own code did about that reference.
- `python bench/exposure.py load ...` is the proof: the standalone mods `jars` says link are ported and started on a
  real 1.21.2 server; 23 of 25 load, one is client-only and one has no code (its data uses an effect 1.21.2 removed).
  A server never runs client code, so a client proof is step 51.
- 16 of the 126 corpus ports build (`python bench/measures.py table port.corpus-builds`). 23 of the 126 are mods
  whose untouched original does not build on the runner, and 72 cross many updates at once, so this number only
  moves after single updates work. Use it as the proof at the end of a PR, never to choose what to do next.

## What was tried, with its result. Don't repeat these

| Tried | Result |
|---|---|
| A rule for each compiler error of the mod in front of you (Nov 14 to 17, 355 rules) | 10 to 15 ports in a day; a rule freed 0 to 2 ports |
| Building from the loader's starter project instead of the mod's build script (Nov 18, PR #109, closed) | 16 to 14 ports: it freed none |
| Choosing work from `exposure.py run` (Nov 18) | 58 cleared; 6 of its 35 standalone mods fail on a real server, on mixins and field types it cannot see |
| A rule with `inClass` for each top-ranked changed member of 1.21.2 (Nov 18, PR #118, 57 replacements) | 31 to 41 of 150 link. The rest are held back by 1.21.2's rendering and recipe rewrites (render states, toasts, recipe book), which one-member replacements cannot express; fixing every mixin signature change would add about 4 |
| JarPort fixes that need nothing learned, every update (Nov 18, step 47b): mixin @Inject moved to a target's new signature, override bridges, access transformers opened or cleaned | 41 to 43 of 150 link on 1.21.2, 33 to 36 on 1.21.5. What is left is learned replacements and redesigned APIs; the other mixin signature changes capture locals, wrap the changed call, or inject where the new method no longer has the point |
| Choosing work from `mmt port-plan`'s list (Nov 18) | of the 21 ports it said had nothing left, 7 built. It reads NeoForge mods (109 changed game parts a port) but almost nothing in Fabric mods (5 a port) |
| GameDiff's Learner: what the game's new code computes at each use of a changed member, as an `inClass` expression (Nov 19, PR #121) | 1,413 -> 2,462 of 49,503 gone members get a replacement over all 43 updates; 43 -> 45 of 150 link. What is left is mostly redesigns, mixins, overrides and value-to-value changes (`false` -> `TriState.DEFAULT`) an expression cannot say |

What they share: the work was chosen from a list nobody had checked against real builds. **A list may choose the
work only after it predicts the result:** the mods it clears build, and the ones it doesn't have errors where it says.

## What comes first: jobs on the tool

Step 50 is done: a `.jar` is ported by changing its classes. Step 44 is done: one scoreboard, read from jars, for every
update and both loaders. What remains on the tool (`coverage/roadmap.json`, Goal 2) needs someone who can change
`tools/JarPort.java` safely. If that is not you, say so to Jon and stop; adding text rules instead is the mistake in
the first row above.

1. **Step 47 (doing): a fix names a game part and is applied in the class.** What the game's own change map knows
   (calls switched, added arguments, chains, narrower fields) and every rule's `inClass` (a Java expression for the
   old member, compiled by JarPort against the new release, see `tools/FixExpr.java`) are applied in classes. Still to
   do: the source port applies the same `inClass` where the compiler resolved the member, instead of a regex.
2. **Step 52: `mmt port --jar` ports Fabric mods too.** The scoreboard reads them; the port must hand them back in
   the names Fabric runs.

## The loop. One changed game part per turn

1. `python bench/exposure.py jars --loader neoforge|fabric --from A --to B --save NAME`. It downloads the pinned mods
   the first time, prints the mods that link, and the references that do not, ranked by how many mods they hold back,
   each with what the game's own code did about it. To pick the update, read the ranked list across every update in
   `bench/exposure/all.json`.
2. Take the top part whose line says the game shows the answer (`game: replaced`, `split`, `linked`, `template`).
   The detail names what the game's own code calls now. Skip `redesigned` and `unused` unless you can read both
   versions of the game class and say what replaces it; if you can't, leave it. A wrong rule is worse than none.
   Before writing a rule, see why the learner gave none: run GameDiff with `-Dgamediff.learnlog=PREFIX` and read the
   member's line (game uses lined up, uses read, votes, readings). A fix to the learner frees every update; a rule one.
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
