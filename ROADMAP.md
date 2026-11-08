# Roadmap

Generated from `coverage/roadmap.json` (phases, steps, dates) and `coverage/measures.json` (every number, its
history and its target) by `python bench/bench_page.py --roadmap`. Edit those two files, not this one;
`python bench/test_measures.py` fails while this file is behind them. The page with the timeline, every number
and the porting map: https://noisemaker111.github.io/modding-accelerator/

Goal: a mod or plugin author never launches Minecraft, and a modpack user need not diagnose mod conflicts by hand. Port source from any version to any other (and, separately, any loader to any other), resolve modpack incompatibilities, and prove behavior in the real game or a real server.

Status words: **done** (ran and checked), **doing** (being worked on), **next**, **later**. Every step that is
not done has a target date. Targets were set on 2026-11-06; a target that moves keeps its old date in `moved`.

| Phase | Done | Doing | Next | Later | Last target |
|---|---|---|---|---|---|
| 1. Neighbouring versions (1.21.x), Fabric and NeoForge | 8 | 1 | 1 | 0 | 2026-12-04 |
| 2. Big jumps across families (1.16.5, 1.18.2, 1.20.1 → 1.21.x) | 4 | 0 | 0 | 2 | 2027-01-08 |
| 3. 26.x, the unobfuscated game | 1 | 2 | 1 | 0 | 2026-12-04 |
| 4. Loaders | 0 | 0 | 0 | 3 | 2027-03-12 |
| 5. Proof: behaviour in the real game and server | 3 | 0 | 2 | 3 | 2027-02-12 |
| 6. Modpack compatibility | 1 | 3 | 0 | 1 | 2027-02-05 |
| 7. Testing without Minecraft open | 4 | 0 | 0 | 0 | 2026-12-18 |

## Phase 1 — Neighbouring versions (1.21.x), Fabric and NeoForge

1. Port a real Fabric mod one minor forward: F3Nope 1.21.5 → 1.21.6, build + mixin audit. **done**
    Done 2026-10-24.
2. Same mod backwards: untouched upstream 1.21.6 → 1.21.5, real client loads it. **done**
    Done 2026-10-24.
3. Prove a port's behaviour, not just its build. The first try (overlay inspected, AppleSkin
    inventory change 95.6%, save reloads at the same position) was re-marked to "loads" on
    2026-10-26: a frame change and a save reload happen without the mod. Behaviour needs an
    assertion on the mod's own state (`--assert` on its class, `--expect-value` on its screen).
    **done** — `tools/tests/AppleSkinHudCheck.java` checks AppleSkin's HUD estimate against real
    regeneration on the 1.21.6 port, and fails when the estimate is broken on purpose.
    Done 2026-10-26.
4. Second loader end to end: NeoForge ZombieApocalypseAddon 1.21.1 → 1.21.4 with no hand edits; its
    `/za` command runs in a 1.21.4 world. **done**
    Done 2026-10-24.
5. Dependency blockers become one-file answers: verified selection, disable with proof, and a
    reference that is not an `import` still counts (AppleSkin's JEI mixin). **done**
    Done 2026-10-24.
6. A port refuses to pass while it still targets the old game: packaged metadata is read back.
    **done**
    Done 2026-10-24.
7. A five-minor jump on a bigger mod: AppleSkin 1.20.1 → 1.21.6. With the render rules (step 8) and
    build tools that follow the target (step 9), 69 errors go to 11 with nothing done by hand. The
    11 are four changes that need new code: the exhaustion getter, food effects on the consumable
    component, server-only game rules and payload networking. The stopped port names a repair guide
    for each. **doing**
    Started 2026-10-24, target 2026-11-20.
8. Turn the render-pipeline family into rules (draw texture / render pipeline / texture view), then
    re-run step 7 to a build. **done** — The GUI render family is rules in yarn and official names,
    each tied to the update that changed it: the render type first in every blit (1.21.2), the
    RenderPipeline (1.21.6), shader colour into each draw call (1.21.6), blend, depth and cull state
    (1.21.5), texture-id binds (1.21.5) and texture views (1.21.6). They replace the two whole-jump
    drawTexture rules. On AppleSkin 1.20.1 → 1.21.6 no render error is left; it still stops at 11
    errors outside rendering (step 7).
    Done 2026-11-07.
9. Build-toolchain bumps follow the target automatically (AppleSkin needed Loom 1.10 + Gradle 8.13).
    **done** — A Fabric port raises Loom to the newest release of the line its target dependencies
    were built with (from each jar's Fabric-Loom-Version), then raises the Gradle wrapper to the
    version that Loom's published metadata asks for. It never lowers either. AppleSkin 1.20.1 →
    1.21.6: Loom 1.8-SNAPSHOT to 1.10.5 and Gradle 8.10 to 8.12, with no hand edit.
    Done 2026-11-07.
10. One command, no hand edits, for 1.21.1 → 1.21.6 on this mod, and the same for a second mod.
    **next**
    Starts 2026-11-20, target 2026-12-04.

## Phase 2 — Big jumps across families (1.16.5, 1.18.2, 1.20.1 → 1.21.x)

11a. Change index and hub-and-spoke families: every release from 1.14.4 to 26.3 diffed against the
    next in Mojang's names (`coverage/changes/`, `tools/ChangeIndex.java`), 24 families cut where an
    update breaks 1% or more of the game's names, each with a hub picked by Modrinth mod count, rule
    coverage and the best ledger run per step, shown in the porting map (`docs/index.html#porting`).
    Releases before 1.14.4 are listed as blocked (no official mappings). **done**
    Done 2026-10-25.
11b. Router: `port` chains hops X → family hub → hub → … → Y from `coverage/changes/index.json`, one
    record per hop; renames inside a family applied from the index. **done** — `port --from A --to B
    --fix` walks the hubs, joins every update's renames and moves into one map by intermediary ids
    (`VersionMap --chain`), runs only the rules whose jump covers an update it crosses, builds once,
    and names the update behind each error left. Releases before 1.14.4 are refused.
    ZombieApocalypseAddon 1.21.1 → 1.21.4 (three families) builds and loads; → 1.21.6 stops at the
    same 9 errors as the one-jump path, all placed on 1.21.4 → 1.21.5; → 26.3 stops at 69 instead of
    100+.
    Done 2026-10-25.
11. Chain mappings across more than one family in a single port, with one record per hop. **done** —
    with 11b up to 1.21.11; updates into 26.x have no shared ids, so there the router relies on
    rules and names errors from the step files by name only.
    Done 2026-10-25.
11c. Learn rules from Stonecutter mods: 33 open-source multi-version mods pinned in
    `coverage/stonecutter/mods.json`, 5,045 `//? if` switches extracted with file, method and the
    update their boundary falls on (`bench/stonecutter_mine.py`). Changes made the same way in two
    or more mods became 38 rules in `knowledge/fixes.json` (22 forward, 16 backports), each with its
    switches as examples and a test that self-test re-runs (`bench/stonecutter_rules.py`). **done**
    — ZombieApocalypseAddon 1.21.1 → 26.3 stops at 50 errors instead of 69; → 1.21.6 (9) and the
    untouched Notes 1.21 → 1.21.6 (14) are unchanged, their errors have no repeated switch. The map
    shows rules learned per update.
    Done 2026-10-25.
12. Prove one untouched mod from 1.20.1 (or older) to 1.21.6 to a build, then to a loaded client.
    **later**
    Starts 2026-12-01, target 2026-12-18.
13. Publish the family list each jump still needs, so a stop is a shopping list. **later**
    Starts 2026-12-14, target 2027-01-08.

## Phase 3 — 26.x, the unobfuscated game

14. Convert a source tree from yarn to the game's own names (works for one version already; needs a
    full-tree pass). **doing**
    Started 2026-10-24, target 2026-11-27.
15. Switch the build with the version: Loom 1.17, Gradle 9.5, Java 25, no mappings. **doing**
    Started 2026-10-24, target 2026-11-20.
16. Diff the old official names against the 26.3 jar to name what moved. **next**
    Starts 2026-11-20, target 2026-12-04.
17. Port one mod to 26.3 to a build, then to a loaded client. AppleSkin NeoForge 1.21.6 → 26.3
    reaches a packaged build with the mixin audit in 66s: build settings, NeoGradle, Java and 82 → 0
    compile errors are handled by rules except the removed debug-text event, one hand-written file.
    Loading it in a real 26.3 client is the next rung. **done** — AppleSkin NeoForge 1.21.6 → 26.3
    ports with no hand-written file and passes its HUD behaviour check in the real 26.3 client: the
    estimate for a golden carrot (+12.000002) matches real regeneration (+12.000001), and the same
    check fails when the estimate is broken on purpose. The debug-text event became a rule; a stale
    Parchment setting that compiled the 26.3 game wrong is now moved or removed by the port.
    Done 2026-11-08.

## Phase 4 — Loaders

18. Fabric → NeoForge on one small mod, measured end to end. **later**
    Starts 2027-01-04, target 2027-01-29.
19. Forge → NeoForge and Fabric → Quilt, which are close pairs. **later**
    Starts 2027-02-01, target 2027-02-26.
20. Publish which loader pairs are real, which are blocked, and why. **later**
    Starts 2027-02-22, target 2027-03-12.

## Phase 5 — Proof: behaviour in the real game and server

The second half of the goal, worked on alongside porting.

21. Linux and macOS game checks (or label them), no-Java install on each. **later** — Linux, part
    proven: on a fresh boat.dev Ubuntu machine with no GPU, the behaviour checks (a hidden client on
    a virtual display, software OpenGL) passed and failed on their breaks, and data-check passed
    (bench/session-cost/behaviour-boat-2026-11-07.txt, data-check-boat-2026-11-07.txt). macOS and
    the no-Java install are not checked.
    Starts 2026-12-14, target 2027-01-15.
22. A mod's own behaviour asserted in a real client: AppleSkin's HUD estimate, Zombie Apocalypse
    Addon's zombies hunting with `/zhunt` on its 1.21.4 port; each fails when the mod is broken on
    purpose. **done** — (local only; they start the game)
    Done 2026-10-26.
23. Optional pixel comparison for regressions, off by default. **later**
    Starts 2027-01-25, target 2027-02-12.
24. Paper plugins: `plugin-check` on MockBukkit (fast floor), then `server-check` on a real Paper
    server with a bot player following a scenario, secrets masked. AgentCraft passes, and each break
    made on purpose fails at its step. **done**
    Done 2026-10-26.
25. The bot joins any Paper version from 1.8 through ViaProxy, reading each world's real floor and
    height. **done** — (1.8.8, 1.12.2, 1.16.5, 1.20.1 and 1.21.4 passed locally; CI covers 26.3
    only)
    Done 2026-10-26.
26. One nightly CI job runs the real-game behaviour checks (AppleSkin's HUD estimate, Zombie
    Apocalypse's AI) and the bot on older Paper versions. **next** — bench/behaviour_checks.sh runs
    both behaviour checks (AppleSkin's HUD estimate, Zombie Apocalypse's AI) in a hidden, quiet
    client on a virtual display with software OpenGL: on a fresh boat.dev Linux machine with no GPU
    both passed and both breaks failed, 10 minutes. The nightly CI job is written as
    bench/behaviour-nightly.patch with its expected spend ($0 on this public repository, about 10 to
    15 runner-minutes a night) and waits for Jon to approve it.
    Starts 2026-11-09, target 2026-11-20.
27. The bot joins a modded server (NeoForge or Fabric dedicated server), so mods get scenario checks
    like plugins do. **next**
    Starts 2026-11-23, target 2026-12-11.
28. Port a Paper plugin between versions (SCOPE.md rung 5; config-only so far). **later**
    Starts 2027-01-04, target 2027-01-22.

## Phase 6 — Modpack compatibility

29. Reproduce Sismeel's Forge 1.20.1 pack problems: an item becomes a different block when placed,
    and a world fails to reopen. Identify the affected item and obtain or recreate the failing
    world; the supplied pack export has configurations and mod download references, but no world or
    logs. Determine each cause independently. **doing**
    - World that will not reopen: done. Traverse 7.0.12 bundles Biolith 1.0.1-alpha.1, which gives 7
      Traverse biomes climate values of 3.01; Blueprint saves the overworld's biome list in full,
      and the next start rejects it ("Overworld settings missing"). Seen on the pack's real Forge
      server and in its real client (the save gets the datapack-failure screen). Biolith
      1.0.1-beta.1 in mods/ fixes it: new worlds reopen, and a world saved by the broken version
      opens too.
    - Item that becomes another block: not reproduced. Every block item (14,129 in singleplayer),
      placed by a player's right-click on the pack's server and again in singleplayer with every
      client-only mod, land as themselves or as their own mod's deliberate variant (bamboo shoot,
      sapling stages, Craziness Awakened's random spawners); the 29 that change afterwards are
      vanilla rules (coral dying out of water) or the mod's own. No two mods override the same block
      look. 221 block names belong to blocks from two or more mods (e.g. "Azure Planks" in Outer End
      and Slugterra), the likeliest cause on the evidence; `pack-check` now lists them. Items are
      also placed on a wall, a ceiling and in water (ladders, hanging signs, spore blossoms, kelp).
      Sweet berries never place in this pack: Sizeable Foliage refuses it on purpose and plants its
      bush from "Sweet Berry Seeds" instead. The item's name from Sismeel is still needed.
    Started 2026-10-29, target 2026-11-20.
30. Detect the demonstrated failure with a code check that fails on the broken case and passes on
    the corrected and normal cases; verify the correction and affected behavior in a real game,
    including save and reload when relevant. Preserve the original pack and world. **doing** —
    `pack-check` saves every dimension the way level.dat stores it and reads it back, starts the
    same world a second time, and reads back every placed block; it fails on the shipped pack and
    passes with the repair. `PackCheckTest` covers the checks that need no game.
    Started 2026-10-30, target 2026-11-27.
31. Accept installed modpacks and exports as inputs, identify versions and loaders from evidence,
    and diagnose and repair proven incompatibilities with reversible changes and clear blockers.
    Expand across Minecraft versions and loaders with real pack cases. **doing** — CurseForge
    exports and CurseForge app instances, both run in the game with `--fix` (a fixed .zip copy; an
    instance changed only after the recheck passes, and `--undo` restores it exactly). Jars no
    CurseForge list describes (other launchers' folders, hand-added jars) get their sides from
    Modrinth by SHA-1; a client-only mod neither site knows, and Forge does not name, still stops
    the game checks and is listed. Forge only in the game; repairs come from
    `knowledge/pack-fixes.json` (one entry).
    Started 2026-10-30, target 2027-01-15.
38. pack-check records how long each stage takes and checks the shipped and the repaired pack side
    by side. **done** — pack-check times every stage (read, static checks, each server start) and
    starts the repaired pack's server beside the shipped one. On Prehistoric World 2 (368 mods) a
    full check with the repair proven takes 5.1 minutes instead of 12.7, with the same findings.
    Client-only mods a start finds are remembered, so later runs skip that extra start; a start that
    only reads the world stops as soon as its probe is done; Simple Voice Chat gets a free port so
    the two servers do not collide, and a start that still loses a port runs again alone; a server
    that stops before its probe is done is reported at once instead of after the 20-minute timeout;
    an export is unpacked once. Tried for the 4.0-minute target and not used: the lighter compiler
    (C1) cuts a 368-mod server start's CPU by a third (519 to 351 CPU-s) but made the start take 465
    s instead of 139 s; the item probe already uses 40 ms of every 50 ms server tick. The 4.0-minute
    target was dropped on Nov 7; the time stays as a fact.
    Done 2026-11-07.
32. Bring Random Things forward from its source as a Forge version-port case, and prove its behavior
    on the target version. Automatic Forge porting is not yet supported. **later** — its newest
    source is a partial 1.14.4 rewrite (36 files, 2019 MCP snapshot names); the full mod is 1.12.2.
    Starts 2027-01-18, target 2027-02-05.

## Phase 7 — Testing without Minecraft open

Every check runs with no game window, checks that need only the game's data skip launching it, and 8
run at once on one PC. Closed on 2026-11-07.

33. Measure RAM, CPU, start time and time per check for one client, one dedicated server and one
    Paper server, warm and cold, as the baseline the next steps are judged against. **done** —
    bench/session_cost.py measures a session from the operating system on Windows, Linux and macOS.
    A hidden Fabric 26.3 client peaks at 1 GB and spends about 100 CPU-s starting, 49 with C1 only,
    which hidden launches now use; 16 started side by side from one saved launch. Warm on a hidden
    game: a screen check takes 3.9 s, a world check 3.0 s, a scripted scenario 5.1 s. mp-check's own
    dedicated server starts in 11 to 19 s. AgentCraft's scenario on a real Paper server takes about
    51 s warm (130 CPU-s, 2.4 GB at the peak) and 316 s on a fresh clone, most of it Gradle reading
    and building the plugin for the first time. This PC is shared, so every point carries how busy
    it was.
    Done 2026-11-07.
34. ui-check, world-check, mp-check, eval and the behaviour checks run with no Minecraft window and
    no sound, with the same assertions and the same 1280x720 captures, and a check stops when a
    window asked to stay hidden is not. **done** — ui-check, world-check, eval and mp-check ran with
    the game hidden and quiet: no game window was on screen after any of 15 warm checks, nor at any
    half-second sample through a whole mp-check (its client and its own server), and each check kept
    its assertions and its 1280x720 framebuffer capture. The behaviour checks (AppleSkin's HUD
    estimate, Zombie Apocalypse's AI) ran hidden on a Linux machine with no screen at all. A check
    stops when a game asked to stay hidden reports its window library was not rewritten. The title
    screen no longer costs a fixed 4.5 s wait; it waits only while it fades in. The warm screen
    check (3.9 s) and scenario (5.1 s) are timed as the whole modaccel command; the 1.5 s and 2.9 s
    of October were the older Windows lane's own loop with a window, so they are not the same work.
    Done 2026-11-07.
35. A check that needs the game's data but not its picture runs the game's own code with no window
    and no world: data-check reads what the mod registered, each item as a real stack, and every
    error the game logs while it loads the data packs. It costs about 5 CPU-s per check once one
    loaded JVM answers many checks, instead of 49 for a hidden client. **done** — modaccel
    data-check works on Fabric 26.3: on the Prism Lab demo mod it lists the mod's items, blocks,
    loot table and recipe with no game launched, and a broken recipe fails it with the game's own
    error. The game stays loaded: a changed data file costs about 2 CPU-s in 2 s, copied from src
    without Gradle when Gradle would copy it as it is. A spare game waits behind it, started up to
    just before the mods' own code, and takes changed code for about 5 CPU-s in 4 s; a changed mixin
    or loader file loads a fresh game, 12 s. The build is read once and kept until a build file
    changes. A loaded game holds about 0.38 GB.
    Done 2026-11-07.
36. modaccel runs at least 8 game checks side by side on this PC, each with its own game folder,
    without running out of memory, and a waiting hidden client costs almost no CPU. **done** — 8
    projects (copies of the demo mod) each ran data-check at once: from no game all passed within
    26.6 s, about 21 CPU-s each and 4.5 GB at the peak; with their games loaded, all passed again
    within 6.3 s. The 8 loaded games hold 2.85 GB and their 8 spares 2.41 GB. A brand-new project's
    first check takes about 36 s, 21 of it Gradle reading the build and recording the launch. A
    hidden game nobody has asked anything for 2 s now draws 10 frames a second, woken at once by the
    next request: it uses 0.04 to 0.06 cores while waiting instead of 0.14 to 0.16, measured
    alternately on the same PC, with warm checks as fast as before. Tried and not used: Java's
    start-up cache for Paper (AOT) halves a start's CPU (48 to 24 CPU-s) but recording it adds about
    95 s to one start and its shutdown, and the self-updating class archive saves 2 to 5 s per start
    but adds about 40 s to the first, a slow first start this step exists to avoid. The faster-start
    targets set on Nov 6 (a hidden client on 25 CPU-s, a cold check under 10 s, a server start of 10
    s) were dropped on Nov 7: they do not move the goal, and their numbers stay as facts.
    Done 2026-11-07.

## Numbers

Every number with a target, from `coverage/measures.json`. The same table for a PR or report:
`python bench/measures.py table [ID ...] [--area AREA] [--step STEP]`.

### Porting

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| 1.21.x updates that work in the game, Fabric | — | 2 updates (Oct 24) | 22 updates by Jan 29, 2027, step 10 | Baseline |
| 1.21.x updates that work in the game, NeoForge | 3 updates (Oct 24) | 8 updates (Nov 8) | 22 updates by Jan 29, 2027, step 10 | 26% of the way |
| Real mods with a port that works in the game | — | 3 mods (Oct 24) | 6 mods by Dec 18, step 12 | Baseline |
| Errors left: AppleSkin, Fabric 1.20.1 → 1.21.6, warm | 62 errors (Oct 24) | 11 errors (Nov 7) | 0 errors by Nov 20, step 7 | 82% of the way |
| Errors left: Zombie Apocalypse, NeoForge 1.21.1 → 1.21.6, warm | 38 errors (Oct 24) | 9 errors (Oct 25) | 0 errors by Dec 4, step 10 | 76% of the way |
| Errors left: Notes, NeoForge 1.21 → 1.21.6, warm | — | 14 errors (Oct 25) | 0 errors by Dec 4, step 10 | Baseline |
| Errors left: Jade, NeoForge 1.21.1 → 1.21.6, warm | — | 98 errors (Oct 24) | 0 errors by Jan 15, 2027, step 12 | Baseline |
| Errors left: Zombie Apocalypse, NeoForge 1.21.1 → 26.3, warm | 69 errors (Oct 25) | 50 errors (Oct 25) | 0 errors by Jan 15, 2027, step 17 | 28% of the way |
| Hand-written files: AppleSkin, NeoForge 1.21.6 → 26.3 | 1 file (Oct 24) | 0 files (Nov 8) | 0 files by Nov 27, step 17 | Met |
| Port to a packaged build: Zombie Apocalypse 1.21.1 → 1.21.4, warm | 2.6 min (Oct 24) | 2.8 min (Oct 25) | 1.5 min by Dec 18, step 10 | 0% of the way |
| Port to a packaged build: AppleSkin 1.21.6 → 26.3, warm | — | 66 s (Oct 24) | 60 s by Nov 27, step 17 | Baseline |
| Port rules learned from Stonecutter mods | — | 38 rules (Oct 25) | 80 rules by Dec 31, step 13 | Baseline |

### Game checks

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| Cold game start + one check, Fabric 26.3 | — | 36.5 s (Oct 21) | — | Fact |
| Cold game start to a listening client, NeoForge 1.21.1 | — | 18.4 s (Oct 22) | — | Fact |
| Dedicated server start, Fabric 26.3, cold | 24.2 s (Oct 22) | 15 s (Nov 7) | — | Fact |
| Client start and first join to a dedicated server, cold | — | 27.1 s (Oct 22) | — | Fact |
| New registration on a fresh server, warm | — | 15 s (Oct 21) | — | Fact |
| Logic tests, no Minecraft, warm | — | 0.79 s (Oct 21) | 1 s (hold) by Nov 27, step 34 | Met |
| Screen change + screenshot, warm game, Fabric 26.3 | 1.5 s (Oct 21) | 3.9 s (Nov 7) | — | Fact |
| Screen edit swapped into the running game, NeoForge 1.21.1, warm | — | 1.5 s (Oct 22) | 1.5 s (hold) by Nov 27, step 34 | Met |
| Block entity in a live world, Fabric 26.3, warm | — | 2.1 s (Oct 21) | 2.5 s (hold) by Nov 27, step 34 | Met |
| Scripted scenario + screenshot, warm game, Fabric 26.3 | 2.9 s (Oct 21) | 5.1 s (Nov 7) | — | Fact |
| Multiplayer scenario, warm client and server | — | 4.1 s (Oct 22) | 4 s by Nov 27, step 34 | Baseline |
| Mods with their own behaviour asserted in a real client | 2 mods (Oct 26) | 2 mods (Nov 7) | 4 mods by Dec 4, step 26 | 0% of the way |
| Memory for one hidden game session, Fabric 26.3, warm | 1.1 GB (Nov 6) | 1.0 GB (Nov 6) | — | Fact |
| Game checks modaccel runs at once on one PC | — | 8 sessions (Nov 7) | 8 sessions by Jan 8, 2027, step 36 | Met |
| CPU to start one hidden client, saved launch, Fabric 26.3, warm | 95 CPU-s (Nov 6) | 49 CPU-s (Nov 6) | — | Fact |
| CPU for data-check on the Prism Lab demo mod, quiet 4-core Linux machine, Fabric 26.3, cold | 5.7 CPU-s (Nov 7) | 6.2 CPU-s (Nov 7) | — | Fact |
| CPU for the first data-check on a mod, the game's code started for it, Fabric 26.3, warm | 33 CPU-s (Nov 7) | 14.3 CPU-s (Nov 7) | — | Fact |
| CPU per data-check on a mod once its game is loaded, no game launched, Fabric 26.3, warm | 2.2 CPU-s (Nov 7) | 1.5 CPU-s (Nov 7) | 5 CPU-s by Dec 18, step 35 | Met |
| CPU per data-check after the mod's code changed, the spare game taking it, Fabric 26.3, warm | 6.2 CPU-s (Nov 7) | 4.9 CPU-s (Nov 7) | 5 CPU-s by Dec 18, step 35 | Met |
| Seconds for a data-check that loads a fresh game, Fabric 26.3, warm | 45.6 s (Nov 7) | 11.7 s (Nov 7) | — | Fact |
| Memory of data-check's loaded game, Fabric 26.3, warm | 0.5 GB (Nov 7) | 0.4 GB (Nov 7) | — | Fact |
| CPU a hidden client uses while it waits, warm | 0.21 cores (Nov 6) | 0.05 cores (Nov 7) | 0.05 cores by Jan 8, 2027, step 36 | Met |
| Hidden clients this PC held at once, each to a 1280x720 capture | — | 16 sessions (Nov 6) | — | Fact |
| One-file edit, modaccel fast, warm | — | 4 s (Oct 23) | 4 s (hold) by Nov 27, step 34 | Met |

### Plugins

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| server-check on a real Paper server, AgentCraft, warm | 25 s (Oct 26) | 51 s (Nov 7) | — | Fact |
| server-check on a real Paper server, AgentCraft, cold | 0.9 min (Oct 26) | 5.3 min (Nov 7) | — | Fact |
| CPU for server-check on a real Paper server, AgentCraft, warm | — | 130 CPU-s (Nov 7) | — | Fact |
| Memory at the peak of server-check on a real Paper server, AgentCraft, warm | — | 2.4 GB (Nov 7) | — | Fact |
| plugin-check on MockBukkit, AgentCraft, warm | — | 7 s (Oct 26) | 7 s (hold) by Nov 27, step 34 | Met |
| Paper versions the test player passed on | — | 6 versions (Oct 26) | 8 versions by Nov 20, step 26 | Baseline |
| Paper versions the test player passes on in CI | — | 1 version (Oct 26) | 6 versions by Nov 20, step 26 | Baseline |

### Modpacks

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| Modpacks checked in the game by pack-check | — | 1 pack (Oct 30) | 5 packs by Jan 15, 2027, step 31 | Baseline |
| Proven pack repairs pack-check knows | — | 1 repair (Oct 30) | 5 repairs by Jan 15, 2027, step 31 | Baseline |
| Block items placed and read back in one pack | — | 14,129 items (Oct 30) | — | Fact |
| pack-check on a 368-mod pack, shipped and repaired, warm | 12.7 min (Oct 30) | 5.1 min (Nov 7) | — | Fact |

### CI

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| PR checks, start to finish | 2.0 min (Oct 27) | 3.9 min (Nov 5) | 5.0 min (hold) by Nov 5 | Met |
| Nightly multi-version run, start to finish | 45.9 min (Oct 28) | 41.7 min (Nov 6) | 30.0 min by Dec 4, step 26 | 26% of the way |

### Roadmap

| Measure | First | Now | Target | Status |
|---|---|---|---|---|
| Roadmap steps done | 5 steps (Oct 24) | 21 steps (Nov 8) | 40 steps by Mar 12, 2027 | 46% of the way |

## Decisions

| Question | Choice | Date |
|---|---|---|
| What is the goal? | Any version to any version, any loader to any loader; today one hop inside one loader is verified | 2026-10-24 |
| Can modpack users get compatibility help? | Yes: diagnose and resolve installed-pack conflicts, with reversible changes and real behavior checks; `pack-check` covers Forge packs | 2026-10-30 |
| Are version and loader changes the same job? | No; separate records, separate claims | 2026-10-24 |
| Does a passing build prove a port? | No; loader-refused jars, invisible UI and unloaded mods all passed builds | 2026-10-24 |
| What counts as progress? | A row in `coverage/ledger.json` with a receipt, shown on the progress page | 2026-10-24 |
| What do we support, and how does a new kind of project get in? | `SCOPE.md`: platforms by side, a six-rung support ladder, intake through the `bench-new-mod` skill | 2026-10-25 |
| Plugin ↔ mod conversion? | Not for now; it's a rewrite, not a port, and hybrid servers already exist | 2026-10-25 |
| Ports between Paper versions? | Yes, as rung 5 for Paper once rungs 2–3 land | 2026-10-25 |
| Where do roadmap steps, targets and every number we show live? | `coverage/roadmap.json` (steps, dates, targets) and `coverage/measures.json` (every number, with its history and target); ROADMAP.md and the roadmap page are generated from them | 2026-11-06 |
| How cheap should a game check be? | 5 CPU-s per check, from 49. Checks that need the game's data but not a picture stop launching the game: data-check runs the game's own code with no window and no world (Jon, 2026-11-07, instead of sharing one started game). Picture checks keep the hidden client, with graphics settings as they are | 2026-11-07 |
| Do speed targets drive work? | Only when they move the goal. Phase 7 closed on Nov 7 once checks were hidden, data-check skipped the game and 8 checks ran at once; the start, cold-check, screen-check, server and pack-check speed targets became facts, the nightly job joined step 26 and pack-check's step joined Phase 6, and work went back to porting (Jon, 2026-11-07) | 2026-11-07 |
