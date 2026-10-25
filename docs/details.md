# Modding Accelerator: details and evidence

Everything that used to sit in the README: the portable command's internals, the older Windows lanes, scenarios, multiplayer, the porting toolbox, measurements and where files live. The README keeps the short version.

## Portable command

The Java command runs without PowerShell. Use `modaccel.cmd` on Windows, `./modaccel`
in a POSIX shell, or `java -jar modaccel.jar` from any shell. A source checkout needs
JDK 17 or newer to start. Missing build/tool JDKs are downloaded from Adoptium into a
local cache, checked against the published SHA-256, and reused. Project Java settings
and the pinned Gradle wrapper determine the choices. `doctor` lists installed JDKs
without downloading anything. On Windows, a machine with no Java at all is handled by
the launcher: it downloads the same Adoptium runtime once (a 47 MB JRE for a packaged
JAR, a 196 MB JDK for a source checkout), verifies its SHA-256, extracts it into
`%LOCALAPPDATA%\modaccel` and reuses it. Measured here with Java removed from PATH:
39.2s the first time and 0.2s after, and the JDK download took 141s. Set
`MODACCEL_NO_DOWNLOAD=1` to keep the launcher from downloading. macOS and Linux still
need Java installed before the launcher runs.

```sh
./modaccel doctor --root /path/to/mod --json
./modaccel setup --root /path/to/mod --json
./modaccel dependencies --root /path/to/mod --to 1.21.6 --json
./modaccel fast --root /path/to/mod --json
./modaccel port --root /path/to/mod --from 1.21.5 --to 1.21.6 --fix --json
./modaccel ui-check --root /path/to/mod --json
./modaccel world-check --root /path/to/mod --world lab --json
./modaccel mp-check --root /path/to/mod --server localhost:25565 --json
./modaccel game-stop --root /path/to/mod
```

`setup` reads evaluated Gradle metadata, including custom source layouts and selected
modules (`--project :26.3` for the Diorama benchmark). It injects an invocation-only init
script; onboarding does not rewrite build scripts. `fast` runs tests tagged `pure` with
JUnit Jupiter. Its repeat path hashes inputs and compiled outputs, then reruns the tests
in a fresh JVM. Multiple test modules retain Gradle's separate classpaths, so identical
class names cannot hide another module's tests. No tests, failed tests and compiler errors
fail the command.

`port --fix` can update unambiguous existing Fabric properties for Minecraft, Yarn,
Loader and Fabric API from their published metadata. Third-party Fabric dependencies are
identified from resolved artifact hashes, or a unique matching source repository and
published loader/version support. Target releases must also exist in the project's Maven
repositories; existing properties or unambiguous dependency literals retain ownership.
When identity cannot be established, the port stops before editing and names every
unresolved artifact with the exact selection entry to add. `.modaccel/dependencies.json`
(or `--selection FILE`) accepts `source` plus either a `target` coordinate with `modrinth`
or `repository` evidence, or `disable: true` with a `reason` and the published project.
A selected target is verified, not trusted: the coordinate must publish from a repository
the build already declares, and its published release (or its own Fabric manifest
constraint) must accept the chosen Minecraft version. A disable is allowed only after the
named published project is shown to have no release for that version; it comments out the
one active declaration, moves aside source files that import the artifact, removes the
matching manifest entrypoints, and restores all of it on undo. `dependencies --to VERSION`
reports the same result without editing, so a user can fix a dependency before porting.
Source planning skips compiler/runtime classpath resolution; target compilation and mixin
auditing still use the full evaluated model. Supported typed API rewrites run before the
first build; unknown cases retain compiler-driven repair. A manifest range that already
accepts the target stays byte-exact; only a constraint that excludes it is retargeted.
The annotation processor's own "no target" report removes one dead name from a
multi-target mixin, and a recorded lesson is kept in the project's `lessons.jsonl`.
1.21.6 draw-context matrix changes (push/pop/translate) are covered by typed recipes.
Each normal attempt runs the evaluated `build` task, including packaging, rather than
compiling and then building again.
Invocations set Loom's supported `fabric.loom.ci` flag to skip dependency source downloads
and source remapping; binary dependency remapping and the project's build checks remain.
It uses the existing typed mapper, rules and strict corpus learner, excluding the ported
mod's own repository. Each port records original bytes, a patch, compiler and
mixin results, and an undo location. `port --undo <location>` restores those bytes only
if later edits would not be overwritten. For other dependency layouts/loaders, set target
dependencies first. Unresolved dependencies and semantic changes need guided repairs;
a successful compile is explicitly not gameplay verification. When a semantic change has
no matching recipe, the port stops after its bounded rounds and lists the remaining
compiler errors with `stoppedSafely` instead of guessing or weakening checks.

Measured on Windows: untouched AppleSkin 1.21.6 sources to a packaged 1.21.5 mod and mixin
audit took **191.5s before, 61.5s after**. Both used fresh project caches and shared existing
JDK/download caches; neither is an empty-machine or Linux comparison. Restoring the source
and repeating with setup cached took **16.5s**, rerunning rewrites and the full build.
The rewrites alone took **1.1s**. These whole-port times exclude starting/checking Minecraft.
Receipts and raw command timings are in `.runs/whole-port-{before,final}*-output.json`.

Two additional unfamiliar upstream source snapshots produced 1.21.6 mod JARs with no manual
source or build edits: F3Nope from `d65a225` (101.7s initial build; 14.8s latest complete port with setup
cached) and NoDeathAnimations from `893f57f` (88.0s initial build; 13.4s cached repeat). Initial runs used fresh
project caches and existing machine downloads, not an empty machine. F3Nope's original manifest
declared 1.20.1 despite its 1.21.5 build; the actual client rejected it twice. Normal porting now
updates that manifest too. The latest F3Nope port also preserves the old RGB text opacity
under 1.21.6's changed rendering behavior. Its final automatically repaired JAR was then
loaded in the actual 1.21.6 client: `world-check` opened the existing `beta visual` save
(27.0s, world path verified), and `ui-check --key 292` captured a 1280×720 framebuffer in
0.74s. The inspected frame shows all three overlay lines ("F3Nope Mod", "Debug info hidden",
"Configure with /f3nope") drawn opaque white over the world, so hidden debug info plus a
visible mod overlay is the observed behavior, not only a passing build. The same path was
repeated through the extracted package: port 39.3s to the identical JAR, world-check 27.9s,
then the visual gate at 1.055% with the three overlay lines inspected. The initial build
timings precede these repairs and are not a controlled before/after speed comparison.
Trinkets, an untouched 1.21.5 Fabric project, shows the dependency path end to end. Its
two Cardinal Components modules and Cloth Config resolved automatically; REI's API artifacts
were selected explicitly at 20.0.811 (published for 1.21.6, verified from the project's own
Architectury Maven and the published REI release); EMI was disabled after its published
project showed no 1.21.6 release, removing one compat plugin and its entrypoint. All six
dependency changes are in the receipt. The build then reached real 1.21.6 code changes:
the dead mixin target and the draw-context matrix calls were repaired automatically, and
the port stopped on seven errors in `LivingEntityTrinketComponent.java` that need a
data-component migration (CCA 7 `writeData(WriteView)` and the removed
`ItemStack.fromNbt`/`toNbt`). That is a named semantic blocker, not a dependency blocker.
Traverse still stops at a Terraform dependency with no verified published target.
Trinkets' dependency result is `.runs/beta-trials/trinkets-deps-packaged.json`; trial
receipts are under `.runs/beta-trials/<mod>/.runs/portable/`.

The reverse direction is verified too: a newer untouched upstream F3Nope snapshot (1.1.0
on 1.21.6, `f3nope-upstream`) was backported to 1.21.5 by the same command in 59.7s,
including its build and mixin audit, and the actual 1.21.5 client then loaded it (mod list
shows `f3nope 1.1.0`, its initializer ran, 30.0s to the captured title screen). Receipts
`.runs/beta-trials/f3nope-backport-port.json` and `f3nope-backport-ui.json`.

NeoForge is no longer manual. A selected NeoForge project gets its `neo_version`
(and `neoforge_version`) resolved for the chosen Minecraft version from
`maven.neoforged.net` by Minecraft minor (1.21.6 publishes as 21.6.x), its third-party
mods are identified on Modrinth with the NeoForge loader, and the port refuses to continue
unless the evaluated NeoForge artifact really targets that version. The first real one,
Snownee's Jade (`1.21-neoforge` branch, 1.21.1 / NeoForge 21.1.21), had `minecraft_version`
and `neo_version` rewritten to 1.21.6 / 21.6.9 in 29.7s, then stopped in Jade's own build
script: NeoGradle 7.0.145 reports "Could not find run type data" for NeoForge 21.6, so that
plugin has to be bumped by hand. An earlier run before the NeoForge pin was wired looked
successful (238s, jar named 1.21.6) while it had really compiled against NeoForge 21.1.21;
the evaluated-artifact check now refuses exactly that false green.

A real NeoForge mod now goes through end to end. `Riqqqque/ZombieApocalypseAddon` (71
Java files, NeoGradle 7.1.38, 1.21.1 / NeoForge 21.1.248) was ported to 1.21.4 /
NeoForge 21.4.161: the tool resolved the NeoForge version, rewrote `minecraft_version`,
`minecraft_version_range` and `neo_version_range` (a mod that still declares the old game
range is refused by NeoForge at runtime even though the build passes), renamed the
NeoForge 1.21.4+ `data` run type to `clientData`, skipped the repo's nested 1.20.1 builds,
built and audited mixins in 73s. Nineteen compiler errors remained and were migrated by
hand (Equipable -> `DataComponents.EQUIPPABLE`, `Registry.get` -> Optional unwrapping,
`EntityType.create(Level, EntitySpawnReason)`, `getMinY`/`getMaxY`, `ServerLevel`
game rules, sound holders, `convertTo(ConversionParams)`, `spawnAtLocation(ServerLevel, ...)`,
`getLootTable().orElse(null)`, the `TargetingConditions.Selector` lambda). Those edits are
now reusable rules: a fresh copy ports with no manual edits at all (19 reported errors
repaired, 75s including the cold build, receipt `.runs/beta-trials/zombie-1214d-port.json`).
The ported mod then loaded in a real
1.21.4 NeoForge client (mod list: "3 mods", Zombie Apocalypse Addon 2.8.0; shot
`.runs/beta-trials/zombie-apoc/.../shots/...b52bd394.png`). A port now also reads the
packaged `neoforge.mods.toml` back out of the built jar and fails if its declared Minecraft
range does not accept the target, so a correct jar that the loader would still refuse can
no longer pass. In a real 1.21.4 client the ported mod's own `/za` dashboard rendered
inside a generated world (waves, caps, events, difficulty and world-pressure lines; shot
`.runs/beta-trials/zombie-apoc/ZombieApocalypseAddon-main/build/modaccel/shots/2026-10-24T07-25-46.639496800Z-404d2a2a.png`),
so commands and config load work on the new version; wave behaviour itself is still
unverified.

Stonecutter repos are understood too. Diorama (Java sources, Kotlin Gradle scripts, 22 nodes, one
shared source tree) is listed node by node by `setup`, `compile --project :26.3` builds,
and `port --project :1.21.1 --to 1.21.4` rewrote that node's Minecraft and Fabric API
versions and built it, stopping on 45 real 1.21.4 API errors. Their repairs belong in the
shared, preprocessor-annotated `src/` rather than the generated per-node copies, which is
the remaining gap for Stonecutter.

The same mod five minors forward, 1.21.1 -> 1.21.6, drops from 38 reported errors to 9 automatically (NBT contains/UUID codecs, day-night renames, moveTo -> snapTo, water checks, the 114 holder sound constants); the 9 left are the 1.21.6 SavedData codec redesign and owner/saddling API, both semantic rather than renames.

Speed, measured on the same F3Nope hop: a full port with every cache valid takes **4.6s**
(model cache hit, preflight 0.9s, build 3.3s, mixin audit 0.2s). The first time a hop is
visited it takes ~39s warm (planning model 14.8s, full model 6.3s, build 5.7s, corpus 2.8s,
tools 1.5s) and the first NeoForge build of a version adds minutes of Gradle download and
decompile. Corpus learning is now deferred until a build actually fails, and receipts carry
`stageSeconds`/`unaccountedSeconds` so the gap is measured rather than guessed.

`ui-check` starts the actual development client, waits for resource loading, and saves a
validated 1280×720 Minecraft framebuffer. Repeats use the same owned client; stale builds
are refused. Owned sessions temporarily disable focus pausing so background inputs
can proceed. An unchanged stopped client can use its verified saved launch recipe.
Long saved classpaths use a Java argument file, avoiding Windows' process command limit
while retaining Loom's existing argument files.
Screen classes and labels follow the target version and game language. For example:

```sh
./modaccel ui-check --screen com.prismlab.client.PrismScreen \
  --click "More shards" --expect-value shards=6 \
  --expect-screen com.prismlab.client.PrismScreen --json
```

`--click-at x,y` and `--move-at x,y` use the 1280×720 framebuffer coordinates;
`--key <GLFW number>` presses/releases a game key, and `--type <text>` enters one line
into the current screen. Inputs run on the game thread in this order: screen, label click,
coordinate click, cursor move, key, text, assertions, capture. A combined key-and-type action
starting from gameplay waits for the opened screen before typing. The receipt records actions.
`--expect-value getter=value` checks an actual screen getter after the click.
`--assert 'package.Checks#staticMethod'` calls a supplied zero-argument static assertion
on the game thread; it must return void or true. `--expect-world` requires both a loaded
player and a world. `--expect-change PERCENT` captures the frame before the input and
after it, then fails unless at least that percentage of pixels changed; the receipt records
the percentage, the changed region, both captures and `behaviorVerified=true`. That is the
reusable check for output that renders but is invisible: on F3Nope's 1.21.6 port the F3
overlay changed 3.699% of the frame, while the same source with the old transparent text
changed 0.110% and failed a 0.5% gate (`.runs/beta-trials/f3-visual-change*.json`). A
fresh client's accessibility onboarding screen is dismissed through its Continue button
before opening a save. Captures still need visual inspection; there are no pixel hashes or golden updates.
`world-check --world <save>` opens an existing save and verifies its native save path.
`--save-roundtrip` then records the player position, saves and quits to the title,
reopens the same save and requires the position to come back within 0.05 blocks; the
receipt keeps both states and `behaviorVerified=true`. On F3Nope's 1.21.6 save the
round-trip took 7.2s and returned (9.5, 101.0, -2.5) in `minecraft:overworld`, and through
the packaged launcher AppleSkin 1.21.5 returned the same position in 54.9s including a cold
client start. The moved-position rejection is unit-tested; a real lost-save fault was not
injected.
Safe single-component save names may contain spaces or Unicode; Fabric 1.21.5 Yarn and
26.3 Mojang loader paths have been exercised. `mp-check --server host:port`
connects to an existing dedicated server; its build remains unverified. Without
`--server`, the command starts the project's evaluated dedicated-server task using
its existing accepted EULA and settings, then stops that owned server after the check.
Joining a world alone leaves `behaviorVerified=false`; add a meaningful assertion
to check the mod's behavior.
Every command saves a JSON receipt in the target's `.runs/portable/` with its actual
verification tier, stage times and logs. Dependency changes require stopping the client
and checking the rebuilt game.

`eval --code SNIPPET` (or `--file SNIPPET.java`) runs Java inside the owned client and prints
the value. The agent compiles the snippet with the JDK's compiler against the game's classpath
and loads it with a child of the loader that loaded Minecraft, so it sees the game and the mod
as they run, mixins included. It runs on the game thread unless `--thread control` is given.
`mc` is the Minecraft instance, `print(x)` adds output, `vars` keeps values between snippets,
and `call(objectOrClass, "name", args...)` / `field(objectOrClass, "name")` reach private
members. Leading `import` lines are allowed; compile errors and stack traces point at snippet
lines. Measured on Diorama (Fabric 26.3, Windows): 0.6–1.5 s per snippet against a running
client, for example the map tile under the player and its bake state.

`eval --until CONDITION` runs the condition every quarter second, on the game thread unless
`--thread control`, until it returns `true`, and prints how long that took; `--timeout SECONDS`
(default 60, at most 120) fails it otherwise, with the last value. An exception fails it at
once. It replaces the polling loop a snippet would otherwise need to wait for a screen, a
world or a baked tile: on Diorama 26.3, `--until 'MapSession.get() != null &&
MapSession.get().map().chunkCount() > 300'` returned after 6.1 s of world loading.

`reload` recompiles the selected project's classes and swaps them into the owned client. The
agent records a hash of every file in the project's class and resource folders on the game's
classpath at startup; a reload redefines the changed classes the game has loaded, leaves
unloaded ones for the loader to read on first use, and reloads resource packs when a resource
changed. The compile runs without the init script and with Gradle's configuration cache, so
on Diorama's 21-project build it took 2.7 s instead of about 11 s; the redefinition took
0.15 s. A changed class shape on a stock JVM, a mixin, or mod metadata is reported as needing
a restart (exit 1), and nothing in that batch is applied. After a reload that applied
everything, the session's fingerprint is the new build's, so `ui-check`/`world-check` accept
it. Objects keep their field values and static initializers do not rerun.

`--jbr` on `ui-check`/`world-check` starts the client on JetBrains Runtime of the same Java
version, with `-XX:+AllowEnhancedClassRedefinition -XX:TieredStopAtLevel=1` (C2 plus
redefinition has crashed the VM). Loom fixes `runClient`'s launcher before an init script can
change it, so Gradle prepares and records the launch, skips starting it, and modaccel starts
the recorded command on JBR, which Gradle's toolchain resolution provides. With it, reload also
swaps added and removed methods and fields: on Diorama an added static method and field were
callable after a 3.4 s reload, and the field kept its value across the next reload.

`--java JAVA_HOME` on `ui-check`/`world-check` starts the client on that runtime instead of the
build's, the same way: Gradle prepares and records the launch, and modaccel starts it with the
given `java`. Some failures only happen on one runtime: Mojang's launcher ships Java 21.0.7 for
Minecraft 1.20.5 to 1.21.11, whose C2 compiler crashes on some code (JDK-8358534). With
`--java` pointing at Microsoft's 21.0.7, a Diorama 1.21.1 build from before its fix died 8 s
into world creation with that crash, and the fixed build ran 3.5 minutes without it. A running
client on another runtime is refused rather than reused.

Local checks cover Windows PowerShell/Git Bash, Fabric 1.21.5/1.21.6/26.3 and NeoForge 1.21.1.
An isolated Linux VPS passed source/packaged core checks and real Gradle/JUnit checks
from paths with spaces. A deliberately failing test failed twice; after restoration,
the verified warm run took 0.68s. Downloaded Linux Java 25 also launched child processes
after preserving TAR executable permissions. Linux Minecraft rendering and macOS have
not been exercised; the three-OS CI workflow has not run. Universal version coverage,
automatic semantic repair, portable world/multiplayer coverage across versions,
empty-cache setup speed and Forge framebuffer checks remain unfinished.
`package` builds `build/distributions/modaccel.jar`, `modaccel-portable.zip` and their
SHA-256 checksums locally; nothing is published. The older Windows lanes described below remain available through their
existing verbs (`ui`, `node`, `world`, `mp`, and single-dash `port` options).

> **Not a finished product, and not the only option:** what is still missing is in
> [`ROADMAP.md`](ROADMAP.md), and how it compares to existing tools (including where they win) is
> in [`bench/alternatives.md`](bench/alternatives.md).

## Earlier Windows source-checkout lanes

The following describes the existing Windows lanes and their recorded benchmarks.
Their hot reload and installer behavior does not apply to the portable beta above.

A fast test loop for Minecraft mods (`modaccel`: Fabric, NeoForge and Forge). Save a file, and a
second or two later you know whether your logic still works - without launching Minecraft, loading a
world, or clicking anything.

Then, when the change is visual or behavioural, the same command swaps your new code into a
client that is **already running** and proves it with pixels or a ticking block, in two to
four seconds. Forge has no hot reload, so it gets the pixels without the swap.

This repo works on day one: it contains a small real mod (an item, a block with a ticking
block entity, an in-game screen and a mixin) plus the tests for all of them. Replace the mod,
keep the loop.

Verified on **Minecraft 26.3 + Fabric** (Loader 0.19.5, Fabric API 0.163.0, Java 25), on
**1.21.11** (same demo, own node), on **NeoForge 1.21.1** (own node, Java 21) and on
**Forge 1.20.1** (MineColonies, built by the pinned JDK 17; no hot reload).

## What this covers, and what it does not

| Loader | Fast lane, warm client, scenarios | Installer | Port tooling |
|---|---|---|---|
| Fabric (Loom) | yes | yes | `-Check`, `-Log`, `diff`, `port` |
| NeoForge (moddev) | yes (own node) | yes | `-Check`, `-Log`, `diff`, `port` |
| Forge / ForgeGradle | yes: fast lane, plus a warm client from one captured launch (no hot reload) | yes (fast lane + bridge) | `-Check`, `-Log`, `diff` |

ForgeGradle repos get the fast lane first - compile plus pure logic tests, without Gradle - and
now the warm client too (below). Measured on MineColonies 1.20.1 (2,102 sources, JDK 17):

| Check | Time |
|---|---|
| `gradlew build -x test`, first build (downloads, setup) | 328.3s |
| `gradlew build -x test` after a one-file edit, Gradle daemon warm | 137.1s |
| first `.\modaccel fast` (JUnit fetch, full compile) | 23.9s |
| one-file edit, `.\modaccel fast` (whole command; compile + tests step 2.5s) | **4.2s** |
| deliberately broken file | FAIL in 1.3s with file and line, exit code 1 |

The warm client is not tied to Loom any more. Each loader gets its own launch, and the client stays
up between checks:

| Loader | Warm client source | Measured |
|---|---|---|
| Fabric | Loom's launch config | screen 1.5s, 7 classes redefined |
| NeoForge | ModDevGradle's generated recipe (no Gradle) | first check 25.6s, screen **3.2s**, hot reload **1.5s**, restart only for added fields/methods |
| Forge 1.20.1 | one captured command (`tools\capture-launch.ps1`) into a Java argfile, replayed with the Forge bridge jar | 854x480 title PNG; cold `ui` **58.6s**, warm **0.5s** |

`install.ps1` wires a Forge repo itself (`labClasspath` block in `build.gradle`, harness copied, no
Fabric files touched) - measured on MineColonies: install 5s, first `fast` 13s including Gradle,
then 1.9s. It also copies the Forge bridge to `.modaccel\bridge-forge.jar`; `modaccel` puts that
jar in the repo's `mods\`, replays `.modaccel\forge.args` and waits for the socket, so the first
`.\modaccel ui` needs no manual step. One capture per Forge repo, and there is no hot reload there:
a code edit means the client starts again (58.6s), while a repeat check against the running client
answers in 0.5s. ForgeGradle rewrites its whole command line on a dependency change, so the
captured launch is re-taken with the same tool after such a change.

A big repo usually stops at its **toolchain** before its code: a Gradle that refuses the JDK, a
NeoGradle newer than the wrapper, or a maven that is down. That is what `port -Check` answers first.

![Measured](bench/benchmarks.png)

Every number in that picture comes from a run in this repo (`.runs/`); `bench/charts.ps1`
regenerates it from `bench/data.json`. There is an offline HTML version in
`bench/benchmarks.html`.

![Eight real mods](bench/mods-vs-loop.png)

The same comparison across eight real mods, each one's own full build next to the loop's answer, where a fast-lane run exists.

---

## The old way, and what this changes

How a mod change is normally checked:

1. Edit the code.
2. `./gradlew runClient` and wait 15-25 seconds for the client to reach the title screen.
3. Create or load a world, another 5-15 seconds.
4. Get to the thing: `/give` the item, find the block in creative, craft it, or open the GUI.
5. Look at it, do the action, read the log, decide.
6. Quit the client (it saves), change one line, start again.

That is **one to several minutes per change**, and most of it is waiting. Multiply by every
tiny edit and the afternoon disappears. The alternative most people use - Fabric's GameTest - still
costs a full server boot (15-30s) in its server form, and its *client* form (which does capture
screenshots, from CI) pays a fresh client per run (28-40s here).

What this lab does instead:

| You changed | You run | You wait |
|---|---|---|
| A recipe, sort order, codec, config rule | `.\modaccel` | **1-3s** - no Minecraft at all |
| A screen: layout, colours, text | `.\modaccel ui` | **1-3s** - a screenshot you can look at |
| A block entity, item behaviour, tick logic | `.\modaccel node` | **2-4s** - real world, real ticker |
| A mixin body | `.\modaccel world` | 13-30s - mixins are applied at start-up and need the server tier |
| A **new** block/item/registry entry | `.\modaccel world` | 15-30s - the registry only exists at start-up |
| A final "does the real game do this" pass | `.\modaccel look` | 30-60s - fresh client, four screenshots |

Nothing appears on your screen during any of this. The client runs detached; the server is
headless.

---

## For agents

The same onboarding lives as a skill at `skills/modding-accelerator/SKILL.md`, and `install.ps1` copies it to
`.claude/skills/modding-accelerator/SKILL.md` in your repo so an agent there finds the loop without being told.

## Install it into your mod (one command)

You do not have to copy code around or read a conversion guide.

```powershell
git clone <this repo> C:\tmp\mod-lab
C:\tmp\mod-lab\install.ps1 -Target C:\path\to\your\mod
```

Then, in your mod repo: `.\modaccel`.

What the installer touches, and nothing else:

- adds `lab.ps1`, `lab.cmd`, `tools/` (the fast lane, the agent, the lint)
- appends **one marked block** to your `build.gradle` (an `// Added by Modding Accelerator. Safe to
  delete as one block.` section with the
  warm-client run config, the game test source set, the JUnit deps and one classpath task).
  Delete that block and everything is exactly as it was.
- creates `src/gametest/` with a starter `@GameTest` you can replace
- copies the onboarding skill to `.claude/skills/modding-accelerator/SKILL.md`
- adds `.modaccel/`, `.runs/`, `.tools/` to `.gitignore`

It never edits your sources, your `fabric.mod.json` or your assets. Running it twice is safe.
Groovy `build.gradle` only for now; Kotlin DSK (`build.gradle.kts`) is refused with a message.

Verified by installing into a fresh `fabric-example-mod` (26.3) clone: `.\modaccel` passed in 1.1s
and `.\modaccel world` in 18.9s with no other edits; the starter `pure` test means the first run
prints `1 passed`, not `0 passed`. The installer also writes an empty `-Dmodlab.screen=` into a repo
with no custom screen, and `LabControl` then opens whatever screen is current instead of failing.

A repo on a Minecraft version other than 26.x still gets the fast lane and the world tier, but the
installer refuses the client bridge and says why: its sources call 26.x client classes, so `ui`,
`node`, `look`, `suite` and `mp` need that socket and stay unavailable until the repo is on 26.x.
A bridge copy an earlier 26.x install left behind is removed.

### Checklist: install into an existing mod

- [ ] Your repo builds today (`.\gradlew build` passes)
- [ ] Run the installer with `-Target` pointing at your repo
- [ ] `.\modaccel` - lint + logic, about a second
- [ ] `.\modaccel world` - proves your registrations and mixins load
- [ ] Add your own `@Tag("pure")` tests and `@GameTest`s as you go
- [ ] For UI checks, point the client test at your screen (see the UI section)

---
## Quick start

**Requirements:** Windows, PowerShell 7 (`winget install Microsoft.PowerShell`), and about
2GB of free disk for the first run.

1. Get the folder and open a terminal in it.
2. Run `.\modaccel`.

The first run downloads the two JDKs it needs (Temurin 25 and JetBrains Runtime 25, about
280MB) into `.tools`, asks Gradle for the Minecraft classpath once, then answers in about two
seconds:

```
PASS  lint      0.7s  lint: 3 registrations, all files present; mixins: 1 mixin, 1 target, 1 member checked, 0 dead
PASS  logic       0s  8 passed
```

That is the setup finished. No environment variables, no IDE, no manual Gradle.

The lint line includes a mixin audit: a `@Shadow` or `@Inject` whose target no longer exists fails
the run instead of applying silently, and the audit costs 0.17s. It found Traveler's Backpack's dead
`@Shadow`.

### First-run checklist

- [ ] PowerShell 7 installed (`$PSVersionTable.PSVersion` shows 7.x)
- [ ] `.\modaccel` passes (it downloads the JDKs on the first run)
- [ ] `.\modaccel stop` works and `.\modaccel doctor` shows a JDK and no running processes
- [ ] `.\modaccel look` once, then look at the four PNGs it prints (nothing to accept or commit)

---

## Commands

| Command | Use it for | Time |
|---|---|---|
| `.\modaccel` | logic and registration/asset/mixin lint | 1-3s |
| `.\modaccel ui` | a screenshot of your screen, plus a real button click | 1-3s |
| `.\modaccel node` | a block entity in a live world, with an exact expected value | 2-4s |
| `.\modaccel world` | new registrations, mixins, assets and lang keys, a fresh headless server | 13-30s |
| `.\modaccel suite` | a file of named cases against one warm client | seconds |
| `.\modaccel look` | a fresh client: four screenshots, including a 1080p one | 30-60s |
| `.\modaccel mp` | a dedicated server, a hidden client, and the same scenario (`-Cold` refuses a server older than the sources) | 4s warm, 60s first run |
| `.\modaccel status` | each lab process: port, pid, how long it has been up | instant |
| `.\modaccel -Version 1.21.11 ui` | the same check on another Minecraft node | 1-5s |
| `.\modaccel port -New <jar> -Apply` | the port report, plus the renames that are safe to apply | 2-3s |
| `.\modaccel restart` | stop, compile, start after a schema change | 49s on NeoForge |
| `.\modaccel all` | every tier, cheapest first | - |
| `.\modaccel watch` | re-run the fast lane on every save | - |
| `.\modaccel doctor` | versions, and what is running | - |
| `.\modaccel logs` | recent runs with timings | - |
| `.\modaccel stop` | stop the resident fast lane and the hidden client | - |

`lab.cmd` runs the same thing from a double-click or a plain `cmd` window.

---

## Scripted scenarios

```
.\modaccel scenario -Steps "place=yourmod:machine|wait=40|assert=energy|screen=yourmod.client.MachineScreen|click=Start|capture=machine-running"
```

Steps: `place=namespace:id`, `wait=<ticks>`, `assert=<method>` (a block entity method that must return > 0), `screen=<class>`, `click=<button label>`, `capture=<name>`, `settle` (waits until two frames 2 ticks apart are identical, 20 tries, then fails rather than save a half-drawn shot). Warm runs are 0.2-3s; the first one pays the client start (8.6s).

### A whole suite in one command

```
scenarios\prismlab.txt
world-node|place=prismlab:prism_node|wait=40|assert=charge|settle|capture=suite-world-node
ui-prism-screen|screen=com.prismlab.client.PrismScreen|click=More shards|settle|capture=suite-ui
```

`.\modaccel suite` runs every line against one warm client, prints PASS/FAIL per case and the total, and exits non-zero if any case failed. Measured: 2 cases in 4.9s warm; the first case in a suite still pays the world load (4.1s).\n\n## The three tricks

**1. A JVM that stays awake for your logic.** A small background program compiles your
sources with `javac` and runs your tests in a fresh class loader. No Gradle start (3-6s), no
Minecraft bootstrap (8-13s). This is why logic checks are a second.

**2. A client that stays awake for your UI and blocks.** One Minecraft client is started once
and left alone, with its window hidden so it never steals focus. Each check recompiles your mod and swaps the changed classes into the running
process, then opens your screen or places your block and answers. JetBrains Runtime's enhanced
redefinition means you can even add a field or a method. Mixin bodies are the exception - mixins are
applied at start-up, so a mixin edit needs `.\modaccel world`.

**3. Screenshots, not eyeballs.** `modaccel ui` captures the real client's framebuffer at a fixed
854x480 and GUI scale 2, writes two PNGs (before and after a click), and prints their paths. It
takes a baseline of the shot folder first, so it only prints a PNG this run wrote. You
or your agent look at the picture; there is no pixel comparison to maintain, and no golden to
accept.

## Multiplayer

```
.\modaccel mp -Steps "place=yourmod:machine|wait=60|assert=energy|settle|capture=mp-machine"
```

This starts a dedicated server (flat world, offline mode, kept alive by a stdin pipe), a second
hidden client on port 27389 that joins it, and runs the same scenario steps. In multiplayer the
client cannot reach into the server's world, so `place=` sends a `labplace` command and `assert=`
reads what the server actually sent back to this client.

That difference is the point: it caught a real bug in the demo. On 26.3 the default block entity
update tag is empty, so `charge` never reached a client; singleplayer could not see it because the
"server" and client share one object. The fix was overriding `getUpdatePacket()` and
`getUpdateTag()`.

A server or client started before your last edit holds stale classes, so `mp` refuses to reuse one:
it names the changed and start times and tells you to run `.\modaccel mp -Cold`, which stops both
and loads the sources on disk.

Measured: 4.1s warm for a place/wait/assert/settle/capture case (26.3 is about 11s including the
first world join); a first `mp` run pays the server boot (24s) plus the client start (27s).

`mp -Two` adds a second hidden client that reads the same block from its own world, which is the
only way to prove the server sent it to someone who did not place it:

```
PASS mp    12s   placed at -1, -58, 6 | assert charge() = 3 | screenshot mp-first.png
PASS mp2   12s   at -1, -58, 6 | assert charge() = 64 | screenshot mp-second.png
```

The second client costs one cold start (about 200-260s here, because Gradle serialises two run
tasks from the same project) and then answers in about 12s.

`.\modaccel status` answers "what is running" in one shot:

```
lab        C:\...\modding-accelerator
fast lane   down port 49776
mp client   up   port 27389 pid 51692  3m11s   JBR client (mp)
lab server  up   port 25565 pid 29536  3m40s   dedicated server
```

Every long step prints its own `[  24.2s] what it is doing` line, so a command is never silent
while it waits on a port, a build or a game.

## Porting map

`docs/porting-map.html` lists every Java release from 1.0 to 26.3. From 1.14.4 on, each neighbouring
pair of releases is diffed in Mojang's own names (`java tools/ChangeIndex.java`), joined across
versions by Fabric's intermediary ids; 26.1 and later ship unobfuscated, so those steps are matched
by name and are a best guess. "Names that break" counts public classes, methods and fields that are
renamed, removed or change signature. "modaccel handles" counts the renames `port --fix` rewrites
from the mappings plus the ones an automatic rule in `knowledge/` names; how-tos are counted
separately. An update that breaks 1% or more of the old names starts a new family, and each
family's hub is its release with the most mods on Modrinth. Before 1.14.4 there are no official
names, so families are grouped by version number, marked estimated, and nothing is measured.

A line is green only when `coverage/ledger.json` has a port across that whole hop that loaded or
passed a behaviour check in the game; yellow when a ROADMAP step marked doing covers it; red
otherwise, with the reason shown. Refresh: `java tools/ChangeIndex.java`, then
`python bench/change_routes.py` and `python bench/porting_map.py`.

## Porting, measured

Six open-source mods (Trinkets, Traverse, Terrestria, Traveler's Backpack, Polymer, Artifacts),
each bumped one Minecraft step (mostly 1.21.5 → 1.21.6), baseline build green first:

| Mod | Baseline build | Bumped build | Compile errors |
|---|---|---|---|
| Trinkets | 321s | 156s | 21 |
| Traverse | 329s | 115s | 68 |
| Terrestria | 142s | 89s | 99 |
| Traveler's Backpack | 335s | 161s | 6 |
| Polymer | 474s | 186s | 8 |
| Artifacts (1.21.1 → 1.21.4) | 568s | 167s | 97 |

When first bumped, every one of the six stopped at its first compiler failure. What dominated was API shape, not the version
number: one Fabric tag-provider removal is 165 of 299 errors, one GUI matrix change is 17 of
Trinkets' 21, and Artifacts' 97 are the 1.21.2 entity render-state refactor (68 hard rewrites).
Error counts are not effort — Terrestria's 99 errors are 3 symbols in 2 files.

Repair time is measured on all six, each driven to a green build and confirmed with a clean
build: **Trinkets 1.15 min** (21 errors), **Polymer 8.6 min** (35 errors across six modules),
**Traverse 68 call sites** (green build 5.9s), **Terrestria 142 call sites** (7.5s), and
**Traveler's Backpack 11 min** (196 errors, four build cycles, 5s final build). What dominated was
Gradle sequencing and reading, not compiling: Traverse and Terrestria were one removed Fabric
method each, while Traveler's Backpack was one getter family (130 of 196 errors were
`CompoundTag.getX` returning `Optional`) plus 20 distinct API facts.

**When the build is red, read the compiler, not the jar.** `port` and `diff` read compiled classes,
and a failed javac writes no class file for the broken sources: against the two repairs above,
SymbolDiff predicted **0 of 21 and 0 of 35** errors. So there is a second lane:

```
.\modaccel port -Log <build.log> [-New <target jar>]
```

It reads javac's own errors, groups them by file and line, names the nearest class in the target jar
and attaches knowledge notes. Measured on the same logs: 21 errors in 3 files (Trinkets) and 8 in 5
(Polymer). The order is: log report, fix, build, then the binary diff to sweep what only appears once
classes exist.

A known port cause can fix itself: `.\modaccel port -Fix -Log <build.log> [-Task <gradle task>] [-Max 5]
[-DryRun] [-Repo <repo>]` applies `knowledge\fixes.json`, rebuilds and repeats until green, one line per
round (rules, replacements, seconds, errors left). Measured on copies: Traverse 68 -> 0 errors in 2 rounds
/ 7.2s; Traveler's Backpack 1 round / 4.0s for 111 getter sites, then stops at the 6 removed classes no
rule covers.

When `gradle.properties` changes Minecraft versions from `HEAD`, `-Fix` also generates a Yarn or
Mojang rename map and learns qualified API rewrites from other mods in `knowledge/port-corpus.json`.
It excludes the target mod and keeps its learned rules in that repo's `.modaccel/learned/`.
Unrelated types, private helpers and uncertain rewrites are left for a guided port; failure stays
nonzero. Repeated Gradle diagnostics count once, with separate errors on the same line retained.

The Yarn fixer also handles verified input-window, entity-world and local GUI-matrix migrations.
Fresh AppleSkin sources reached zero compile errors for 1.21.6 (14 -> 0, 14.7s) and 1.21.9
(10 -> 0, 8.9s). Compilation does not validate mixin injection points: the 1.21.9 debug hook
needed a guided migration, saved in `bench/port-tests/appleskin-1.21.9-runtime.patch`.
Both clients passed real world/HUD/tooltip tests, including large-value counters, with checked
1280x720 framebuffer captures. To repeat on a disposable AppleSkin fixture, run
`bench/port-tests/Install-ClientTest.ps1 -Repo <repo>`, then
`modaccel -Repo <repo> port -Fix -Log <original failing log> -Task runClientGameTest -Max 1`.
The test mod stays in the gametest source set and is excluded from the shipped mod.

### Can this repo even build at the target?

Before any code work, ask the toolbox:

```
.\modaccel port -Check <repo> [-To <minecraft version>]
```

It reads the Gradle wrapper, picks the JDK that wrapper actually needs (17 below 8.5, 21 for 8.5-8.14,
25 for 9+), detects the loader (Fabric, NeoForge, ForgeGradle), reads the version pins (including the
active Stonecutter node), then **proves** it by running `gradlew help` with that JDK. Measured:

| Repo | Wrapper | JDK | Proof | Verdict |
|---|---|---|---|---|
| Diorama | 9.7.1 | 25 | `gradlew help` PASS **9.6s** | READY for 26.3 |
| MineColonies `version/1.20.4` | 8.8.0 | 21 | FAIL **29.4s**: `Could not resolve net.neoforged.gradle:userdev:7.0.+` | BLOCKED before any code |
| MineColonies `version/main` | 8.1.1 | 17 | PASS **88.7s** | OUT OF SCOPE (Forge) for a Fabric target |
| Traverse 1.21.6 | 8.12.1 | 21 | FAIL **11.1s**: `Could not resolve com.terraformersmc.terraform-api:...` (their maven answered 521) | BLOCKED before code |

That is the difference between finding a blocker in half a minute and finding it after a 328-second
build plus a failed version bump.

**JDK note:** every one of these mods needs a Gradle 8.11-8.14 wrapper, which refuses JDK 25
(`Unsupported class file major version 69`). The benchmark ran on JDK 21. If you install this
harness into an older mod, point Gradle at JDK 21 until the mod's Gradle is 9.x.

## Two big real repos, as they are

Run against a large map mod (Diorama, Stonecutter, Kotlin DSL) and a very popular open-source mod
(MineColonies, ForgeGradle):

| Repo | Build system | Baseline | Bump | Install |
|---|---|---|---|---|
| Diorama | Stonecutter + Kotlin DSL | `:26.3:build -x test` PASS 204.5s | no newer node exists; a fabric-api pin bump PASS 8.2s | **installs now**: active node detected, Loom 1.18 block appended to the node script, bridge in `src/main/java`, fast lane answers in **2.3s** (0 pure tests found — they tag none) |
| MineColonies | ForgeGradle 8.1.1, NeoGradle `7.0.+` | `build -x test` PASS 328.3s on JDK 17 | FAIL at 56s before javac: NeoGradle 7.0.192 requires Gradle 8.13 | installs on the Forge lane: `labClasspath` block, bridge in `.modaccel\bridge-forge.jar`, 2102 sources fast in **2.3s**, then a warm client via one captured launch |

The world tier also stopped lying on big repos: if a repo ships its own gametest suite (Diorama's
runs about ten minutes), the tier now says *"skipped: nothing claimed"* instead of running it and
reporting a pass. And because a cleaned Gradle cache used to leave the recorded classpath pointing
at deleted jars, `modaccel` now notices missing entries and re-asks Gradle once.

Two honest conclusions: a big repo still fails at its *toolchain* before its code, which is exactly
what `port -Log` reports (it prints "the build never reached the compiler" and the first matching
Gradle lines instead of a blank page); and a lane that was never installed (Forge here, before the
Forge bridge) is a wrong label, not a limitation of the mod.

---

## Benchmarks

Measured on a Ryzen 9 3900X, Windows 11, warm. Not quoted from anywhere.

| Way of checking a change | Per check | Notes |
|---|---|---|
| **lab - logic** | **1-3s** | no Minecraft process at all |
| **lab - screen** | **1-3s** | real framebuffer capture at 854x480; you look at the PNG, nothing compares it |
| **lab - block behaviour** | **2-4s** | real world, real ticker |
| **lab - mixin body** | **13-30s** | `world` tier: mixins are applied at start-up, not redefined in place |
| lab - new registration | 15-30s | registry only exists at start-up |
| lab - clean client proof | 30-60s | fresh client + world + screenshots |
| `gradlew runClient` + manual play | 1 minute or more | measured: 15s to atlases here, plus world load and your clicking |
| `runClientGameTest` | 28-40s | fresh client every run |
| Fabric **server** GameTest | 15-30s | headless, no UI, no screenshot; the client GameTest row above does capture screenshots |
| JRebel | paid, unknown | "Mixin support is unreliable in practice" - unverified, I ran no test of it |
| HotSwapAgent / DCEVM | no Minecraft workflow | the standalone DCEVM builds stop at Java 11; JetBrains Runtime carries enhanced redefinition on 17/21/25 and this harness runs it on JBR 25 - the JVM trick is not the new part, the orchestration around it is |
| KubeJS-style `/reload` | seconds, data only | cannot add real blocks or Java behaviour |

Bottom line: a manual check is a client boot (15-25s) plus a world plus your clicking. Here
everything except new content and mixin edits is 1-4 seconds; a mixin edit is a `world` run
(13-30s). These are this machine's numbers, not a head-to-head (see `ROADMAP.md` item 6).

---

## Using it for your own mod

### Checklist: swapping in your mod

- [ ] Replace `src/main/java/com/prismlab` and `src/client/java/com/prismlab` with your
      sources; replace `src/main/resources/fabric.mod.json` and assets.
- [ ] Keep `src/test` and `src/gametest` - they are the harness.
- [ ] Point the `depends` in `src/gametest/resources/fabric.mod.json` at your mod id.
- [ ] Rename the lab's screen/control classes or delete them, and remove the demo mixin.
- [ ] Run `.\modaccel` - lint must pass before you trust anything else.
- [ ] Run `.\modaccel world` once: it proves your registrations and mixins load.
- [ ] Run `.\modaccel look` once and look at the four PNGs it prints.

### Checklist: every change

- [ ] Save.
- [ ] `.\modaccel` - logic and lint (1-3s).
- [ ] Touched a screen? `.\modaccel ui`.
- [ ] Touched a block, item or tick? `.\modaccel node`.
- [ ] Touched a mixin? `.\modaccel world` (mixins apply at start-up; the body is not re-applied in place).
- [ ] Added a block/item/registry entry? `.\modaccel world` once, then back to the fast loops.
- [ ] Before calling it done: `.\modaccel look`, then look at the PNG.

### Checklist: adding a test

- [ ] Pure logic: a JUnit test in `src/test`, tagged `@Tag("pure")`, must not touch a
      Minecraft class.
- [ ] Anything with a registry, world, item stack or screen: a `@GameTest` in
      `src/gametest/java`, or an assertion inside the client test.
- [ ] For a screen: add it to `PrismClientGameTest`, capture, then look at the PNG.

### Checklist: new content (a new block or item)

- [ ] Register it and give it a `ResourceKey` with your mod id.
- [ ] Add blockstate, model, item model, texture, lang entry, loot table.
- [ ] `.\modaccel` - lint catches the missing piece in under a second.
- [ ] `.\modaccel world` - proves it loads in a real server.
- [ ] `.\modaccel ui` / `.\modaccel node` for anything it does.

---

## Honest limitations

- **Behaviour and pixels need Minecraft running.** Your mod is Minecraft code; only Minecraft
  can run it. The client is hidden and the server is headless, so nothing appears on screen,
  but a process exists. There is no way to prove a screen or a block works without one.
- **New registrations still cost a boot** (15-30s). Java cannot add a new class to a running
  JVM's loader, and Minecraft's registry is frozen after start-up (26.3 has no unfreeze). Method
  bodies, added fields/methods and screen code all hot-swap; a new block/item id
  does not. The relaunch is hidden and reuses the lab world, so you never see or click anything.
- **NeoForge 1.21.1 is a separate node, now with hot reload.** It launches from ModDevGradle's
  generated recipe instead of Gradle (cold start 18.4s, screen check 3.2s) and the agent redefines
  classes, so a screen edit lands in **1.5s** without a restart; edits that add a field or method
  report `needs restart`, and `.\modaccel restart` does stop -> compile -> start in one command (49s).
  Two things it taught: NeoForge freezes vanilla registries before mods construct, so registration
  must use `DeferredRegister`; and `BaseEntityBlock` defaults to `INVISIBLE` on 1.21.1, so the block
  ticked and passed the probe while being invisible - only the screenshot caught it.
- **Mods that are not ours.** `install.ps1` also drops a 10 KB bridge jar (`.modaccel\bridge.jar`)
  into a NeoForge repo and adds it through `-Dfml.modFolders`, so a client that belongs to someone
  else's mod answers `root`, `screenshot` and `ui <Screen>` without a line of their code changing.
  Verified on a copy of the node with its own control mod removed: PNG in 4.3s, every answer from
  the jar. Forge 1.20.1 uses a Forge build of that jar: `install.ps1` copies it to
  `.modaccel\bridge-forge.jar`, `modaccel` puts it in the repo's `mods\` and replays the captured
  launch, and the client answers the same questions (MineColonies: 854x480 title PNG, cold 58.6s,
  warm 0.5s).
- **The Forge lane is automatic, but has no hot reload.** Install once, then
  `tools\capture-launch.ps1` once per repo; after that `.\modaccel ui` copies the bridge into
  `mods\`, replays `.modaccel\forge.args` and waits for the socket itself. A code edit means a new
  client (58.6s cold); only a repeat check on the running client is 0.5s.
- **What a jar diff cannot see.** Renames and removals are proven from the jars (stable ids);
  changes where the name survives but the contract doesn't (the render-state refactor) are matched
  to a written recipe in `knowledge\structural.json`, which the error report cites by name.
- **1.21.11 now has hot reload too**: a screen edit answers in 1.75-2.24s and a main-class edit in
  2.0-2.6s, against 28s for a restart. Edits to the harness itself, new fields/methods, registrations
  and mixins still need a restart there.
- **One lab client per machine.** The control port is fixed; a second repo refuses to use the
  first repo's client (it tells you to run `.\modaccel stop` there) instead of testing the wrong mod.
- **Hot reload limits.** Method bodies, added fields/methods and screen code hot-swap. A mixin
  body needs `.\modaccel world`, and changing a class hierarchy, a mixin's target list, or
  `fabric.mod.json` needs a restart.
- **Fabric, 26.3, verified.** The harness is version-agnostic; the demo mod is not. For
  1.21.11 or older also switch the Loom plugin to `net.fabricmc.fabric-loom-remap` and use
  yarn mappings. NeoForge and Forge are their own lanes, not a config change.
- **Which tier can miss what.** `bench\fault-matrix.md` injects seven faults one at a time and
  records what each tier did. Two harness bugs it found are fixed: `world` false-passed from a stale
  launch argFile, and `ui` could be hijacked by a bridge jar. Since then a dead `@Shadow`/`@Inject`
  fails lint, `world` checks blockstates, item models and lang keys inside the live server, `node`
  asserts the exact expected value (and re-probes a drifting reading), `ui` prints only PNGs this
  run wrote, and `mp` refuses a stale server instead of trusting it. Remaining gaps are in that
  file: a warm tier still cannot see a fault that was present before its process started, and a
  render exception kills the shared client while `ui` reports a transport error rather than the
  cause.
- **Scan recall and fix rules.** `port -Check` sees exact removals in your own source; renames and
  member use need a reference index before the first build. `port -Fix` covers 11 rule families
  (`knowledge\fixes.json`); removed or reshaped classes still need a human or a new rule.

---

## Where things live

```
lab.ps1                 the commands
lab.cmd                 same thing for double-click / cmd
tools/LabDaemon.java    resident JVM for logic tests
tools/LabAgent.java     -javaagent: class redefinition over a socket
tools/lint.ps1          registration and asset lint
src/main/java           the mod
src/client/java         client code, the screen, the hot-reload control socket
src/test/java           pure logic tests (tag "pure" = no Minecraft classes)
src/gametest/java       world tests (@GameTest) and the client screenshot test
.modaccel/                   runtime state: pids, ports, classpath, hidden client log
.runs/                  every run with its timing, plus copies of screenshots
```

If something goes wrong: `.\modaccel doctor` shows versions and what is running, `.\modaccel logs` shows
recent runs, `.modaccel/client.log` is the hidden client, `.runs/logs` has each tier's full output.
`.\modaccel stop` clears a stuck client or daemon.















## The porting toolbox, end to end

| Command | Answers | Measured |
|---|---|---|
| `port -Check <repo> -To <mc>` | can this build at the target, and how much of the source already breaks | Diorama READY 5.2s; MineColonies BLOCKED 29.4s; Artifacts "2 class references removed in 1.21.5" |
| `port -Api <old jar> <new jar>` | what changed between two Minecraft versions | 1.21.4->1.21.5: 205 removed, 25 moved, 3345 members, 921 signatures, 351 replacements (222 stable-id) in 1.9s |
| `port -Log <log> -To <mc>` | which of your errors are known, and what replaced them | "BakedModel -> BlockStateModel (stable-id)"; "CoreShaders removed (jar diff)"; recipes for semantic breaks |
| `port -Fix -Log <log>` | apply the known families and rebuild | Traverse 68 -> 0 in 2 rounds, 7.2s; 111 getter sites in 4.0s |
| `restart` | stop, compile, start after a schema change | 49s on the NeoForge node, one command |

Rename links come from stable ids (intermediary names are constant across versions), so a
replacement is proven rather than guessed. If the two jars are not in the same mapping family,
`-Api` says so (this session: 1.21.5 layered against 1.21.6 yarn printed "only 1% of old classes
exist under the same name" and labelled all 717 replacements heuristic) instead of pretending the
links are proven. Changes no jar diff can see - the render-state refactor,
the item-model split - are matched to a written recipe in `knowledge/structural.json`, which the
error report cites by name.




