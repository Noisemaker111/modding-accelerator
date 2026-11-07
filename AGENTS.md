# Modding Accelerator

This file holds only what is specific to this project.

## Goal

A mod or plugin author never has to launch Minecraft, and a modpack user can find and resolve compatibility problems without diagnosing them by hand. The goals are:

1. **Port** any mod source project (a Gradle checkout; compiled JARs are unsupported) to any Minecraft version, forward or back, reversibly, through the change index and hub routing. Changing loader is a separate job.
2. **Prove it behaves** in the real game or a real server: the mod's own state asserted in a real client (`eval`, `--assert`, `--expect-value`), plugins on a real Paper server with a bot player following a scenario. A build, a fake server (MockBukkit) or "the mod loaded" is a floor, never "tested".
3. **Make modpacks work together**: detect and resolve incompatibilities in installed mods and their configurations, including incorrect blocks or items and worlds that fail to reopen, with reversible changes and real-game behavior checks. Compiled mods are inputs for pack diagnosis; source is still required for source ports.

Windows, Linux and macOS; Java and Kotlin; Fabric, NeoForge, Forge and Paper, climbing SCOPE.md's ladder.

## Where the truth is

- The roadmap is one file, `coverage/roadmap.json`: every phase and step with its status, start, target and done dates, and the measures it moves. Porting, proof, modpacks and future work are all steps there. Only statuses there are claims; update it when a step changes, then run `python bench/bench_page.py --roadmap` to rewrite `ROADMAP.md` (generated; never edit it by hand). A target that moves keeps its old date in `moved`.
- Every number shown anywhere (the page, `ROADMAP.md`, a PR body, a report to Jon) is a measure in `coverage/measures.json`: the same work measured the same way, with its points, its source, and a target with a date and step. Record a point with `python bench/measures.py add ID VALUE --source WHERE` (it rewrites `ROADMAP.md` too) and show numbers with `python bench/measures.py table`, never a hand-made table. A new kind of number gets a new measure entry first. `bench/viz.py` is the only code that draws a number on a page; add a component there rather than a one-off chart. `python bench/test_measures.py` (PR CI) fails when the two files disagree or `ROADMAP.md` is behind them. `README.md` is the public goal and support table. `modaccel help COMMAND` is how each command works; don't repeat it in a doc.
- `coverage/ledger.json` records port runs. A run that is not in the ledger did not happen. `python bench/bench_page.py` renders the roadmap, every measure, the porting map and the ledger into `docs/index.html`, the one public page (`docs/porting-map.html` only redirects old links there). After any port run, or any change to the rules or the router, record it in the ledger in the same change, so a hop turns green only once a port on it has been seen working. Regenerate `docs/` only in the final commit before a milestone merge (see "Branches and PRs").
- `coverage/changes/` is the change index (every neighbouring-release diff 1.14.4 → 26.3 in Mojang names) and its `index.json` the families, hubs, rule coverage and best ledger run. Refresh: `java tools/ChangeIndex.java`, `python bench/change_routes.py`, `python bench/porting_map.py`, `python bench/bench_page.py`.
- Rules mined from Stonecutter mods are the `source: "stonecutter"` rules in `knowledge/fixes.json`; regenerate with `python bench/stonecutter_mine.py extract` then `python bench/stonecutter_rules.py`.
- Trial trees live under `.runs/beta-trials/`; logs under `.runs/logs`, history in `.runs/index.jsonl`.

## Commands

The portable command: `modaccel.cmd` on Windows, `./modaccel` on POSIX, or `java -jar modaccel.jar`. `help` lists the verbs and `support` the version table. Porting: `port`, `explain`, `mixin-check`. Mods: `fast`, `data-check` (the game's data, items and load errors from the game's own code, no game launched; Fabric 26.x; its game stays loaded, so a changed data file costs about 2 CPU-s, and a spare started behind it takes changed code in about 6; `--once` keeps nothing and `game-stop` ends both), `ui-check`, `world-check`, `mp-check`, `eval`, `reload`, `game-status`, `game-stop`. Plugins: `plugin-check`, `server-check`.

`modaccel.ps1` (called by `modaccel.cmd` for `ui`, `node`, `suite`, `world`, `look`, `mp`, ...) and `bench/bridge`, `bench/forgebridge` are the older Windows lane, kept until the portable command covers each of its jobs; don't add to them.

Run builds and game launches through modaccel, never raw Gradle or a manual launch, and never add `--no-daemon`. Package locally; publishing needs Jon's approval.

## Branches and PRs

- One PR per feature or support area (e.g. "Paper plugin support", "Porting"), not one per step. Keep it a draft and add commits as steps land; the PR body is the checklist.
- Merge when it reaches a milestone worth shipping (a new rung on SCOPE.md's ladder, or a big porting jump such as every 1.14 mod reaching 1.16), with CI green on all 3 OS. Agents merge; Jon has asked not to be waited on. Publishing a release still needs his approval.
- PR CI finishes in under 5 minutes on every OS. A check that is slower runs nightly or on demand, not on the PR. The real-game behaviour checks (`bench/behaviour_checks.sh`, a hidden client on Xvfb with software OpenGL) run by hand or on boat; a cloud CI job for them waits for Jon to approve that spending. The `budget` job in `.github/workflows/portable.yml` fails a PR run that takes longer, and every PR job has a 5-minute timeout.
- Porting work goes on one long-running branch, `porting`, merged at those jumps.
- Small fixes outside any feature may go in one hardening/fixes PR at a time.
- A PR body follows `.github/pull_request_template.md` and fits on one screen: a bold one-line result, before/after (a table row per change), what was checked, what is not covered. A title that needs a comma-joined list is several PRs. When a push changes what the PR does, rewrite the body in the same push.
- Every port run gets its ledger row in that branch. Regenerate `docs/` (`python bench/bench_page.py`, `python bench/porting_map.py`) once, in the final commit before merge, so the site changes only when a milestone lands.
- Agents work in their own worktree and never edit the same files as another agent.

## Releases

A release is how someone without a source checkout gets modaccel. Cut one when a milestone merges:

1. Bump `VERSION` in `tools/cli/Support.java` and regenerate `docs/` (`python bench/bench_page.py`, `python bench/porting_map.py`) in the milestone PR.
2. After merge, tag `main` as `v<VERSION>` and push the tag. `.github/workflows/release.yml` checks the tag matches the code and `docs/` is current, packages, runs the extracted-ZIP test, and opens a draft release whose notes are the merged PR titles. That list is the changelog; there is no hand-kept one, so PR titles must read well.
3. Jon reads the draft and publishes it.

## What counts as evidence

- A passing build, mixin audit or loaded world is not proof of the mod's behaviour. Report port/build, game start and behaviour checks separately, each with its cache condition (cold or warm).
- Never present preview or edit-only timings as complete ports, and never invent a universal speed target. Warm caches and reusable sessions are welcome; first-use setup matters too.
- A rendering claim needs an inspected 1280x720 Minecraft framebuffer capture, never a desktop grab and never a hash of a frame. Say which tier you ran; a tier that cannot catch the mistake you made is not evidence.
- When dependencies or behaviour cannot be verified, return the precise blocker. No silent feature removal and no weakened checks to make a port pass.

## Modpacks

`pack-check` runs a pack on its own Forge server. Forge 1.20.1 runs on SRG names, so the probe in `tools/packcheck` is written in Mojang names and renamed with ART; ART's `--lib` must be the Mojang-named jars or nothing is renamed. A server whose start fails keeps its JVM alive on leftover threads: stop it on the failure line, by PID. Mods marked for both sides on CurseForge can still be client-only and stop a server; leave them out and say so, and remember them in `~/.modaccel/packs/client-only.json` so no later start pays that extra start again. Every repair comes from a known fault, found before the game runs, so `--fix` starts the repaired pack's server beside the shipped one (each with its own instance folder, logs prefixed `repaired-`); keep the two lanes' findings apart. Forge loads the highest version of a mod id, bundled or standalone, so a newer jar in mods/ replaces a bundled copy.

## Resident client

The hidden client runs on JBR (`.tools\jbr-*`) with `-XX:+AllowEnhancedClassRedefinition -XX:TieredStopAtLevel=1`. Keep the C1-only flag: C2 plus redefinition crashes the VM. A hidden (background) game also runs C1 only, which halves the CPU it spends starting (`bench/session_cost.py`), and a check stops when a game asked to stay hidden reports its window library was not rewritten. Adding a field, method or registry entry still needs a restart; mixin edits need a clean client. A dedicated server sends block entity data only when the class overrides `getUpdatePacket()` and `getUpdateTag()`, and 26.3's default update tag is empty, so singleplayer hides what a real client never receives: `mp-check` is the tier that shows it. Primary mouse button in `MouseButtonInfo` is 1, not 0. Item components bind only once a datapack loads, so a title-screen UI cannot build an `ItemStack`. `BlockBehaviour.Properties` needs `.setId(...)`.
