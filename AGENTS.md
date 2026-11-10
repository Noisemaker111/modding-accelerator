# Modding Accelerator

`modaccel` is one command for Minecraft mod, plugin and modpack work. This file is the rules for working in this repo. It does not repeat `modaccel help COMMAND` (how a command works), `README.md` (what works today) or `ROADMAP.md` (what is done and what is next).

## The four goals

Everything here serves one of these. They are in priority order.

| # | Goal | Done means | Commands |
|---|---|---|---|
| 1 | **Test a mod in seconds** | An author changes code and gets an answer about the mod's real behaviour in seconds, and never opens Minecraft by hand | `fast`, `data-check`, `mixin-check`, `ui-check`, `world-check`, `mp-check`, `eval`, `reload`; plugins: `plugin-check`, `server-check` |
| 2 | **Port a mod to any Minecraft version** | One command, no hand edits, forward or back, every edit reversible, the result checked in the real game | `port`, `explain`, `dependencies` |
| 3 | **Make modpacks work together** | Conflicts in an installed pack are found and repaired (wrong blocks or items, worlds that fail to reopen), reversibly, checked on the pack's real server | `pack-check` |
| 4 | **Change loader or platform** | The same project moved between Fabric, NeoForge, Forge, Quilt and Paper. A separate job from a version port, with separate claims | not built yet; `python bench/loader_api.py show FROM TO` lists the loader calls known to stand in for each other |

- Windows, Linux and macOS; Java and Kotlin; Fabric, NeoForge, Forge and Paper, climbing `SCOPE.md`'s ladder.
- Ports need source (a Gradle checkout). Compiled jars are inputs for pack diagnosis only.
- Work that moves none of the four goals is not roadmap work. A speed target, a page or a refactor earns its place by moving a goal's step; otherwise it is a small fix or it waits.

## How work is done

1. **Find the step.** `ROADMAP.md` opens with where each goal stands: the steps being worked on and the steps up next. Take one of those. Finish a `doing` step in a goal before starting another one there. Work with no step gets a step first, under its goal in `coverage/roadmap.json`; a small fix needs none.
2. **Work in your own worktree**, on that goal's branch, and never edit the same files as another agent.
3. **Run it through modaccel.** See Commands.
4. **Prove it** at the level the claim needs. See Evidence.
5. **Record it in the same change.** See Recording. A step is `done` only when it ran and was checked.
6. **Put it in the goal's PR**, with the goal and step in the body. See Branches and PRs.

## Recording

Three files are the truth. Everything else that shows a status or a number is generated from them.

| What happened | Record it with | It writes |
|---|---|---|
| A step started, finished or moved | `python bench/record.py step ID start\|done\|next\|later [--result TEXT] [--target DATE]` | `coverage/roadmap.json`. `done` needs `--result`: what ran and what decided the pass. A target that moves keeps its old date in `moved` |
| A port ran | `python bench/record.py port RECEIPT --mod NAME` (or `python bench/port_board.py --record` for the pinned ports) | A row in `coverage/ledger.json`, read from the receipt `modaccel port` printed. `load` and `behaviour` need `--status` and a `--detail` saying what was checked |
| A number was measured | `python bench/measures.py add ID VALUE --source WHERE` | A point in `coverage/measures.json` |
| A number goes in a PR body or a report | `python bench/measures.py table` | Nothing. Never a hand-made table |
| A step is added, or the rules or the router changed | by hand, in the same change | `coverage/roadmap.json` under its goal; a ledger row for the port that shows the change working |

- Only statuses in `coverage/roadmap.json` are claims.
- `ROADMAP.md` and `docs/` are generated, and only `main` writes them: the Generated files workflow runs `bench/bench_page.py` and `bench/porting_map.py` after every merge and commits the result. Never edit or regenerate them in a branch. If a merge conflicts on one, take main's copy (`git checkout origin/main -- ROADMAP.md docs`); main rewrites it anyway. To read the roadmap as your branch has it, run `python bench/bench_page.py --roadmap` and don't commit the file.
- Every number shown anywhere (the page, `ROADMAP.md`, a PR body, a report to Jon) is a measure: the same work measured the same way, with its points, its source, and a target with a date and a step. A new kind of number gets a new measure entry first.
- A port run that is not in the ledger did not happen.
- `bench/viz.py` is the only code that draws a number on a page. Add a component there, not a one-off chart.
- `python bench/test_measures.py` (PR CI) fails when the roadmap and the measures disagree or do not render.
- `docs/index.html` is the website and the only one: what modaccel is and how to get it, the four goals, the roadmap, the porting map, every measure and the ledger. `bench/bench_page.py` writes it; its look is `bench/page_style.py` and the CSS in `bench_page.py`. `docs/porting-map.html` only redirects old links there.
- A goal's `today` sentence and `try` commands in `coverage/roadmap.json` are what the website and `ROADMAP.md` say works now. Change them when what works changes.

## Evidence

- A passing build, mixin audit or loaded world is not proof of the mod's behaviour. A build, a fake server (MockBukkit) or "the mod loaded" is a floor, never "tested". Proof is the mod's own state asserted in a real client (`eval`, `--assert`, `--expect-value`), or a plugin on a real Paper server with a bot player following a scenario.
- Report port/build, game start and behaviour checks separately, each with its cache condition (cold or warm).
- Never present preview or edit-only timings as complete ports, and never invent a universal speed target. Warm caches and reusable sessions are welcome; first-use setup matters too.
- A rendering claim needs an inspected 1280x720 Minecraft framebuffer capture, never a desktop grab and never a hash of a frame. Say which tier you ran; a tier that cannot catch the mistake you made is not evidence.
- When dependencies or behaviour cannot be verified, return the precise blocker. No silent feature removal and no weakened checks to make a port pass.

## Commands

The portable command is `modaccel.cmd` on Windows, `./modaccel` on POSIX, or `java -jar modaccel.jar`. `modaccel help` lists the verbs, `modaccel help COMMAND` explains one, and `modaccel support` prints the version table. The goals table above says which verb serves which goal.

Run builds and game launches through modaccel, never raw Gradle or a manual launch, and never add `--no-daemon`. Package locally; publishing needs Jon's approval.

Before you push, run what CI runs, without a game or a network: `modaccel self-test` (about 8 seconds; `--only SUITE` runs one suite, and a failure is reported first), then `python bench/test_measures.py`, `python bench/test_record.py`, `python bench/test_ledger_rule.py`, `python bench/test_java_lines.py` and `python bench/test_loader_api.py`.

When you change a port rule or the porter, run `python bench/port_board.py`: it ports every pinned mod in `bench/port-cases.json` from a clean checkout and shows each one against what is recorded and against the last run, then lists the errors still left by the game update that caused them. It exits 1 when a port got worse. Every rule in `knowledge/fixes.json` carries a `test {error, before, after}`, and `self-test` fails on one that does not. `python bench/rule_test.py --only RULE_ID --write` writes it from the pinned mods' real source, with the porter's own rewrite as the expected result; `--source` adds the checkout of a mod that is not pinned, and `--after OTHER_RULE` is for a rule that only applies once another rule has run.

Write Java one statement per line. `python bench/format_java.py` formats `tools/` (prettier-java, 120 columns; it needs Node) and writes back only files whose compiled bytecode is unchanged, so it cannot alter what the code does; `--check` changes nothing. The code that runs inside the game or a server does not compile outside it, so it is left alone. `python bench/test_java_lines.py` (PR CI) fails on a line over 200 characters in `tools/cli` unless it is one long string.

The older Windows lane is `modaccel.ps1` (called by `modaccel.cmd` for `ui`, `node`, `suite`, `world`, `look`, `mp`, ...), `install.ps1`, the `.ps1` and `Lab*.java` files directly in `tools/`, `bench/bridge`, `bench/forgebridge`, `scenarios/` and `skills/modding-accelerator/` (the skill `install.ps1` copies into a mod repo, which describes this lane only). It is kept until the portable command covers each of its jobs. Don't add to it.

## Where things are

| Path | What it is |
|---|---|
| `tools/cli/` | The portable command. `Modaccel.java` is the entry and the help text; one file per job (`Port`, `Game`, `PackCheck`, `ServerCheck`, ...) |
| `tools/agent/`, `tools/datacheck/`, `tools/mixincheck/`, `tools/packcheck/`, `tools/plugincheck/`, `tools/servercheck/` | The code that runs inside the game or server for each check |
| `tools/tests/` | The command's own tests and fixtures; `PortableTests.java` is what `self-test` runs |
| `knowledge/` | Port rules and repairs. `fixes.json` holds the rules; those with `source: "stonecutter"` are mined (`python bench/stonecutter_mine.py extract`, then `python bench/stonecutter_rules.py`) |
| `coverage/` | The truth files (`roadmap.json`, `measures.json`, `ledger.json`) and `changes/`, the change index: every neighbouring-release diff from 1.14.4 to 26.3 in Mojang names, with families, hubs and rule coverage in `index.json`. `loader/api.json` is the loader catalogue: which loader call stands in for which, and where it was seen (`python bench/loader_api.py fetch`, then `extract`; more sources go in `loader/sources.json`) |
| `bench/` | The recorders (`record.py`, `measures.py`), the port scoreboard (`port_board.py`, cases pinned in `port-cases.json`), the rule test writer (`rule_test.py`), the loader catalogue (`loader_api.py`), the generators (`bench_page.py`, `viz.py`, `porting_map.py`), the measuring scripts, and the real mods and plugins the checks run on (`real-mods/`, `server-plugins/`) |
| `docs/` | The public website, served by GitHub Pages: `index.html` and `porting-map/` are generated on main, `logo.svg` is the mark |
| `src/`, `build.gradle`, `versions/` | Prism Lab, the demo Fabric mod the checks run against. The repo root is both modaccel's home and this fixture |
| `.runs/` (ignored) | Trial trees in `.runs/beta-trials/`, logs in `.runs/logs`, history in `.runs/index.jsonl` |

Refresh the change index with `java tools/ChangeIndex.java`, `python bench/change_routes.py`, `python bench/porting_map.py`, `python bench/bench_page.py`.

## Branches and PRs

- One PR per goal or support area (e.g. "Porting", "Paper plugin support"), not one per step. Keep it a draft and add commits as steps land; the PR body is the checklist. Goal 2 work goes on the long-running `porting` branch.
- Merge when it reaches a milestone worth shipping (a new rung on `SCOPE.md`'s ladder, or a big porting jump such as every 1.14 mod reaching 1.16), with CI green on all 3 OS. Agents merge; Jon has asked not to be waited on. Publishing a release still needs his approval.
- Small fixes outside any goal's step go in one hardening/fixes PR at a time.
- A PR body follows `.github/pull_request_template.md` and fits on one screen: the goal and step, a bold one-line result, before/after (a table row per change), what was checked, what is not covered. A title that needs a comma-joined list is several PRs. When a push changes what the PR does, rewrite the body in the same push.
- PR CI stays quick: every PR job has a 5-minute timeout against hangs. A slower check runs on demand (workflow_dispatch, boat, or locally) when the code it tests changes, never on a schedule: every input is pinned, so a scheduled run repeats the last answer.
- The real-game behaviour checks (`bench/behaviour_checks.sh`, a hidden client on Xvfb with software OpenGL) run on boat or locally when a port reaches the game or the game-check code changes.

## Releases

A release is how someone without a source checkout gets modaccel. Cut one when a milestone merges:

1. Bump `VERSION` in `tools/cli/Support.java` in the milestone PR.
2. After merge, wait for the Generated files workflow to commit the regenerated website, then tag that commit on `main` as `v<VERSION>` and push the tag. `.github/workflows/release.yml` checks the tag matches the code and `docs/` is current, packages, runs the extracted-ZIP test, and opens a draft release whose notes are the merged PR titles. That list is the changelog; there is no hand-kept one, so PR titles must read well.
3. Jon reads the draft and publishes it.

## Things that cost time to learn

Goal 1, the hidden client:

- It runs on JBR (`.tools\jbr-*`) with `-XX:+AllowEnhancedClassRedefinition -XX:TieredStopAtLevel=1`. Keep the C1-only flag: C2 plus redefinition crashes the VM. C1 only also halves the CPU a hidden game spends starting (`bench/session_cost.py`).
- A check stops when a game asked to stay hidden reports its window library was not rewritten.
- `reload` swaps edited code into the running game. Adding a field, method or registry entry still needs a restart; mixin edits need a clean client.
- `data-check` (Fabric 26.x) runs the game's own code with no game launched, and its game stays loaded: a changed data file costs about 2 CPU-s, and a spare started behind it takes changed code in about 6. `--once` keeps nothing; `game-stop` ends both.
- A dedicated server sends block entity data only when the class overrides `getUpdatePacket()` and `getUpdateTag()`, and 26.3's default update tag is empty. Singleplayer hides what a real client never receives; `mp-check` is the tier that shows it.
- The primary mouse button in `MouseButtonInfo` is 1, not 0.
- Item components bind only once a datapack loads, so a title-screen UI cannot build an `ItemStack`.
- `BlockBehaviour.Properties` needs `.setId(...)`.

Goal 3, `pack-check`:

- It runs a pack on its own Forge server. Forge 1.20.1 runs on SRG names, so the probe in `tools/packcheck` is written in Mojang names and renamed with ART. ART's `--lib` must be the Mojang-named jars or nothing is renamed.
- A server whose start fails keeps its JVM alive on leftover threads: stop it on the failure line, by PID.
- Mods marked for both sides on CurseForge can still be client-only and stop a server. Leave them out, say so, and remember them in `~/.modaccel/packs/client-only.json` so no later start pays for that again.
- Every repair comes from a known fault found before the game runs, so `--fix` starts the repaired pack's server beside the shipped one (each with its own instance folder, logs prefixed `repaired-`). Keep the two lanes' findings apart.
- Forge loads the highest version of a mod id, bundled or standalone, so a newer jar in `mods/` replaces a bundled copy.
