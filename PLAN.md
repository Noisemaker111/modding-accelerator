# Plan: make Modding Accelerator a measured, honest answer to "did this change work"

What is still missing, in the order a stranger hits it, is in `ROADMAP.md`.

## The goal

Jon's goal, clarified 2026-10-23 and sharpened 2026-10-24: any Minecraft version to any other,
and any loader to any other. Anyone can point the tool at their mod, name the target, and get a
port plus trustworthy logic, world, UI and multiplayer checks with as little waiting and setup
as possible. This applies across Windows, Linux and macOS, shells, loaders and version families,
including backports. Version portability and a change of loader are separate capabilities and
must be reported separately.

First use matters as much as repeat use. Record download/setup, build/repair, game startup
and verification separately; an existing dependency cache is not an empty-machine result.
Keep the original fast-loop objective and early toolchain diagnosis (JDK, Gradle, plugins,
repositories), but measure before setting universal timing promises.

Done means a stranger can install it on an unfamiliar real mod without hand-written wiring,
get a reversible port, and see truthful results tied to the exact tested build. Unknown
semantic changes and missing target dependencies must be explicit; compiling alone cannot
mean the port works. Source availability and dependencies constrain what can be ported.

## Beta closed (2026-10-23/24)

Frozen package JAR SHA-256 `e80f44d3338ed27389744dfe1698437c50ba0c6cef095c6b7554e230933d3137`,
ZIP `1a40176118bb9cc32122b00ab674aab99b0aa34ea046eb4314264459ca1c4532`. Extracted Windows
and Linux acceptance passed (24 core outcomes plus archive, dependency and manifest gates);
Linux log `.runs/beta-install/linux-acceptance-beta-final.log`. Nothing was published.

The final automatically repaired F3Nope port is now game-verified, closing the last beta
item. Original-source undo/port took 14.83s with setup cached (receipt
`.runs/beta-trials/f3nope-semantic-port-final.json`, ported JAR SHA-256 `88037b76...bcee39d`,
original runtime config byte-exact). Through the extracted package at
`.runs/beta-install/portable package with spaces/modaccel.cmd`:
`world-check --world "beta visual"` opened the real save in 27.0s and verified its native
save path (`.runs/beta-trials/f3-final-world-check.json`), then `ui-check --key 292
--expect-world` pressed F3 and captured in 0.74s
(`.runs/beta-trials/f3-final-ui-check.json`, `debugInput.shown=true`).
Capture `2026-10-24T01-56-19.096110500Z-bdd33903.png` was inspected: all three F3Nope
overlay lines render opaque white over the world, so the RGB-opacity repair is visually
confirmed. Source `self-test` passed 24 portable plus JDK, dependency and manifest checks.
Both runs used cached metadata and existing downloads; these are not first-use times.

Remaining beta limits that items 2-7 own: unidentified/missing target dependencies
(Trinkets REI, Traverse Terraform), other loaders and large version jumps, macOS and Linux
rendering, zero-Java install, portable hot reload, and mod-specific behavior assertions
beyond screen/world presence.


Local beta progress (2026-10-23): the distributable ZIP now contains the standalone JAR,
Windows/POSIX launchers, checksums, license and getting-started instructions. The extracted
package passed actual Windows and Linux launch checks from paths with spaces, source-input
guidance, missing-Java/legacy-command messages, 24 core outcomes and JDK/archive/dependency/
manifest gates. Final JAR SHA-256 starts `e80f44d3338e`; the three-OS CI workflow now checks
the actual extracted ZIP rather than copying two files. macOS and Linux rendering remain
unverified; Java must already be installed. Nothing was published.

Two unfamiliar untouched upstream Fabric mods now port via the extracted package without
hand edits: F3Nope `d65a225` 1.21.5 -> 1.21.6 took 101.7s initially, then 19.5s after undo and
repeat with setup cached; NoDeathAnimations `893f57f` took 88.0s initially and 13.4s cached.
Initial runs used fresh project caches and existing machine downloads. Both produce packaged
JARs and pass mixin audits. F3Nope's original manifest incorrectly declared Minecraft~1.20.1:
two actual client launches rejected it despite a passing build. Porting now retargets owned
simple literal manifest requirements, preserves processed placeholders, refuses ambiguous
constraints, and includes every target edit in guarded byte-exact undo. A Fabric error popup
previously hid the exit until the 181.4s startup timeout; the same rejection now reports in
12.5s with the loader's error-GUI flag. Actual game rendering remains enabled.

The actual F3 world check exposed an additional semantic break despite a passing build:
RGB-only text colors became transparent in 1.21.6. RGB -> opaque ARGB -> restored RGB
repeatedly changed whether all three overlay lines appeared, with F3 shown=true throughout.
Cached bytecode confirms the old TextRenderer 0xFC000000 implicit-alpha fallback and new
DrawContext alpha-zero rejection. The guarded mapper now preserves that old color behavior
for proven DrawContext text calls on this exact version hop; unsafe expressions stay untouched.
The source gate fails against the prior mapper and passes with the real F3Nope map/source.
A separate saved-launch command exceeded Windows process limits (34,517 characters) twice;
a dedicated classpath argument file fixes actual saved Minecraft replay. Its regression
child-launch gate fails with the previous direct command and passes after packing, preserving
existing Loom argument files. Trial receipts are under .runs/beta-trials/ and .runs/beta-install/.

## NeoForge end to end, Stonecutter nodes, and the limit of a big jump (2026-10-24)

Verified on a real NeoForge mod: `Riqqqque/ZombieApocalypseAddon` (71 Java files,
NeoGradle 7.1.38, 1.21.1 / NeoForge 21.1.248) ported to 1.21.4 / NeoForge 21.4.161.
The tool resolved NeoForge 21.4.161, rewrote `minecraft_version`, `minecraft_version_range`
(`[1.21.4,1.21.4]`) and `neo_version_range` (`[21.4.161,)`), renamed the `data` run type to
`clientData` (NeoForge 1.21.4+ dropped it), excluded the repo's nested 1.20.1 Gradle builds
and guarded NeoGradle 7.1's task-output providers so model discovery works at all.
`port` finished build + mixin audit in 73s on a warm project and 156s on a fresh copy.

Two findings mattered more than the timings. First, a passing build is not a loaded mod:
the mod metadata still declared `minecraft_version_range=[1.21.1]`, so NeoForge refused the
mod at runtime and the client showed "0 mods" although the jar was correct. The port now
rewrites the declared ranges with the pins, and the 1.21.4 client then listed the mod
("3 mods", Zombie Apocalypse Addon 2.8.0, shot
`.runs/beta-trials/zombie-apoc/ZombieApocalypseAddon-main/build/modaccel/shots/2026-10-24T06-49-17.549621Z-b52bd394.png`).
Second, a fresh copy still stops at 19 compiler errors, and they are all real 1.21.2+
API changes: `Equipable` became the `DataComponents.EQUIPPABLE` component, `Registry.get`
returns `Optional<Reference<T>>`, `EntityType.create` needs an `EntitySpawnReason`,
`getMinBuildHeight`/`getMaxBuildHeight` became `getMinY`/`getMaxY`, game rules moved to
`ServerLevel`, several `SoundEvents` fields became holders, `Mob.convertTo` takes
`ConversionParams`, `spawnAtLocation` takes a `ServerLevel`, `getLootTable` returns an
`Optional`, and `NearestAttackableTargetGoal` takes a `TargetingConditions.Selector`
lambda. Those were migrated by hand in this trial and are now rules: a fresh copy ports with
no manual edits (19 reported errors repaired, 75s including the cold build, receipt
`.runs/beta-trials/zombie-1214d-port.json`). The 331s this hop took earlier was not
NeoGradle setup: the port's forced-recompile flag was applied to every `JavaCompile` task,
including NeoGradle's `neoFormRecompile`, so each repair round recompiled Minecraft.
Restricting the flag to the project's own compilers took the fresh port from 331s to 75s
(repair rounds 4.5s and 5.6s; a repeated port is 13.6s). The port also reads the
packaged `neoforge.mods.toml` back out of the built jar and refuses a declared Minecraft
range that does not accept the target, which is exactly what made the first 1.21.4 client
show "0 mods" (`.runs/beta-trials/zombie-metadata-check.json`). The ported mod's own `/za`
dashboard then rendered inside a generated 1.21.4 world (waves, caps, events, difficulty,
world pressure; shot `.../shots/2026-10-24T07-25-46.639496800Z-404d2a2a.png`), which proves
the command, config load and message path; whether the waves actually spawn is not verified.
The same mod five minors forward (1.21.1 -> 1.21.6) reaches the target and stops on 38
errors in six storage/world families (NBT UUID and `SavedData` API, `Level.isDay`,
`Entity.moveTo` -> `snapTo`, `isInWaterRainOrBubble`, `SavedData.Factory`, sound holders) —
the 1.21.6 GUI rewrite that blocks Jade is irrelevant to a server-side mod.

Dependency disable used to look only at imports. AppleSkin's JEI mixin names its target as a
fully qualified `@Mixin(mezz.jei...)` reference, so the disable refused with "found no importing
sources". It now also matches a package reference anywhere in the file; on a fresh AppleSkin 1.20.1
copy the same selection resolves and lists `java/squeek/appleskin/mixin/JEIRenderHelperMixin.java`
as the removed source (`.runs/appleskin-disable-test.json`).

Jade 1.21.1 -> 1.21.6 (five minors, including the GUI rewrite) reaches the real target and
then stops honestly: NeoGradle 7.0.192 plus the run-type rename builds against NeoForge
21.6, and 98 errors remain in the 1.21.6 rendering rework (fine GUI matrix stack instead of
`PoseStack`, `RenderPipeline` arguments on `blitSprite`/`blitTiledSprite`, and removed
`Equipable`, `PinkPetalsBlock`, `Saddleable`, `BufferUploader`). That is the next semantic
family; no silent fallback builds the old game.

Diorama (Java sources, Kotlin Gradle scripts, Stonecutter, 22 nodes, one shared source tree) is now
importable: `setup` lists every node, `compile --project :26.3` succeeds (13.9s),
`port --project :1.21.1 --to 1.21.4` rewrites that node's `minecraft_version` and
`fabric_api_version` and builds it, then stops on 45 real 1.21.4 API errors. Its repairs
must land in the shared preprocessor-annotated `src/`, not the generated per-node copies;
that mapping is the remaining Stonecutter gap. The node-owner ambiguity (a `1.21.1` and a
`1.21.1-neoforge` node both pinning 1.21.1) now resolves to the selected node's file.

The same NeoForge mod was also sent five minors forward, 1.21.1 -> 1.21.6. The port rewrote
the pins, run type and ranges and the rule families took the reported errors from 38 to 9:
NBT `contains(String, int)`, UUID reads and writes through `UUIDUtil.CODEC`, `isDay`/`isNight`
-> `isBrightOutside`/`isDarkOutside`, `moveTo` -> `snapTo`, `isInWaterRainOrBubble` ->
`isInWaterOrRain`, and the 114 `SoundEvents` constants that became holders now get `.value()`
from a list read out of the target class. What remains is semantic rather than a rename:
1.21.6 replaced `SavedData.Factory` with `SavedDataType` plus a `Codec` per data class (four
errors in the two storage classes), and moved owner identity to `OwnableEntity`
(`getOwnerReference`), horse saddling off `AbstractHorse.equipSaddle`, and dropped
`isInWaterOrBubble` (five more). The 1.21.6 GUI rewrite that blocks Jade never touches this
server-side mod.

## NeoForge target setup and the speed floor (2026-10-24)

NeoForge projects now resolve their own loader version for the chosen Minecraft version:
`neo_version`/`neoforge_version` is rewritten from `maven.neoforged.net` by Minecraft minor
(1.21.6 -> 21.6.x, 1.20.1 -> 47.x), third-party mods are identified on Modrinth with the
`neoforge` loader, and the port stops unless the evaluated `net.neoforged:neoforge`
dependency starts with the expected prefix. The loader is now decided by the evaluated
Gradle plugin first, because Jade's dependency list contains fabric, forge and neoforge
groups while only NeoGradle is applied.

Jade `1.21-neoforge` (Snownee, 1.21.1 / NeoForge 21.1.21, JEI optional): pins rewritten in
29.7s, then the evaluated build failed inside Jade's own script — NeoGradle 7.0.145 reports
"Could not find run type data" for NeoForge 21.6 — so it stops with that named build-config
blocker instead of porting. The first attempt, made before `neo_version` was in the model
pins, reported a 238s success and produced `Jade-1.21.6-NeoForge-15.10.6.jar` while the
build still used NeoForge 21.1.21. That was a false green; the new evaluated-artifact check
rejects it, and the trial was undone. A real NeoForge source port therefore still needs a
project whose NeoGradle version already supports the target NeoForge.

Speed, measured on F3Nope 1.21.5 -> 1.21.6: with every cache valid a complete port
(source edits, packaged build, mixin audit) is **4.6s**. First visit to a hop is ~39s warm:
planning model 14.8s, full model 6.3s, build 5.7s, corpus 2.8s, tools 1.5s; a cold Gradle
daemon adds about 10s, and a first NeoForge build adds minutes of download/decompile. Corpus
learning is now deferred to the first failed build, and every receipt records
`stageSeconds` and `unaccountedSeconds` (0.14s on the 4.6s run) so future speed work starts
from a measured gap, not a guess.

## Backport of an untouched upstream snapshot (2026-10-24)

A second F3Nope snapshot, the newer upstream 1.1.0 tree that already targets 1.21.6
(`.runs/beta-trials/f3nope-upstream`), was copied untouched to
`.runs/beta-trials/f3nope-backport` and ported the other way: `port --from 1.21.6 --to
1.21.5 --fix` took 59.7s and produced a build plus mixin audit with no manual edits. The
actual 1.21.5 client then started from that source in 30.0s, loaded `f3nope 1.1.0` (the
FabricLoader mod list and its initializer appear in the game log) and rendered the title
screen, which was inspected. This is a reverse-direction build and load result on a
held-out snapshot; it does not exercise the mod's overlay in-game on 1.21.5.

## Installation without Java on Windows (2026-10-24)

`modaccel.cmd` no longer stops when `JAVA_HOME` and `PATH` have no Java. It downloads an
Adoptium Java 21 runtime once into `%LOCALAPPDATA%\modaccel\<jre|jdk>`, checks the published
SHA-256 with .NET (not a PowerShell cmdlet, which failed under a stripped PATH), extracts it,
and reuses it. A packaged `modaccel.jar` gets the 47 MB JRE; a source checkout with no jar
gets the 196 MB JDK. Measured with JAVA_HOME removed and PATH reduced to System32 and
Windows PowerShell, on the extracted package: first `help` took 39.2s including the download,
the repeat took 0.2s, and the JDK path downloaded 196 MB in 141s with `javac` present.
`MODACCEL_NO_DOWNLOAD=1` restores the old "install Java" message, which the package
acceptance test uses. The source-checkout launcher's choice of a JDK could not be exercised
end to end because this machine's `.tools` JDK cannot be renamed while in use; the JDK
download itself was verified directly. macOS and Linux still require Java.

## A reusable visual check (2026-10-24)

`ui-check` and `world-check` accept `--expect-change PERCENT`. The tool captures the frame
just before the input, runs the key/click/screen action, captures again and fails unless
enough pixels changed; the receipt records the percentage, the changed region, both
captures and `behaviorVerified=true`. This is the gate for "the code ran and drew nothing":
F3Nope 1.21.6 with the opacity repair changed 3.699% of the frame (baseline before F3,
final with the overlay); the same source with the repair removed changed only 0.110% and
failed a 0.5% requirement, while the build and mixin audit passed in both states. The
broken capture was inspected and shows the world with no overlay text. AppleSkin 1.21.5
reopened its world and the same gate measured a 95.578% change from pressing E (inventory),
in 2.38s. Receipts: `.runs/beta-trials/f3-visual-change*.json`,
`.runs/appleskin-visual-change.json`. The whole release path was then repeated through the
extracted package in `.runs/item7-acceptance-final`: port 39.3s to the byte-identical
game-verified JAR, `world-check` 27.9s on the `beta visual` save, and the visual gate at
1.055% against a 0.5% requirement with the three overlay lines inspected
(`.runs/beta-trials/f3-package-{port,world,visual}.json`). A never-started client also opens
an accessibility onboarding screen; the tool now presses Continue before opening a save
instead of timing out. This is one generic check, not full coverage of items, entities,
saves and multiplayer behavior; those still need mod-supplied assertions.

Save round-trip (same date): `world-check --save-roundtrip` records the player position,
presses Escape, clicks "Save and Quit to Title", waits for the title, reopens the same save
and requires the position to return within 0.05 blocks; both states and `behaviorVerified`
are in the receipt. F3Nope 1.21.6 took 7.2s and returned (9.5, 101.0, -2.5) in
`minecraft:overworld`; the packaged launcher repeated it on AppleSkin 1.21.5 in 54.9s
including a cold client start (`.runs/appleskin-save-roundtrip.json`). The rejection path is
unit-tested; no real lost-save fault was injected.

## Dependency selection and bounded repair (2026-10-24)

Automatic identity remains the first path. When it cannot finish, the port now stops before
editing and returns every unresolved artifact with the exact selection entry to add.
`dependencies --to VERSION` produces the same report without touching the project, and the
packaged launcher runs it. `.modaccel/dependencies.json` (or `--selection FILE`) accepts
either a `target` coordinate with `modrinth` (published release lists the chosen game
version) or `repository` plus the artifact's own Fabric manifest constraint, or
`disable: true` with a reason and the published project. A disable is refused when that
project supports the target; when allowed it comments out the one active declaration,
moves aside importing sources, removes matching manifest entrypoints, and returns all of it
on undo. Selection evidence, source hashes and removed files are written into the port
receipt.

Measured on the untouched Trinkets 1.21.5 trial (`.runs/beta-trials/trinkets`, extracted
package at `.runs/item2-acceptance/portable package with spaces/modaccel.cmd`):
`dependencies --to 1.21.6` reported ready with six resolved dependencies in 3.7s (metadata
cached) — CCA base/entity 6.3.0 -> 7.0.0-beta.1 and Cloth Config 17.0.144 -> 19.0.147
automatically, REI API and default-plugin 18.0.796 -> 20.0.811 by verified selection
(Architectury Maven POM plus the published REI 1.21.6 release), and EMI 1.1.16+1.21.1
disabled after its published project showed no 1.21.6 release. The full port (21.5s with
caches) then applied all six, repaired a dead mixin target reported by the annotation
processor and the 1.21.6 draw-context matrix change, and stopped with `stoppedSafely` on
seven errors in `LivingEntityTrinketComponent.java`: CCA 7 `writeData(WriteView)` plus the
removed `ItemStack.fromNbt`/`toNbt`. That is a named semantic migration, not a dependency
failure. Trinkets is NOT a completed port. Every attempt was undone byte-exact between runs.

The remaining semantic work is that component migration; item 3 of the README list owns
broader coverage. Traverse still identifies Terraform's canonical GitHub homepage but no
uniquely matching published Fabric 1.21.6 project, so it remains a verified blocker until a
selection or a published target exists. Trials and receipts are under `.runs/beta-trials/`.
`support` and README classify available, conditional, guided and unfinished capabilities.

Earlier evidence (2026-10-23): portable Java orchestration and a standalone JAR now exist;
source launchers ran in PowerShell and Git Bash. Evaluated Gradle discovery covered Fabric,
NeoForge and Diorama's Kotlin/Stonecutter `:26.3` module without build edits. Eight real pure
tests pass; wrong expectations, compile faults and zero pure tests each failed twice before
restoring green. Core outcome checks cover content invalidation, exact undo and conflicts.
The generic agent captured real 1280×720 frames on Fabric 1.21.6/26.3 and NeoForge 1.21.1;
actual Options clicks and saved NeoForge launch replay passed. Stale-client faults exposed
custom JavaCompile sources missing from the fingerprint; those inputs are now included and
the fault fails twice. Fabric 26.3 and NeoForge 1.21.1 also passed actual button/getter
checks (5 to 6); deliberately expecting 7 failed twice. Seventeen portable outcome checks
and 23 mapper checks passed. The standalone package ran its own checks and eight real
logic tests from a folder containing spaces, without using checkout resources.

An untouched AppleSkin 1.21.5 fixture selected published 1.21.6 pins, excluded its own
repository from learning, and compiled/mixin-audited in 39.2s with caches. Its first client
check exposed outdated Mod Menu and REI dependencies that compilation had not rejected.
Dependency discovery now uses evaluated artifacts, exact hashes or a unique matching
source repository with published loader/version support, plus the project's Maven releases.
It updates existing property owners and inline dependency literals, including compile-only
mods. The complete automatic dependency/source repair took 50.3s with downloads cached;
the actual 1.21.6 client rendered its title and opened Mod Menu through a real click.
This verifies startup and those screens, not AppleSkin's hunger rendering inside a world.
Its first setup took 164.8s for source discovery and 92.3s for target discovery, separately
from repair; this is not an empty-machine result. Receipts live in `.runs/portable/`, and
the isolated fixture is `.modaccel/acceptance/appleskin-original`.

The next three workers used GPT-6 Sol at low reasoning. Missing JDKs now download from
Adoptium with SHA-256 verification and safe extraction. With Gradle auto-detection and
auto-download disabled, a disposable project ran Gradle 9.7.1 on Java 25 and compiled with
the downloaded exact Java 8 toolchain (class version 52), in 8.8s. Exact toolchain versions
are distinct from minimum Java requirements; Gradle receives downloaded JDK search paths.

Held-out AppleSkin 1.21.6 Java sources backported automatically to the prepared 1.21.5
dependency set: 12 unique compiler errors to zero in one repair round, then 8 mixins and
21 members checked with zero dead targets, in 8.25s with metadata/dependencies cached.
The initial dependency/setup attempt took 201.7s, including 180.7s of metadata work; the
cached repair time is not first-use speed. The typed inverse GUI API rules reject other
declaring types, missing target API evidence and escaping matrix values. The 1.21.9
debug-hook migration still needs guidance; a scoped structural recipe records it.

Portable Fabric 26.3 `world-check` loaded the existing `lab` save and `mp-check` started
the evaluated dedicated server, joined with an actual client, and stopped the owned
server. Both 1280x720 framebuffer captures were inspected. Mere joining records
behaviorVerified=false. Game-thread assertion transport rejected a missing method twice;
that transport check does not establish mod-specific behavior.
The final standalone JAR repeated owned multiplayer in 66.5s including missing Java 25
setup (5.5s), metadata (10.7s), client and server startup and cleanup. It reads each
server task's evaluated working directory, not a guessed run folder. A stopped-client
saved-launch world check took 23.0s; its repeat verified an actual screen click from
5 to 6 in 2.37s. Native save paths identify the requested world. Source bootstrap and
packaged checks pass, including the standalone launcher in a folder containing spaces.
The backported AppleSkin 3.0.6+mc1.21.5 loaded in the actual Fabric 1.21.5 client and
rendered its inspected title screen. That first run proved startup/rendering. Subsequent Yarn 1.21.5 checks opened the
actual `New World` save by name (including its space), then inspected native 1280×720
frames of AppleSkin saturation, the F3 hunger/saturation/exhaustion values, and the
apple tooltip food icons. Generic game-thread coordinate clicks, cursor movement,
keys and text input are available through the portable command. These visual checks
retain behaviorVerified=false unless a formal assertion is supplied. Eight real pure logic tests also passed through the final standalone JAR.

The next isolated Linux VPS run passed source and standalone core checks, downloaded
Temurin 25, and ran actual Gradle/JUnit from paths containing spaces. The old TAR extractor
lost executable permission on lib/jspawnhelper: both the regression check and actual child
process launch failed twice. Preserving archive file permissions made both pass; that gate
now runs with self-test on POSIX systems. Linux logic checks rejected a deliberately wrong
expectation twice, then passed after restoration; the final warm check took 0.68s. This is
Linux command/build evidence, not Minecraft rendering or a zero-Java installer.

A fresh AppleSkin source-planning comparison with shared dependency caches went from
91.08s to 80.62s (11.5% less); source metadata went from 86.00s to 75.52s. Planning avoids
compiler/runtime classpath resolution while target compilation/auditing still loads the
full model. These times remain far from instant first use. A duplicate-name multi-module
fixture exposed cached tests running only one of two modules, twice; retaining separate
Gradle test tasks ran both and detected a failing second module. A tracked outcome gate
covers cold, warm, failing and restored runs in the portable CI workflow; that gate
also passed manually on the Linux VPS. The final standalone Linux launcher passed its
core checks from a separate installation folder containing spaces.

The final packaged AppleSkin run exposed vanilla focus pausing: the unfocused game
returned to GameMenuScreen and two inventory checks failed. The owned agent now disables
focus pausing in memory and reports its live value, restoring the original on owned quit.
With the final package (SHA-256 starts 476ccf66), the actual 1.21.5 world opened in 28.45s,
inventory opened in 1.75s and the repeated world/tooltip capture passed in 0.71s while
pauseOnLostFocus=false. Both final 1280×720 frames were inspected. Final Windows and
standalone Linux core checks also passed. That earlier client has since been stopped.

Whole-port speed evidence (2026-10-23): untouched AppleSkin 1.21.6 commit a1c60fc to a
packaged 1.21.5 mod plus mixin audit took 191.5s before and 61.5s after on Windows.
Both used fresh project caches with existing global JDK/download caches; neither is
empty-machine first use, and the comparison is not Windows versus Linux. Restoring the
original source and rerunning the full port with setup cached took 16.5s. Typed edits alone
took 1.1s. Game startup/checks are separate. Raw outputs: `.runs/whole-port-before-output.json`,
`.runs/whole-port-before-build-output.json`, `.runs/whole-port-final-output.json` and
`.runs/whole-port-final-warm-output.json`. Before/after changed source/config bytes match.

The CLI now skips dependency source conversion using Loom's supported CI flag, runs reviewed
mapping-backed AST recipes before compilation, reuses content-validated target metadata,
and runs the standard build once per attempt, including packaging. Unknown changes retain
compiler-driven repairs. Preflight gates cover real original sources and reject wrong imports,
shadowed API names, missing target descriptors and escaping matrices; the gate is in three-OS
CI, whose remote runs remain unverified. Nineteen portable outcome checks and archive/JDK
gates pass on both Windows and the isolated Linux VPS with standalone JAR25f7e4d7.

The final package loaded the actual Fabric1.21.5 AppleSkin3.0.6 world in 28.95s, PID61928.
Native1280x720 capture `2026-10-24T00-54-55.804988200Z-ce9d562a.png` in
`.runs/whole-port-final/build/modaccel/shots/` was inspected: the apple tooltip renders its
food and saturation icons. Visual-only proof retains behaviorVerified=false. The old combined
chat-key-and-type command failed three times before its screen opened; the new package waits
for native screen readiness and passed three combined chat commands plus inventory/hover.
The warm tooltip check took0.71s. No desktop captures or project-specific control mod.

Next speed work follows the measured critical path: source and target discovery took17.2s
and16.3s in the fresh-project run; the warmed standard build took10.2s of the16.5s whole port.
Jon dropped the Gradle-source optimization idea. Continue improving the complete port and
verification path in this project, using measured stages and preserving build checks.

Remaining work still follows the six steps below: actual Linux/macOS CI runs; bootstrap
without Java installed; target resolution beyond Fabric property/literal owners and
identifiable mods; semantic migrations and larger held-out version jumps; portable world/
multiplayer coverage and mod-specific behavior assertions; empty-cache first-use speed.
The older Windows lanes remain for existing behavior.

1. **Make the command portable.** Move orchestration from `modaccel.ps1`, `install.ps1` and
   `tools/*.ps1` behind the existing Java tools, with thin OS launchers and structured results.
   Preserve existing command behavior. Check clean install plus intentionally passing/failing
   logic and port runs on actual Windows, Linux and macOS; no PowerShell dependency.
2. **Discover projects and supported targets.** Replace installer/version assumptions with
   build metadata and explicit loader/version capabilities. Cover Groovy, Kotlin, multiple
   projects and Stonecutter layouts. Preserve existing build configuration; check onboarding
   on unseen repositories without source edits or pasted configuration, with repeat install
   and removal checks. Report unsupported dependencies before starting a repair.
3. **Make verification automatic.** Generalize the loader/version bridges and reusable client
   scenarios instead of adding a custom AppleSkin test for each mod. Tie results to the loaded
   build and check injection points, actual behavior, UI and server/client sync. Deliberately
   inject startup, rendering and stale-process faults; each must fail and name the cause.
4. **Finish semantic porting.** Extend `VersionMap`, `portlog` and the existing knowledge
   corpus with typed structural migrations and an assisted repair path for unknown changes.
   Preserve behavior and provide a reviewable, reversible patch. Check forward ports,
   backports and large jumps on held-out mods; keep target implementations out of learning.
5. **Optimize first use and repeats separately.** Instrument the complete command before
   choosing bottlenecks. Reuse verified setup artifacts and warm processes only when their
   project, sources, dependencies, mappings and runtime match. Check empty-cache first use,
   downloaded-but-stopped runs and live repeats separately, including invalidation failures.
6. **Prove stranger adoption.** Package the portable command, add OS/loader/version CI,
   machine-readable results and editor/agent entrypoints. Check representative unseen mods
   from installation through real gameplay on all supported systems, with cold/warm times
   and automatic versus guided repair rates. Publishing remains a separate approval step.

The dated snapshots and earlier queue below are historical evidence; this order governs new work.

## Where it stands (2026-10-22)

- Fabric: full loop, 1.5s screen, 2.9s scenario, 1.4s logic (8 tests).
- Stonecutter (Diorama): installs, fast lane 2.1s, world tier skips honestly.
- NeoForge: warm client from ModDevGradle's recipe (no Gradle); screen 3.2s, hot reload 1.5s (agent redefines classes), cold start 18.4s. Schema changes still need a restart.
- Forge (MineColonies, 2102 sources): install 5s, first run 23.9s, one-file edit 2.3s,
  broken file fails in 1.3s. No hot reload. (2026-10-23: also gets a warm client and screenshots -
  see the section at the end.)
- Porting: `-Check` answers in 9-89s; `-Log` groups compiler errors; all six benchmark mods
  repaired green with times recorded (Trinkets 1.15 min, Polymer 8.6 min, Traverse 5.9s, Terrestria 7.5s, Traveler's Backpack 11 min / 196 errors, Artifacts 28.1 min / 100 errors).
- Automatic fixes: `port -Fix` applies `knowledge\fixes.json` and rebuilds until green - Traverse 68 -> 0 errors in 2 rounds / 7.2s, Traveler's Backpack 111 getter sites in 1 round / 4.0s (still red on removed classes).

## The queue, in order

1. ~~Forge/NeoForge warm client~~ **DONE 2026-10-23**: one capture per Forge repo
   (`tools\capture-launch.ps1`), the bridge auto-copied into `mods\`, `.modaccel\forge.args`
   replayed, socket awaited. MineColonies: 854x480 title PNG, cold `ui` 58.6s, warm 0.5s. No hot
   reload on that lane.
2. Finish the four blocked repairs when their dependency is reachable (local mirror via
   Modrinth's maven works for Mod Menu; terraform-api needs a real mirror). Acceptance: 6 of 6
   green with minutes recorded.
3. Structural ports: 1.21.5 removed `RenderSystem.setShader` entirely and `BakedModel` became
   `BlockStateModel`, so Traveler's Backpack is a rendering migration, not a rename. Teach
   `port` to label such errors "structural" and stop pretending a rename table covers them.
4. Publish: one-command installer for all three loaders, README scope table kept true.
   **Done**: all three loaders install, and the README scope table now says Forge gets a warm
   client too.





## Where it stands (2026-10-23)

Done and measured:
- Fast loop + hot reload on Fabric (1.5s), NeoForge (1.5s) and 1.21.11 (1.9s); Forge 1.20.1 has the
  fast lane (2.3s on 2,102 sources) and a warm client from one captured launch, no hot reload.
- Installer for Fabric, Forge and NeoForge, verified from a fresh clone of the public repo. Forge
  gets `.modaccel\bridge-forge.jar` copied in and `modaccel` drops it in `mods\`, replays
  `.modaccel\forge.args` and waits for the socket: MineColonies answers with an 854x480 title PNG,
  cold `ui` 58.6s, warm 0.5s. One capture per Forge repo. A non-26.x repo gets no bridge and the
  installer says why.
- Fault matrix (`bench\fault-matrix.md`): 7 injected faults, tier by tier. Two harness bugs found
  and fixed (world false-pass from a stale argFile; ui hijacked by a bridge jar). Remaining gaps are
  listed there.
- Since the matrix: mixin audit (dead `@Shadow`/`@Inject` fails lint, +0.17s, found TB's dead
  shadow); `world` checks blockstates, item models and lang files in the live server (13-16s);
  `node` asserts the exact value and re-probes drift; `ui` prints only PNGs this run wrote; `mp`
  refuses stale classes and points at `-Cold`; `LabControl` falls back to the current screen when
  `-Dmodlab.screen` is empty; the installer's starter `pure` test makes the first run "1 passed".
- Toolchain gate: JDK by wrapper, loader and pins, `gradlew help` proof, NeoForge target check.
- Porting: `-Api` (version diff, stable-id renames; 1.21.4->1.21.5 in 1.9s: 205 removed, 25 moved,
  3345 members, 921 signatures, 351 replacements of which 222 stable-id), `-Log` (mechanical/
  structural, jar-diff citations, recipes), `-Fix` (11 rule families; Traverse 68 -> 0 in 7.2s, TB
  111 getter sites in 4.0s), `-Check` (pre-build breakage warning, including source references to
  removed symbols).
- All six benchmark mods repaired green with recorded times.
- Bridge jar: screenshots and `ui` on a client that belongs to someone else's mod.
- `restart` = stop, compile, start in one command (49s on the NeoForge node).

Left for the total goal (one command answers "did this change work" on every loader):
1. **Pre-build scan recall.** It sees exact removals in your own source only; renames and member use
   need a reference index to be counted before the first build. Largest gap.
2. **More fix rules.** `knowledge\fixes.json` covers 11 families. Removed or reshaped classes
   (CoreShaders, BakedModel, ThrownPotion), `Inventory.items/selected`, `appendHoverText` and the
   render-state refactor still need a human or a new rule; the fixer names them rather than guessing.
3. **Semantic rewrites stay guided.** Three recipes; a fourth kind of rewrite needs one written.
   Auto-generating render-state classes is deliberately out of scope (green-but-wrong risk).
4. **Fault-matrix gaps that remain:** a warm tier cannot see a fault that predates its process; a
   render exception kills the shared warm client and `ui` reports a raw transport error instead of
   the cause; Forge has no hot reload.
5. **Polish:** one cold-machine run to measure the 280 MB first run; the Forge lane still needs one
   manual capture per repo.


## First run on a cold machine (measured network, estimated total)

Measured here: the JDK download is 134.6 MB and this connection pulled 20 MB in 0.5s
(37.1 MB/s), so the harness's own 280 MB of JDK+JBR is about 8s of transfer plus extraction.
The rest of a first run is Gradle and Minecraft: Loom fetches the game jars and libraries
(typically 200-400 MB) on top. A truly cold machine has not been run end to end; the numbers
above are measured transfer rate x measured sizes, not a timed cold install.
