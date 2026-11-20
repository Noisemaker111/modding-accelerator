---
name: port-a-mod
description: Make mmt port carry any mod across Minecraft versions with no hand edits, by teaching the porter one changed part of the game at a time rather than fixing one mod's compiler errors. Use when Jon names a mod and a target version, says "next mod", "bigger mod", "next version", "keep going" on porting, or asks to push the porting map further.
---

You never work on "this mod's errors". You teach the porter one changed part of the game, and every mod that uses
that part is fixed with it. Read this whole file before the first command: it is short, and each part was measured.

## Where porting stands (2026-11-20)

- A mod comes in as source or as a `.jar`. `mmt port --jar MOD.jar --to 1.21.2` (tools/JarPort.java) moves a compiled
  NeoForge or Fabric mod with no source and no build, and names every reference that does not link instead of guessing.
  A Fabric jar is read in Mojang's names and handed back in the target release's intermediary (`ModNames.Back`).
- **The number to move:** `tools/cloud-run exposure` (`python bench/exposure.py all` runs the same here) ports the
  150 most-downloaded mods of each release and links them against the target release and its loader (NeoForge with FML
  and its event bus; Fabric Loader and Fabric API). It counts three things, in `bench/exposure/all.json`:
  **jumps** over several updates, from each family's most-used release to the next family's and to the newest
  release (`port.jar-links.jumps`), which is what people ask for; the **big updates**, each into a new family; and
  the small ones, across which nearly every mod links untouched. One update at a time, all together, was the old
  headline (`port.jar-links.every-update`) and flattered the porter: half its updates are small. Run it on your PR's
  last commit and put the before and after of the jumps and the big updates in the PR.
- One update or one jump: `python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2` (about
  2 minutes; `--to 1.21.4` or `--to 26.3` is a jump). It prints **the mods nearest to done**, each with the
  references it still needs and what the game's own code did about each one: that is the to-do list. Fabric jars are
  read in Mojang's names through intermediary first (tools/ModNames.java).
- `python bench/exposure.py load ...` is the proof: the standalone mods `jars` says link are ported and started on a
  real 1.21.2 server and then in a real 1.21.2 client (`mmt pack-check --side client`), each mod's two verdicts side by
  side. Server: 23 of 25 load (one client-only, one with no code fails on its data). Client: 23 of 24 load; Puzzle loads
  on the server and links but fails in the client, because its injection point (a call 1.21.2's loading overlay no
  longer makes) is inside a method that still exists. The client covers the title screen, a world, the inventory and
  every creative tab, not a mod's own screens.
  On Fabric (`--loader fabric`) Fabric API is in the pack, and the client is Fabric's own (intermediary, read by mmt's
  agent through Mojang's and Fabric's mappings): 13 link; server 7 of 7 that a server runs, client 12 of 13 (Better Mount HUD:
  its @ModifyVariable finds no target in 1.21.2's HUD code, step 55).
- `jars` also checks each mod's data files for built-in game ids the new release dropped (`modsThatLinkAndData`).
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
| Members the route map follows that still break, learned too: a closed member from its exact public wrapper, a closed field from its accessor, moved fields, generic results; readings seen only with literal arguments refused (Nov 19, PR #126) | every update 5,302 -> 5,365 of 9,149, none lower. Overrides with retyped parameters freed 1-2 mods per update when measured, so they wait for passing the new argument into the old method. The link check does not read NeoForge's loader (FML) classes: 2 mods that link failed on `ModLoadingContext.registerConfig` |
| Calls the game removed learned as dropped (uses vanish, or the statement is only the receiver chain), and replaced classes as a class map from the types the game's own members and values switched to (Nov 20, PR #128) | every update 5,826 -> 5,854 of 9,423, none lower; Fabric 1.18.2 -> 1.19 34 -> 49 (TextComponent). Most mods that drop endVertex() still don't link on 1.21: getBuilder() + begin() became one call, which no one-member replacement says. JOML (1.19.3) is a library the snapshot doesn't read |
| Taking the reference the most mods share first, which every row above did (measured Nov 20 on NeoForge 1.21.1 -> 1.21.2) | a mod held back has 15 references at the median, and 1,241 of the 2,060 are used by one mod each: the 100 most shared freed 2 mods, the 500 most shared 16. The mods nearest to done first: 15 mods are one reference away, 26 are done after 40 fixes. The first one taken that way (ServerPlayer.teleportTo) freed Chunky, which loads on a real 1.21.2 server |
| Linking against the loader too, reading NeoForge 1.20.2-1.20.4, and counting mods whose code the port never reads as not read (Nov 20, PR #127) | every update 5,775 -> 5,586 on the same 61 updates (loader breaks -28 net; hidden code -165), 5,826 of 9,423 over 64. The compasses' FML break is now porting work; loader breaks are ranked with game ones |
| Overrides whose parameters changed type moved whole to the new signature, each old parameter worked out from the new ones as the game's own overrides do (graphics.pose(), event.key()), and calls in the body given the new arguments; every injector's @At checked against the new method, a gone point a break only where require says so (Nov 20, steps 55 and 59) | every update 5,854 -> 5,838 of 9,423: +1 from moved overrides, -17 real breaks (required injection points gone; Better Mount HUD crashes on one in a real client). The screen family stays blocked by calls whose receiver changed (Font.draw(pose, ...) -> graphics.drawString(font, ...)), which need the new parameter as an operand of a learned replacement |

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
   the first time and prints the mods nearest to done, each with the references it still needs and what the game's
   own code did about each. To pick the update, read `nearest` in `bench/exposure/all.json`: the mods with the
   fewest left over every update.
2. Take the first mod on that list whose every reference the game answers (`game: replaced`, `split`, `linked`,
   `template`), and its first reference. The detail names what the game's own code calls now. Skip a mod with a
   `redesigned` or `unused` reference unless you can read both versions of the game class and say what replaces it;
   if you can't, take the next mod. A wrong rule is worse than none. The rule still names a game part, never the
   mod: the mod only chooses which part comes next, because a part that finishes no mod helps nobody yet.
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
