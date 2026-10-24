# What already exists for the problems this harness solves

Sourced comparison, written 2026-10-23. Every claim below is either quoted from the linked
page or marked **unverified**. Pages are cited with their own publication date where the page
states one; otherwise the date is the day this file read it (2026-10-23), because vendor docs
change silently.

Scope: this compares against what `README.md` claims — a fast local loop (logic, screen,
block behaviour), a warm-client hot reload, in-game/multiplayer assertions, version porting,
and loop timing. It is not a survey of every Minecraft tool.

---

## 1. Hot reload / hot swap

### 1.1 Fabric Loom and IDE debug hotswap (the documented baseline)

What it is: Loom is the Gradle plugin that sets up a deobfuscated dev environment and the
`runClient` / `runServer` run configs (`https://docs.fabricmc.net/develop/loom`, accessed
2026-10-23). Fabric's own IDE guide documents what you get by running the client in **debug**
mode: method bodies swap, but "You can't add or remove methods / You can't change method
parameters / You can't add or remove fields". Swapping the JDK for JetBrains Runtime plus
`-XX:+AllowEnhancedClassRedefinition` lifts most of that
(`https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game`,
accessed 2026-10-23).

Limits: it is a manual IDE action (Compile + Reload Changed Classes, or the debugger's hotswap
button) inside a client a human is looking at. Nothing captures a screenshot or an assertion.
Mixin bodies need a separate `-javaagent` for the Mixin library, and Fabric's wiki is explicit
that "Mixins can't be hot-swapped in a default Fabric environment"
(`https://wiki.fabricmc.net/tutorial:mixin_hotswaps`, accessed 2026-10-23). Assets and data
are a `/reload`, which reloads datapack content only
(`https://wiki.fabricmc.net/tutorial:hotswapping`, accessed 2026-10-23).

### 1.2 JetBrains Runtime enhanced class redefinition (DCEVM)

What it is: JBR is a fork of OpenJDK that carries "Enhanced class re-definition with the DCEVM
technology ... this feature needs to be explicitly enabled with
`-XX:+AllowEnhancedClassRedefinition`" (`https://github.com/JetBrains/JetBrainsRuntime`,
accessed 2026-10-23). The mechanism is JEP 159: add/remove methods, add/remove fields, add
supertypes; removing supertypes stays prohibited (`https://openjdk.org/jeps/159`).

Limits: it is a JVM capability, not a workflow. It does not know about Minecraft, registries,
mixins, screens or screenshots. It also has a live crash class of its own: JBR-7442 removed a
flag-driven capability override because "Retroactively changing the capability does not
re-initialize the existing classes, leading to a state mismatch and a JVM crash" (fixed in
jbr21.509.11, `https://youtrack.jetbrains.com/projects/JBR/issues/JBR-7442`, accessed
2026-10-23).

### 1.3 HotSwapAgent / DCEVM

What it is: an agent plus patches for "unlimited runtime class and resource redefinition" —
fields, methods, annotations, anonymous classes, enum values; "The only unsupported operation
is hierarchy change" (`https://github.com/HotswapProjects/HotswapAgent`, accessed 2026-10-23).
Its plugin set targets Spring/Hibernate/Tomcat-style frameworks, not Minecraft.
Maturity: the standalone DCEVM builds cover Java 8 and 11; for 17/21/25 the README tells you to
use JBR and copy `hotswap-agent.jar` in by hand.

Limit that matters here: no Minecraft plugin, so redefining a class does not re-open a screen,
re-place a block, or tell you whether the result is right.

### 1.4 Mixin hot reload

What it is: the Mixin library can be attached as a `-javaagent` so mixin method bodies can be
redefined in a debug session (Fabric wiki `tutorial:mixin_hotswaps`, and the Fabric IDE guide
above). Limits: bodies only; changing a mixin's target list or adding an injection is a
restart. There is a long-standing report of mixin + DCEVM hanging on hotswap
(`https://github.com/SpongePowered/Mixin/issues/592`, accessed 2026-10-23). This matters
because the harness's README used to credit mixin bodies reloading "in place" while also requiring
`.\modaccel world` for a mixin body. Resolved 2026-10-23 in favour of `world`: the README, its
benchmark table and this file now all say a mixin edit needs the `world` tier (13-30s), with no
in-place claim. Nothing in the repo measures an in-place mixin redefinition.

### 1.5 ModDevGradle / NeoGradle run configs

What they are: NeoForge's Gradle plugins. ModDevGradle defines run types `client`, `server`,
`data`, `gameTestServer` and lets you add JVM args such as
`-XX:+AllowEnhancedClassRedefinition` yourself
(`https://docs.neoforged.net/toolchain/docs/plugins/mdg`, accessed 2026-10-23). ModDevGradle 2
also generates VS Code launch configs and `build/moddev/runXxx.{cmd|sh}` launch scripts for
external debuggers/profilers (published 2025-01-02,
`https://neoforged.net/news/moddevgradle2`). NeoGradle offers the same plus optional RenderDoc
capture on client runs (`https://github.com/neoforged/NeoGradle`, accessed 2026-10-23).

Limits: they launch the game; they do not keep it warm between edits, and neither ships a
redefinition agent or an assertion/screenshot step.

### 1.6 IntelliJ "Minecraft Development" plugin

What it is: IDE support — project import, mixin inspections, run configurations, `genSources`
(`https://github.com/minecraft-dev/MinecraftDev`, supports IDE 2025.3–2026.3 as of this read).
It is not a hot-reload implementation; it defers to the debugger/JBR story above.
**Unverified:** I found no page claiming it provides its own redefinition or screenshot check.

### 1.7 JRebel

Paid commercial hot reload, still sold, still the most mature per HotSwapAgent's own comparison
("JRebel is currently more mature and contains more plugins",
`https://github.com/HotswapProjects/HotswapAgent`; general hotswap history at
`https://www.jrebel.com/blog/java-hotswap-guide`). No Minecraft-specific support published;
the harness README's "Mixin support is unreliable in practice" is **unverified** — the harness
quotes no test for it.

---

## 2. Automated in-game testing

### 2.1 Vanilla GameTest

What it is: Mojang's E2E framework — "Each test contains a structure, parameters for how the
test gets executed, and optional code" (`https://minecraft.wiki/w/GameTest`, accessed
2026-10-23), run from the server with `/test runall`; the scripting form lives in
`@minecraft/server-gametest` for Bedrock
(`https://www.minecraft.net/en-us/creator/article/get-started-gametest-framework`).
Limits: server-side world assertions, structure-block based, no client rendering, no
screenshot. A boot per run.

### 2.2 Fabric: server GameTest **and client GameTest**

What it is: Fabric's automated-testing guide covers both. Server tests via
`@GameTest` + `fabric-gametest`; client tests via `FabricClientGameTest` and the
`runClientGameTest` / `runProductionClientGameTest` tasks, and the docs' CI example uploads
`build/run/clientGameTest/screenshots`
(`https://docs.fabricmc.net/develop/automatic-testing`, accessed 2026-10-23). The reference
example calls `context.takeScreenshot("example-mod-singleplayer-test")`
(`https://github.com/FabricMC/fabric-docs/blob/-/reference/latest/src/gametest/java/com/example/docs/ExampleModClientGameTest.java`,
accessed 2026-10-23).

This is the strongest existing alternative in this category: officially maintained, CI-ready,
cross-platform, and it does produce pictures. Limits: a fresh client per run (the harness
measured 28–40s for `runClientGameTest`, README Benchmarks table — self-measured, not
reproduced from Fabric docs), no warm reuse between edits, screenshots saved but nothing
compares them.

### 2.3 NeoForge GameTest

What it is: `@GameTest` on NeoForge's test framework, with "only the `client` and
`gameTestServer` run configurations have Game Tests enabled" by default, or
`neoforge.enableGameTest = true` on others (`https://docs.neoforged.net/docs/misc/gametest`,
accessed 2026-10-23); the framework also supports dynamic tests, custom templates and mock
players (`https://github.com/neoforged/NeoForge/blob/26.1.x/docs/NEOGAMETESTS.md`, accessed
2026-10-23). Client-side gametests are not official: a third party ships an "Unofficial
NeoForge port of the Fabric Client GameTest API"
(`https://github.com/Wurst-Imperium/neoforge-client-gametest-api`, accessed 2026-10-23).

### 2.4 HeadlessMC

What it is: a command-line launcher that runs the client headlessly and can drive it — its
docs show `gui` dumping screen widgets/text/buttons and `click <id>` acting on them
(`https://headlesshq.github.io/headlessmc/specifics`, accessed 2026-10-23). MIT, ~402 stars at
time of writing (`https://github.com/headlesshq/headlessmc`).
Limits: it is a launcher and remote-control layer, not a test runner; it has no concept of
"this edit failed", no lint, no port report, and the version-specific command mod is a separate
project per version.

### 2.5 MC-Runtime-Test (headless client in CI)

What it is: a GitHub Action that launches a headless client via HeadlessMC, joins a world,
optionally runs `/test runall`, and fails the job on failures; it covers Forge/Fabric/NeoForge
from 1.7.10 through 26.3 and supports `-DMcRuntimeGameTestMinExpectedGameTests=1` to stop an
empty run from passing (`https://github.com/headlesshq/mc-runtime-test`, accessed 2026-10-23).
It also states plainly that "Forge and NeoForge GameTest discovery may require additional
setup, hacks, or other modifications".
Limits: Linux/Xvfb-oriented CI action, no warm process to reuse, no edit→check loop, no porting,
no screenshot review step.

### 2.6 Screenshot-diff / golden-image frameworks

**Unverified.** I found no maintained Minecraft-specific golden-image/pixel-diff framework in
this survey. Fabric client gametests save screenshots but the docs' CI example uploads them as
artifacts rather than comparing them. Treat "nobody does pixel diffs for Minecraft" as an
unproven statement; treat "the harness deliberately does none either" as fact (see §5).

---

## 3. Version porting / updating

### 3.1 Remappers (the plumbing everyone uses)

- **ART / AutoRenamingTool** (NeoForge): remaps/renames jar contents with extensible
  transformations (`https://github.com/neoforged/autorenamingtool`, accessed 2026-10-23).
- **tiny-remapper** (Fabric): Tiny-format jar remapping
  (`https://github.com/FabricMC/tiny-remapper`, accessed 2026-10-23).
- **Intermediary mappings**: stable ids give "binary compatible across multiple versions ...
  guaranteed only for the parts of the game that are unchanged between versions"
  (`https://wiki.fabricmc.net/tutorial:mappings`, accessed 2026-10-23). This is the same idea
  the harness's `-Api` leans on when it calls a replacement "stable-id" rather than guessed.

### 3.2 Human-written migration guides

NeoForge's version primers (e.g. the 1.19.3→1.19.4 Forge primer,
`https://docs.neoforged.net/primer/docs/1.19.4/forge`) and community guides such as Mcjty's
Forge→NeoForge porting notes (`https://mcjty.eu/docs/1.20.4_neo/porting`, accessed 2026-10-23).
Limits: prose, per-hop, and you do the grepping.

### 3.3 mc-mod-porter / "Auto-Porter"

What it is: a GUI+CLI tool that ports a mod folder across supported hops (1.16 → 26.3) using a
per-hop knowledge base and rename tables, then runs the build and reports what is left; it
states "What it doesn't do: Logic changes" and is labelled Beta, MIT+Commons Clause, Java 21+
(`https://github.com/reqsery/mc-mod-porter`, v1.3.0-beta, accessed 2026-10-23). Fabric and
NeoForge only.
This is the closest existing thing to `modaccel port`, and on one axis it is ahead: it covers
many hops' worth of curated API notes, not just a jar diff plus 11 rule families.

### 3.4 Stonecutter (multi-version, not porting)

A Gradle plugin for source preprocessing/conditional code across target versions
(`https://codeberg.org/stonecutter/stonecutter`; 0.9.8 created 31 Aug 2026 on the plugin
portal). It keeps one codebase compiling for several versions; it does not tell you what broke
or fix it.

### 3.5 AI mod-porting tools

**Unverified.** I found no published LLM-based Minecraft mod porting tool with evidence of use.
The harness's `port` is not AI either: it is a class/member jar diff plus
`knowledge\fixes.json` rules plus written structural recipes (README "The porting toolbox",
PLAN queue item 3). Any framing of this as "AI porting" would be inventing a capability.

---

## 4. Build / benchmark tooling (the part that overlaps "how long is my loop")

- **spark** — CPU profiler for clients/servers/proxies; tick monitor and
  `/spark profiler --only-ticks-over <ms>` for lag spikes
  (`https://github.com/lucko/spark`, `https://spark.lucko.me/docs/Command-Usage`, accessed
  2026-10-23). Answers "why is the game slow at runtime", not "how long does my edit→check
  loop take".
- **JFR** — in-JVM profiling/event recording, "integrated into the Java Virtual Machine ...
  performance impact is less than one percent" at default settings
  (`https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm`, accessed
  2026-10-23). Same mismatch: it profiles the JVM, and the loop's cost is mostly process
  startup and Gradle, which JFR on the game JVM will not attribute.
- **Gradle Build Scan / Develocity** — a per-build timeline with task timings and cache
  decisions, published to a server; `./gradlew build --scan`
  (`https://docs.gradle.org/current/userguide/inspect.html`, accessed 2026-10-23). This is the
  honest existing tool for "my build is slow", and it is better than the harness at that: it
  attributes a build to tasks and cache misses, where the harness records one wall-clock number
  per tier (`.runs/index.jsonl`, `bench/data.json`).
- What the harness adds here is narrow and real: it times the *loop*, not the build — e.g.
  `gradlew build -x test` 328.3s vs one-file edit 2.3s on MineColonies (README, lines 30–35).

---

## 5. Side by side

| Problem | Existing options | What this harness does differently | Where the harness is worse |
|---|---|---|---|
| "Did this logic change work?" | Plain JUnit in the IDE (~1s anywhere); `gradlew test`; Fabric/NeoForge GameTest (boot) | Resident JVM compiles with `javac` and runs `@Tag("pure")` tests with no Gradle or Minecraft bootstrap, wired to a lint pass in the same command | Technically nothing new: any Java dev's IDE test run is as fast. Value is zero-config-in-a-mod-repo, not speed over JUnit. Windows/PowerShell only; no CI story |
| "Does this screen look right?" | Live debug hotswap + your eyes (Fabric IDE guide); Fabric **client** GameTest with `takeScreenshot` and CI artifact upload; HeadlessMC `gui`/screenshot | Hidden warm client, recompile + redefine, capture 854x480 PNG, print only PNGs written this run; no golden to maintain | Fabric's client gametest is official, cross-platform and CI-native. The harness has no CI integration and no published release (MIT licensed since 2026-10-23); and no pixel comparison at all, so it cannot catch a regression — only a human/agent can |
| "Does this block/item tick right in a live world?" | GameTest with a structure + `/test runall`; `/spark` for perf only | Scenario steps against a warm client: `place=` / `wait=` ticks / `assert=<method>` / `settle` / `capture=`; 2–4s warm | GameTest is official, deterministic per-run, and runs headless in CI across many versions. The harness's warm tiers can miss a fault that predates the process (its own `bench\fault-matrix.md`) |
| "Do several cases still pass?" | GameTest suite (`/test runall`), JUnit suites | `modaccel suite` — named cases in a text file against one warm client, non-zero exit on failure (2 cases in 4.9s warm, README) | Suite format is bespoke; no JUnit XML/report integration, so CI dashboards cannot consume it |
| "Did I break a mixin / asset / registration?" | Compile errors; runtime crash at boot; Mixin's own audit output | Lint in 0.17s: dead `@Shadow`/`@Inject` targets fail the run; checks blockstates, item models, lang keys in the live server | Fabric/NeoForge do their own mixin validation at load; the harness's edge is *when* it runs (before boot), not *what* it checks. Coverage is 1 mixin in the demo; not proven on a large mod's mixin set |
| "Can this repo even build at the target version?" | `gradlew help` / a full build and read the error; mc-mod-porter's version checks | `port -Check` picks the JDK from the wrapper, detects loader + pins, then **proves** it with `gradlew help` (READY 9.6s; BLOCKED 29.4s) | It is a shell script per repo shape; mc-mod-porter ships a maintained per-hop knowledge base and a GUI. Windows-only, no pick-up-and-run on Linux/macOS |
| "What do I change to port?" | ART/tiny-remapper + primers + Mcjty-style guides; mc-mod-porter knowledge base + rename tables | `-Api` jar diff with stable-id-proven renames, `-Log` compiler-error grouping, `-Fix` 11 rule families, written structural recipes | Rules cover 11 families; removed classes and the render-state refactor still need a human (PLAN item 2). No AI, despite how "port" may read. mc-mod-porter covers more hops' API notes; neither is authoritative |
| "How long is my loop / why slow?" | spark (runtime), JFR (JVM), Build Scan/Develocity (build) | One wall-clock number per tier in `.runs/index.jsonl`, charted from `bench/data.json` | Build Scan is strictly better at build attribution and works on any platform; the harness's numbers are single-machine (Ryzen 9 3900X, Windows 11) and self-published |
| Multiplayer-only bugs | Nothing found in this survey that scripts a dedicated server + client assertion | `mp` starts a dedicated server + hidden client and asserts what the server actually sent; caught a real 26.3 empty-update-tag bug (4.1s warm) | Single-machine, fixed ports, one lab client per machine (README limits). No CI path |
| Forge (pre-NeoForge) | GameTest on Forge; MC-Runtime-Test covers Forge 1.7.10–26.3 in CI | Fast lane on ForgeGradle without Gradle (2.3s on 2,102 sources), warm client from one captured launch, repeat check 0.5s | **No hot reload on Forge**; one manual `capture-launch.ps1` per repo; ForgeGradle rewrites its command line on dependency changes (README) |

---

## 6. Where we are actually ahead (each tied to a number in this repo)

1. **Edit→fail in seconds on a real, large Gradle repo.** MineColonies 1.20.1, 2,102 sources:
   their own `gradlew build -x test` is 328.3s, the first `.\modaccel fast` is 23.9s, and one
   file edit is **2.3s**; a deliberately broken file FAILs in **1.3s** with file and line
   (README, "ForgeGradle repos get the fast lane" table). No existing tool in §1–§4 reports a
   per-edit number on a mod of that size.
2. **A warm, picturable screen check.** Fabric screen **1.5s**, NeoForge screen **3.2s**,
   NeoForge hot reload **1.5s**, 1.21.11 screen edit **1.75–2.24s** — against 28s for a restart
   on that node (README §Limitations) and `runClientGameTest` **28–40s** in the repo's own
   benchmark table. Fabric's client gametest is the strongest alternative and it still pays a
   fresh client per run.
3. **Port triage in seconds, with proof attached.** `port -Api` on 1.21.4→1.21.5 in **1.9s**:
   205 removed, 25 moved, 3,345 members, 921 signatures, 351 replacements of which 222 are
   stable-id (README "porting toolbox"; PLAN). `port -Check` returns READY in **9.6s**
   (Diorama) or BLOCKED in **29.4s** (MineColonies) instead of after a 328s build.
4. **Automatic fixes that are measured, not claimed.** `port -Fix`: Traverse 68 → 0 errors in
   2 rounds / **7.2s**; Traveler's Backpack 111 getter sites in 1 round / **4.0s**, then it
   stops honestly at the 6 classes no rule covers (README, PLAN).
5. **A mixin check that found a real bug for 0.17s.** The lint mixin audit found Traveler's
   Backpack's dead `@Shadow` (README, "the audit costs 0.17s. It found Traveler's Backpack's
   dead `@Shadow`").
6. **Multiplayer assertion, which nothing else here does.** `mp` proved a 26.3 block entity
   never reached a second client (empty default update tag, fixed with
   `getUpdatePacket()`/`getUpdateTag()`) in **4.1s** warm — a bug singleplayer structurally
   cannot see (README "Multiplayer"; AGENTS.md).

---

## 7. Do not claim

- **"GameTest gives you no picture at all."** False as written. Fabric ships an official
  *client* GameTest API that captures screenshots and uploads them from CI
  (`docs.fabricmc.net/develop/automatic-testing`). Only the *server* GameTest has no picture.
  Fixed in `README.md` on 2026-10-23: it now separates the server form (boot, no picture) from the
  client form (fresh client per run, and it does screenshot).
- **"Hot reload is new / unique to this harness."** JBR's enhanced redefinition is documented
  and shipped; HotSwapAgent has done structural redefinition for years; Fabric's IDE guide
  documents the same flag and the mixin `-javaagent` (`github.com/JetBrains/JetBrainsRuntime`,
  `github.com/HotswapProjects/HotswapAgent`, `docs.fabricmc.net` IDE guide). What is new here
  is the orchestration — hidden warm client, socket redefinition, screenshot, scenario steps —
  not the JVM trick.
- **"DCEVM stops at Java 11 / unusable on Java 25."** The standalone DCEVM project does; JBR
  ships DCEVM for 17/21/25 and the harness itself runs
  `-XX:+AllowEnhancedClassRedefinition` on JBR 25 (HotswapAgent README's Java 17/21/25
  instructions; AGENTS.md). Corrected in the README's benchmark row on 2026-10-23.
- **"Mixin bodies hot-reload in place" unless you actually measure it.** The README used to say
  both that mixin method bodies reload and that a mixin body needs `.\modaccel world`. Resolved in
  favour of `world` on 2026-10-23 — every mention now says the `world` tier (13-30s) and the
  benchmark row no longer claims an in-place body reload. Nothing here measures one, so do not
  claim it again without a number.
- **"AI porting."** There is no AI here and none was found in the alternatives; `port` is a jar
  diff plus 11 rule families plus 3 written recipes (PLAN queue items 2–3).
- **"Fastest" as a general claim.** The comparison numbers for alternatives
  (`runClientGameTest` 28–40s, `gradlew runClient` 15s to atlases) are this repo's own
  measurements on one machine; no third party has reproduced them and no head-to-head against
  Fabric client gametest is published. Say "measured here: X" and never "the fastest"; the
  head-to-head is roadmap item 6.
- **"Cross-platform" as a blanket claim.** The portable commands run on Windows, Linux and macOS,
  and all three pass the portable checks in hosted CI; game checks and the no-Java install are
  verified on Windows only, and Linux/macOS game rendering has not been exercised by hand.
- **"It replaces GameTest."** The world tier *is* GameTest; and a repo that ships its own
  gametest suite (Diorama, ~10 min) is skipped honestly rather than run (README §Two big repos).
- **"No pixel goldens" as a strength.** It is a deliberate trade that removes regression
  detection: nothing in this loop can tell you a screen changed for the worse since last week.
  Fabric's screenshot artifacts at least give you history.
- **"Mature enough to adopt."** `0.1.0-beta.1` is published with checksums and the portable
  workflow is green on Windows, Linux and macOS, but this is still one person's lab, not an
  ecosystem tool; FabricMC and NeoForged own the tooling in §1–§3. Say "a beta that works on
  real mods", not "a tool the ecosystem uses". What remains is ordered in `ROADMAP.md`.
