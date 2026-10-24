# Roadmap

Current as of 2026-10-24. The older gap sizing further down is history; sizes and status there
describe the earlier Windows-only harness, not the portable tool.

## Goal

Any Minecraft version to any other, and any loader to any other. Version changes and loader
changes are separate jobs with separate records: a version port keeps the loader, a loader port
keeps the game version, and a mod that needs both is two steps. The verified path today is one
hop inside one loader.

## What works now

**Changing the Minecraft version of a mod, one loader at a time.** The tool detects the loader and
ports within it; every attempt ends in either a build plus mixin audit or a named stop.

- Fabric: AppleSkin 1.21.5 ↔ 1.21.6 ports and backports; F3Nope 1.21.5 → 1.21.6 built, audited and
  loaded in the real client. `--expect-change` caught an overlay that was invisible despite a passing
  build; `--save-roundtrip` proves a save reloads at the same position.
- NeoForge: ZombieApocalypseAddon (71 files) 1.21.1 → 1.21.4 with no hand edits — pinned versions and
  declared game range rewritten, 19 API errors repaired by rules, 331s from a fresh copy, 73s warm.
  The ported jar loads in a 1.21.4 client and its `/za` command runs.
- Five minors (1.21.1 → 1.21.6) on the same mod: 38 reported errors reduced to 9 by rules; the rest
  is semantic (see step 4). Jade 1.21.1 → 1.21.6 stops at 98 errors in the 1.21.6 GUI rewrite.
- Repeat cost for an already-visited hop is 4.6s; a first visit is ~40s warm plus download/decompile.

**Changing the loader is not built.** The tool never converts Fabric to NeoForge or the reverse, and
Forge target setup is manual. The goal is any loader to any loader; that capability needs different
build scripts, entrypoints, mixin targets and API translation, so it is sized as its own step below.

**Checks, install and platforms.** Build and mixin audit run on every port; UI and world checks drive
the real game and save inspected frame captures. Windows installs without Java (verified JRE/JDK
download); Linux has command and build evidence only; macOS is unverified. Multi-project Stonecutter
repos are listed node by node and a node builds, but repairs must land in the shared annotated
sources, which is not wired yet. Kotlin sources are untested.

## What's left, in order

1. **Publish the beta.** Done 2026-10-24: `v0.1.0-beta.1` is published with the portable ZIP and
   JAR plus SHA-256 files, and the README install step points at it. Check: on a machine with no
   Java, download the release, run `support`, and port F3Nope 1.21.5 → 1.21.6 with the same numbers.
2. **Make the platform claim true or narrower.** Run one Linux game check (Xvfb path) and one macOS
   smoke run, or label both experimental in README and `support`. Check: the claim in `support`
   matches what was run, with the receipt named.
3. **Write the stopping rules where users read them.** README and `support` should say what a stop
   means per area: multi-project and Stonecutter repairs, Kotlin sources, Forge, and the semantic
   families below. Check: every `support` feature line links to a named limitation or a receipt.
4. **Close the 1.21.6 storage families.** `SavedData.Factory` became `SavedDataType` plus a codec per
   data class, and owner/saddling moved to `OwnableEntity`. Deliver either a rule family (where the
   shape is regular) or a guided recipe with a named stop, and prove it on the same mod reaching a
   clean build. Check: a fresh 1.21.1 → 1.21.6 copy builds with zero hand edits, or stops only on
   documented items.
5. **Cover the 1.21.6 GUI rewrite.** Fine matrices replaced `PoseStack`, render pipelines gate
   blitting, and several block/entity classes were merged. Jade 1.21.1 → 1.21.6 is the target case.
   Check: Jade builds and its overlay renders in a real 1.21.6 client with a captured frame.
6. **Chain version hops.** Any version to any other means multi-hop jumps (1.20.1 → 1.21.6) with a
   record per hop, mappings carried between hops and one reversible chain. Check: the same mod
   reaches the far version through two or more hops, each hop's receipt naming what it changed.
7. **Make a loader change possible.** Any loader to any loader, starting with one Fabric → NeoForge
   mod with no GUI, measured end to end: build scripts, entrypoints, mixin targets, registries. Then
   either build the adapter or write "not supported" with the reason. Check: one mod loads on the
   other loader, or a written decision with the measured blockers.
8. **Automate build-toolchain bumps.** A five-year hop needs Loom, Gradle, NeoGradle or ModDev bumped with the game: AppleSkin 1.20.1 -> 1.21.6 refused Loom 1.8 and Gradle 8.10 before any source could compile. Pick the versions from the target and prove it on the same hop. Check: the AppleSkin jump reaches the source-repair stage with no hand-edited build files.
9. **Add Kotlin and Stonecutter source coverage.** Port a real Kotlin mod; map Stonecutter errors on
   generated sources back to the shared annotated `src/` so repairs land once. Check: each produces a
   building node or mod with repairs in the shared sources only.

## Decisions

| Question | Choice | Date |
|---|---|---|
| What is the goal? | Any Minecraft version to any other, and any loader to any other; today one hop inside one loader is verified | 2026-10-24 |
| Is a version change the same capability as a loader change? | No; they are separate and are reported separately | 2026-10-24 |
| Does a passing build prove a port? | No; loader-refused jars, invisible UI and unloaded mods were all caught after green builds | 2026-10-24 |
| Which loader is supported automatically? | Fabric and NeoForge; Forge target setup stays manual | 2026-10-24 |

---

# Historical gaps

Jon expanded the goal on 2026-10-23 to portable, broadly automatic mod ports and fast first-use
verification. The items below retain the earlier gaps and sizing; their old order is historical.

The older gap descriptions below are historical sizing, not the current feature inventory.
Ordered by what a stranger hits first, not by what is most fun to build. Each item says why it
matters, what "done" looks like, and roughly what it costs (one person, evenings — treat the
numbers as sizing, not promises). Everything is compared against the surveyed alternatives in
[`bench/alternatives.md`](bench/alternatives.md), which also lists the tools that are simply better
at some of these jobs.

Status terms used here: **done** means it works and was checked, **open** means it is not built.


---

## 1. No LICENSE — **done in this change**

**Why it matters.** Without a license nobody can legally reuse the repo, and a reviewer stops
reading there. It was the cheapest credibility gap on the list.

**Done looks like.** `LICENSE` at the repo root: MIT, `Copyright (c) 2026 Jon`, with `NOTICE`
agreeing. Jon picked MIT here and can change it at any time — if he prefers permissive-with-patents
(Apache-2.0) or public domain (CC0-1.0, what the demo mod's own manifests still declare), it is a
one-file swap plus the three `mods.toml`/`fabric.mod.json` `license` fields.

**Cost.** 15 minutes, done.

---

## 2. No release artifact — **being prepared separately**

**Why it matters.** Today the only way in is `git clone` plus `install.ps1`. There is no version
number to quote, no checksum to verify, and nothing a stranger can download without git.

**Done looks like.** A tagged release with the zip, a SHA-256, a short "what changed", and the
README install step pointing at the download instead of a clone. That work is tracked by the
separate release effort in this repo — this roadmap does not duplicate it; this item is only here so
the ordering stays honest.

**Cost.** Not counted here (owned by that effort).

---

## 3. Windows + PowerShell 7 only — **open**

**Why it matters.** A Linux or macOS modder cannot run a single command: `lab.ps1`, `install.ps1`
and `capture-launch.ps1` are PowerShell, the JBR/JDK fetch is written for Windows paths, and the
warm client opens a real (hidden) window. Loom, ModDevGradle, mc-mod-porter, HeadlessMC and
MC-Runtime-Test all run cross-platform, so this is the first hard "no" a non-Windows visitor gets.

**Done looks like.** Either (a) `modaccel` starts on Linux with the **fast lane and `port` working**
(no client tier), verified by one real run on a Linux box, or (b) the README says plainly
"Windows-only, here is why, here is what runs elsewhere" and the plan for (a) is dated. The client
tiers on Linux need Xvfb and a different capture path, so they are a separate, later step.

**Cost.** README honesty: 1 hour. Minimal Linux path for fast lane + lint + `port` (a bash entry
script over the same Java tools): 3-5 days. Client tiers on Linux (Xvfb, headless GL): 1-2 more
weeks, and only worth it after CI (item 5).

---

## 4. No editor integration — **open**

**Why it matters.** Modders live in IntelliJ (the Minecraft Development plugin) or VS Code. This
loop is a terminal command; a red squiggle, a run button or a test gutter icon would reach people
who will never open a terminal in their mod repo.

**Done looks like, in two steps.** Step 1 (cheap): `install.ps1` also writes `.vscode/tasks.json`
and an `.idea` run configuration so `modaccel`, `ui`, `node` and `world` are one keypress, verified
by importing a fresh clone into both IDEs. Step 2 (expensive): a real plugin that runs the fast lane
on save and shows PASS/FAIL in the editor, which also needs a stable machine-readable output format
(see item 5) before it can exist.

**Cost.** Step 1: half a day per editor. Step 2: 1-2 weeks for IntelliJ alone (plugin SDK, and
IntelliJ's own debugger hotswap already does part of this — say so rather than compete with it).

---

## 5. No CI story — **open**

**Why it matters.** Every check here is run by a human at a keyboard. Fabric client gametests are
CI-native and upload screenshots; MC-Runtime-Test is a GitHub Action that fails a job. A team cannot
adopt a loop that only exists on one machine. It is also the prerequisite for the editor plugin and
for trusting the numbers on hardware other than Jon's.

**Done looks like.** A documented GitHub Actions workflow that runs the fast lane plus the `world`
tier in CI (today: a `windows-latest` runner; after item 3: `ubuntu-latest`), uploads the PNGs as
artifacts, and emits **JUnit XML** so standard dashboards can consume the results. Non-zero exit on
failure is already there. Verified by a green and a deliberately red run in a public fork.

**Cost.** Windows-runner workflow + artifacts: half a day. JUnit XML output from the fast lane and
`suite`: 1 day. Linux runner: blocked on item 3.

---

## 6. Speed claims are self-measured — **open**

**Why it matters.** `runClientGameTest` 28-40s, `gradlew runClient` 15s to atlases, the 328.3s
MineColonies build: all measured here, on one machine (Ryzen 9 3900X, Windows 11), by the author.
No third party has reproduced them, and there is no head-to-head against Fabric's client gametests
or Gradle Build Scans on the same repo in the same session. Until then "faster" is a hypothesis with
one data point, and a reviewer is right to say so.

**Done looks like.** One script that, on the same repo and machine, measures (a) `runClientGameTest`,
(b) `gradlew build` with a Build Scan, and (c) this harness's tiers, three repetitions each, and
prints a table with medians and spread; run on two machines, preferably one that is not Jon's; raw
output committed under `bench/`. The README then quotes the comparison instead of only its own
numbers, or says which comparisons were never made.

**Cost.** 2-3 days including writing it up, plus the risk of an unflattering result — which is the
point of doing it.

---

## 7. Forge: no hot reload, one manual capture per repo — **open**

**Why it matters.** Forge 1.20.1 gets the fast lane (2.3s on 2,102 sources) and a warm client, but a
code edit costs a new client (58.6s cold) and every repo needs `tools\capture-launch.ps1` run by
hand once, then re-run whenever ForgeGradle rewrites its command line. That is the difference
between "a loop" and "a tool with a setup step" on the oldest loader.

**Done looks like.** Either the capture disappears — `modaccel` derives the launch itself from the
repo's Gradle configuration, verified on two Forge repos — or the README says why it cannot and what
the manual step is, in the same sentence as the numbers. Hot reload on Forge is a separate bet: it
needs a redefinition agent that survives Forge's own class loading, and it may not be reachable.

**Cost.** Automatic capture: 1-2 days. Forge hot reload: 3-5 days with unknown outcome; do not start
it before item 6, because without a head-to-head nobody can say whether it is worth it.

---

## 8. No pixel diffing — **open by design**

**Why it matters.** Screenshots here are for a human or an agent to look at. That is a deliberate
trade: no goldens to accept, no tolerance tuning, no false alarms on a driver or font change. It
also means nothing in the loop can tell you a screen got *worse* since last week — a regression that
Fabric's uploaded screenshot history at least lets a person spot.

**Done looks like.** A decision, written down, not drift: either (a) keep "no comparison" and say so
in the README next to the screenshot claim, or (b) add an **opt-in** `-Baseline` / `-Compare` that
saves a golden, compares with a documented tolerance, prints the diff percentage, and never fails a
run that did not ask for it. Note that `settle` already measures frame-to-frame pixel difference, so
the comparison code partly exists.

**Cost.** (a) 10 minutes. (b) 1-2 days plus ongoing golden maintenance in every repo that turns it
on — which is the argument for (a).

---

## Not on this list

- **Replacing GameTest.** The `world` tier *is* a GameTest-shaped check; a repo with its own suite
  is skipped honestly rather than raced.
- **AI porting, current capability versus goal.** The current tool uses jar/mapping diffs,
  targeted fixes and written recipes; it does not implement general autonomous semantic repairs.
  Assisted repairs are now part of the expanded goal in `PLAN.md`, not a delivered capability.
- **Beating IDE test runs on pure logic.** A JUnit run in IntelliJ is about as fast. The value here
  is zero-config inside a mod repo, not raw speed over JUnit.
