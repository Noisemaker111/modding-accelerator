# Roadmap

Goal: any Minecraft version to any other, and any loader to any other, from a Gradle source
checkout, with checks that prove it works. Version changes and loader changes are separate jobs.

Status words: **done** (ran and checked), **doing** (current step), **next**, **later**.
Live proof of what runs: https://noisemaker111.github.io/modding-accelerator/progress.html
(numbers come from `coverage/ledger.json`, one row per port that was actually run).

## Phase 1 — Neighbouring versions (1.21.x), Fabric and NeoForge

1. Port a real Fabric mod one minor forward: F3Nope 1.21.5 → 1.21.6, build + mixin audit. **done**
2. Same mod backwards: untouched upstream 1.21.6 → 1.21.5, real client loads it. **done**
3. Prove a port's behaviour, not just its build: overlay inspected, AppleSkin inventory change
   95.6%, save reloads at the same position. **done**
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

11. Chain mappings across more than one family in a single port, with one record per hop. **later**
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

## Phase 5 — Proof and platforms

21. Linux and macOS game checks (or label them), no-Java install on each. **later**
22. Behaviour assertions beyond UI: items, entities, multiplayer. **later**
23. Optional pixel comparison for regressions, off by default. **later**

## Decisions

| Question | Choice | Date |
|---|---|---|
| What is the goal? | Any version to any version, any loader to any loader; today one hop inside one loader is verified | 2026-10-24 |
| Are version and loader changes the same job? | No; separate records, separate claims | 2026-10-24 |
| Does a passing build prove a port? | No; loader-refused jars, invisible UI and unloaded mods all passed builds | 2026-10-24 |
| What counts as progress? | A row in `coverage/ledger.json` with a receipt, shown on the progress page | 2026-10-24 |
