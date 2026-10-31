# Roadmap

Goal: a mod or plugin author never launches Minecraft, and a modpack user need not diagnose mod
conflicts by hand. Port source from any version to any other (and, separately, any loader to any
other), resolve modpack incompatibilities, and prove behavior in the real game or a real server.

Status words: **done** (ran and checked), **doing** (current step), **next**, **later**.
Live proof of what runs: https://noisemaker111.github.io/modding-accelerator/ (bench page)
(numbers come from `coverage/ledger.json`, one row per port that was actually run).

## Phase 1 — Neighbouring versions (1.21.x), Fabric and NeoForge

1. Port a real Fabric mod one minor forward: F3Nope 1.21.5 → 1.21.6, build + mixin audit. **done**
2. Same mod backwards: untouched upstream 1.21.6 → 1.21.5, real client loads it. **done**
3. Prove a port's behaviour, not just its build. The first try (overlay inspected, AppleSkin
   inventory change 95.6%, save reloads at the same position) was re-marked to "loads" on
   2026-10-26: a frame change and a save reload happen without the mod. Behaviour needs an
   assertion on the mod's own state (`--assert` on its class, `--expect-value` on its screen). **done** —
   `tools/tests/AppleSkinHudCheck.java` checks AppleSkin's HUD estimate against real regeneration on
   the 1.21.6 port, and fails when the estimate is broken on purpose.
4. Second loader end to end: NeoForge ZombieApocalypseAddon 1.21.1 → 1.21.4 with no hand edits;
   its `/za` command runs in a 1.21.4 world. **done**
5. Dependency blockers become one-file answers: verified selection, disable with proof, and a
   reference that is not an `import` still counts (AppleSkin's JEI mixin). **done**
6. A port refuses to pass while it still targets the old game: packaged metadata is read back. **done**
7. A five-minor jump on a bigger mod: AppleSkin 1.20.1 → 1.21.6. 62 errors down to 51 after seven
   rules (Identifier, food components, debug HUD); the rest needs real code changes. **doing**
8. Turn the render-pipeline family into rules (draw texture / render pipeline / texture view), then
   re-run step 7 to a build. **next**
9. Build-toolchain bumps follow the target automatically (AppleSkin needed Loom 1.10 + Gradle 8.13). **next**
10. One command, no hand edits, for 1.21.1 → 1.21.6 on this mod, and the same for a second mod. **next**

## Phase 2 — Big jumps across families (1.16.5, 1.18.2, 1.20.1 → 1.21.x)

11a. Change index and hub-and-spoke families: every release from 1.14.4 to 26.3 diffed against the
    next in Mojang's names (`coverage/changes/`, `tools/ChangeIndex.java`), 24 families cut where an
    update breaks 1% or more of the game's names, each with a hub picked by Modrinth mod count, rule
    coverage and the best ledger run per step, shown at `docs/porting-map.html`. Releases before
    1.14.4 are listed as blocked (no official mappings). **done**
11b. Router: `port` chains hops X → family hub → hub → … → Y from `coverage/changes/index.json`, one
    record per hop; renames inside a family applied from the index. **done** — `port --from A --to B
    --fix` walks the hubs, joins every update's renames and moves into one map by intermediary ids
    (`VersionMap --chain`), runs only the rules whose jump covers an update it crosses, builds once,
    and names the update behind each error left. Releases before 1.14.4 are refused. ZombieApocalypseAddon
    1.21.1 → 1.21.4 (three families) builds and loads; → 1.21.6 stops at the same 9 errors as the
    one-jump path, all placed on 1.21.4 → 1.21.5; → 26.3 stops at 69 instead of 100+.
11. Chain mappings across more than one family in a single port, with one record per hop. **done**
    with 11b up to 1.21.11; updates into 26.x have no shared ids, so there the router relies on
    rules and names errors from the step files by name only.
11c. Learn rules from Stonecutter mods: 33 open-source multi-version mods pinned in
    `coverage/stonecutter/mods.json`, 5,045 `//? if` switches extracted with file, method and the
    update their boundary falls on (`bench/stonecutter_mine.py`). Changes made the same way in two or
    more mods became 38 rules in `knowledge/fixes.json` (22 forward, 16 backports), each with its
    switches as examples and a test that self-test re-runs (`bench/stonecutter_rules.py`). **done** —
    ZombieApocalypseAddon 1.21.1 → 26.3 stops at 50 errors instead of 69; → 1.21.6 (9) and the
    untouched Notes 1.21 → 1.21.6 (14) are unchanged, their errors have no repeated switch. The map
    shows rules learned per update.
12. Prove one untouched mod from 1.20.1 (or older) to 1.21.6 to a build, then to a loaded client. **later**
13. Publish the family list each jump still needs, so a stop is a shopping list. **later**

## Phase 3 — 26.x, the unobfuscated game

14. Convert a source tree from yarn to the game's own names (works for one version already; needs
    a full-tree pass). **doing**
15. Switch the build with the version: Loom 1.17, Gradle 9.5, Java 25, no mappings. **doing**
16. Diff the old official names against the 26.3 jar to name what moved. **next**
17. Port one mod to 26.3 to a build, then to a loaded client. AppleSkin NeoForge 1.21.6 → 26.3
    reaches a packaged build with the mixin audit in 66s: build settings, NeoGradle, Java and 82 → 0
    compile errors are handled by rules except the removed debug-text event, one hand-written file.
    Loading it in a real 26.3 client is the next rung. **doing**

## Phase 4 — Loaders

18. Fabric → NeoForge on one small mod, measured end to end. **later**
19. Forge → NeoForge and Fabric → Quilt, which are close pairs. **later**
20. Publish which loader pairs are real, which are blocked, and why. **later**

## Phase 5 — Proof: behaviour in the real game and server

The second half of the goal, worked on alongside porting.

21. Linux and macOS game checks (or label them), no-Java install on each. **later**
22. A mod's own behaviour asserted in a real client: AppleSkin's HUD estimate, Zombie Apocalypse
    Addon's zombies hunting with `/zhunt` on its 1.21.4 port; each fails when the mod is broken on
    purpose. **done** (local only; they start the game)
23. Optional pixel comparison for regressions, off by default. **later**
24. Paper plugins: `plugin-check` on MockBukkit (fast floor), then `server-check` on a real Paper
    server with a bot player following a scenario, secrets masked. AgentCraft passes, and each break
    made on purpose fails at its step. **done**
25. The bot joins any Paper version from 1.8 through ViaProxy, reading each world's real floor and
    height. **done** (1.8.8, 1.12.2, 1.16.5, 1.20.1 and 1.21.4 passed locally; CI covers 26.3 only)
26. A nightly CI job runs the bot on older Paper versions. **next**
27. The bot joins a modded server (NeoForge or Fabric dedicated server), so mods get scenario
    checks like plugins do. **next**
28. Port a Paper plugin between versions (SCOPE.md rung 5; config-only so far). **later**

## Phase 6 — Modpack compatibility

29. Reproduce Sismeel's Forge 1.20.1 pack problems: an item becomes a different block when placed,
    and a world fails to reopen. Identify the affected item and obtain or recreate the failing world;
    the supplied pack export has configurations and mod download references, but no world or logs.
    Determine each cause independently. **doing**
    - World that will not reopen: **done**. Traverse 7.0.12 bundles Biolith 1.0.1-alpha.1, which gives
      7 Traverse biomes climate values of 3.01; Blueprint saves the overworld's biome list in full, and
      the next start rejects it ("Overworld settings missing"). Seen on the pack's real Forge server and
      in its real client (the save gets the datapack-failure screen). Biolith 1.0.1-beta.1 in mods/ fixes
      it: new worlds reopen, and a world saved by the broken version opens too.
    - Item that becomes another block: not reproduced. Every block item (14,129 in singleplayer), placed by a player's
      right-click on the pack's server and again in singleplayer with every client-only mod, land as
      themselves or as their own mod's deliberate variant (bamboo shoot, sapling stages, Craziness
      Awakened's random spawners); the 29 that change afterwards are vanilla rules (coral dying out of
      water) or the mod's own. No two mods override the same block look. 221 block names belong
      to blocks from two or more mods (e.g. "Azure Planks" in Outer End and Slugterra), the likeliest
      cause on the evidence; `pack-check` now lists them. Items are also placed on a wall, a ceiling
      and in water (ladders, hanging signs, spore blossoms, kelp). Sweet berries never place in this pack:
      Sizeable Foliage refuses it on purpose and plants its bush from "Sweet Berry Seeds" instead.
      The item's name from Sismeel is still needed.
30. Detect the demonstrated failure with a code check that fails on the broken case and passes on
    the corrected and normal cases; verify the correction and affected behavior in a real game,
    including save and reload when relevant. Preserve the original pack and world. **doing**:
    `pack-check` saves every dimension the way level.dat stores it and reads it back, starts the same
    world a second time, and reads back every placed block; it fails on the shipped pack and passes with
    the repair. `PackCheckTest` covers the checks that need no game.
31. Accept installed modpacks and exports as inputs, identify versions and loaders from evidence,
    and diagnose and repair proven incompatibilities with reversible changes and clear blockers.
    Expand across Minecraft versions and loaders with real pack cases. **doing**: CurseForge exports and
    CurseForge app instances, both run in the game with `--fix` (a fixed .zip copy; an instance changed
    only after the recheck passes, and `--undo` restores it exactly). Jars no CurseForge list describes
    (other launchers' folders, hand-added jars) get their sides from Modrinth by SHA-1; a client-only mod
    neither site knows, and Forge does not name, still stops the game checks and is listed. Forge only in the game;
    repairs come from `knowledge/pack-fixes.json` (one entry).
32. Bring Random Things forward from its source as a Forge version-port case, and prove its
    behavior on the target version. Automatic Forge porting is not yet supported. **later**: its newest
    source is a partial 1.14.4 rewrite (36 files, 2019 MCP snapshot names); the full mod is 1.12.2.

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
