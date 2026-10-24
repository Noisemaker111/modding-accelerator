# Real Minecraft bump cost, measured on 6 open-source mods

Machine: Ryzen 9 3900X, 64 GB RAM, Windows 11, **JDK 21**. JDK 25 was tried first and every mod's Gradle 8.11–8.14 wrapper refused it (`Unsupported class file major version 69`), so the run used the machine's default JDK 21.
Method: `git clone --depth 1` at a 1.21.x branch → `gradlew build -x test` (baseline) → edit only version pins → rebuild → count javac errors. Each clone keeps the mod's own mappings family (yarn, or Mojang official for Artifacts).
Seconds are wall clock and include Gradle startup plus dependency/MC setup. Baselines were cold (Gradle distribution + full dependency graph); bump builds reused warm caches but still downloaded and remapped the new Minecraft jar.

| Mod (license) | Step | Baseline | Bumped | Compile errors | What actually broke |
|---|---|---|---|---|---|
| Trinkets (MIT) | 1.21.5 → 1.21.6 | 321.1 s PASS | 156.1 s FAIL | 21 | 17 GUI transform (`getMatrices()` → `Matrix3x2fStack`, `push/pop` → `pushMatrix/popMatrix`), 4 `ItemStack.fromNbt/toNbt` removals |
| Traverse (LGPL-3.0) | 1.21.5 → 1.21.6 | 328.9 s PASS | 115.0 s FAIL | 68 | one Fabric API rename: `getOrCreateTagBuilder(...)` removed, 4 tag-provider files |
| Terrestria (LGPL-3.0) | 1.21.5 → 1.21.6 | 141.8 s PASS | 89.4 s FAIL | 99 | same Fabric tag-provider rename (97), `FabricTagBuilder` gone (1), one renamed vanilla biome tag (1) |
| Traveler's Backpack (GPL-3.0) | 1.21.4 → 1.21.5 | 335.1 s PASS | 161.0 s FAIL | 6 (+1 mixin warning) | 3 moved vanilla symbols (`CoreShaders`, `BakedModel`, `ThrownPotion`); 1 dead `@Shadow` target |
| Polymer (LGPL-3.0) | 1.21.5 → 1.21.6 | 474.4 s PASS | 186.1 s FAIL | 8 | `PlayerEntity(World,GameProfile)` ctor, `getProtocolVersion()` removed, new `writeCustomData(WriteView)` |
| Artifacts (MIT) | 1.21.1 → 1.21.4 | 567.5 s PASS (fabric+neoforge) | 167.4 s FAIL (`:fabric:build`) | 97 | entity render-state refactor: 68 model/renderer override+generic breaks, 22 moved/removed classes, 7 type mismatches |

Attempts that never reached the compiler (recorded in `results.json`): Traveler's Backpack 3 (missing `accessories …+1.21.5` artifact, "Mod was built with a newer version of Loom", Loom 1.10 demanding Gradle 8.12), Artifacts 2 (NeoForge 21.1.211 rejected for MC 1.21.4 even for the Fabric task), Traverse 2 (disk exhaustion, not tooling).

## What dominated the cost

1. **What dominates is API-shape churn, not the version number.** The 1.21.5 → 1.21.6 step cost 68–99 errors in the worldgen mods from a single Fabric API rename, and 17 of Trinkets' 21 from one GUI matrix change; meanwhile the raw error counts say almost nothing about effort (99 errors = 3 distinct symbols in 2 files).
2. **The larger jump is a different kind of work.** Artifacts' 1.21.1 → 1.21.4 crosses the entity render-state refactor, so 68 of 97 errors are inherited `renderToBuffer`/`setupAnim`/generic-bound breaks that need real rewrites, not renames. Bumping 1.21.1 → 1.21.2 (and then 1.21.4) would be cheaper than one big step.
3. **Build-system cost was mostly third-party, not Loom.** Loom 1.10.5 + Gradle 8.12/8.13 handled 1.21.6 fine in Trinkets, Traverse, Terrestria and Polymer; the only genuine tooling cascade was Traveler's Backpack, where a dependency jar built with Loom 1.10.1 forced Loom 1.9 → 1.10 and Gradle 8.11.1 → 8.14. Artifacts needed its NeoForge pin bumped just to configure the Fabric task.
4. **Dependency pins fail before code does.** `accessories 1.2.9-beta+1.21.5` simply does not exist; a stale NeoForge pin aborts configuration. A bump tool must resolve the target-version dependency set before any compile error is meaningful.
5. **Silent breaks ride along with hard errors.** Traveler's Backpack also produced `Cannot find target for @Shadow field` warnings — a green build would ship a dead mixin. Error counting alone under-reports damage.

## What the knowledge base already answers

`knowledge/version-changes.json` covers the 1.21.6 `CompoundTag` → `ValueInput`/`ValueOutput` save/load changes (observed in Polymer's `writeCustomData(WriteView)` and Trinkets' `ItemStack` NBT helpers) and the 1.21.5 block-removal split. It has **no entry** for the five symbols that actually dominated these builds: the Fabric `getOrCreateTagBuilder` removal, the `DrawContext`/`Matrix3x2fStack` GUI change, `CoreShaders`/`BakedModel`/`ThrownPotion` moves, `PlayerEntity(World, GameProfile)`, and `getMinBuildHeight` → `getMinY`. It also jumps 1.21.1 → 1.21.11, so the intermediate 1.21.2 render-state refactor that cost Artifacts most is absent.

## Unresolved

- Artifacts was measured with `:fabric:build` against a full-build baseline; its second figure is not like-for-like.
- All six bumps stop at the first compiler failure — nobody has pushed a bump to a green build, so repair time is still unmeasured.
- Wall times come from builds running 1–4 at a time on one machine; treat ±20 % as noise and the error counts as the solid signal.
- The optional `tools\SymbolDiff.java` cross-check was not run; no per-symbol diff against the 1.21.6 jar exists yet, and build outputs were deleted after logging (rebuild takes 1–5 min per mod).
