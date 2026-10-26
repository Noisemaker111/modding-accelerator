# Modding Accelerator

This file holds only what is specific to this project.

## Goal

Anyone brings an unfamiliar mod source project (a Gradle checkout; compiled JARs are unsupported), picks a Minecraft version, and gets a reversible port plus fast, trustworthy logic and game checks with minimal setup. Windows, Linux and macOS; Java and Kotlin; Fabric and NeoForge; forward and back. Changing loader is a separate job. The limited Fabric beta is a starting point, not the scope.

## Where the truth is

- `ROADMAP.md` holds the phases and numbered steps. Only statuses there are claims; update it when a step changes. `README.md` is the public goal and support table; `PLAN.md` the evidence behind the steps.
- `coverage/ledger.json` records port runs. A run that is not in the ledger did not happen. `python bench/progress.py` renders it to `docs/index.html` (the bench page). After any port run, or any change to the rules or the router, record it in the ledger and regenerate `docs/index.html` and `docs/porting-map.html` in the same change, so a hop turns green only once a port on it has been seen working.
- `coverage/changes/` is the change index (every neighbouring-release diff 1.14.4 → 26.3 in Mojang names) and its `index.json` the families, hubs, rule coverage and best ledger run. Refresh: `java tools/ChangeIndex.java`, `python bench/change_routes.py`, `python bench/porting_map.py`.
- Rules mined from Stonecutter mods are the `source: "stonecutter"` rules in `knowledge/fixes.json`; regenerate with `python bench/stonecutter_mine.py extract` then `python bench/stonecutter_rules.py`.
- Trial trees live under `.runs/beta-trials/`; logs under `.runs/logs`, history in `.runs/index.jsonl`.

## Commands

The portable command: `modaccel.cmd` on Windows, `./modaccel` on POSIX, or `java -jar modaccel.jar`. `help` lists the verbs and `support` the version table. The main verbs: `port --from OLD --to NEW --fix` (routes A → hubs → B, joins one map across every update, filters rules by the updates crossed and names each remaining error's update; `--direct` keeps the one-jump map), `fast`, `ui-check`, `world-check`, `mp-check`, `eval` (runs Java in the owned client), `reload` (swaps recompiled classes in and says when a restart is needed), `game-status`, `game-stop`.

Run builds and game launches through modaccel, never raw Gradle or a manual launch, and never add `--no-daemon`. Package locally; publishing needs Jon's approval.

## What counts as evidence

- A passing build, mixin audit or loaded world is not proof of the mod's behaviour. Report port/build, game start and behaviour checks separately, each with its cache condition (cold or warm).
- Never present preview or edit-only timings as complete ports, and never invent a universal speed target. Warm caches and reusable sessions are welcome; first-use setup matters too.
- A rendering claim needs an inspected 1280x720 Minecraft framebuffer capture, never a desktop grab and never a hash of a frame. Say which tier you ran; a tier that cannot catch the mistake you made is not evidence.
- When dependencies or behaviour cannot be verified, return the precise blocker. No silent feature removal and no weakened checks to make a port pass.

## Resident client

The hidden client runs on JBR (`.tools\jbr-*`) with `-XX:+AllowEnhancedClassRedefinition -XX:TieredStopAtLevel=1`. Keep the C1-only flag: C2 plus redefinition crashes the VM. Adding a field, method or registry entry still needs a restart; mixin edits need a clean client. A dedicated server sends block entity data only when the class overrides `getUpdatePacket()` and `getUpdateTag()`, and 26.3's default update tag is empty, so singleplayer hides what a real client never receives: `mp-check` is the tier that shows it. Primary mouse button in `MouseButtonInfo` is 1, not 0. Item components bind only once a datapack loads, so a title-screen UI cannot build an `ItemStack`. `BlockBehaviour.Properties` needs `.setId(...)`.
