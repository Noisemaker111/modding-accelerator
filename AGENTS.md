# Agent instructions for Modding Accelerator

## Product goal and current work

Jon wants anyone to bring an unfamiliar mod source project, choose a Minecraft
version, and get a reversible port plus fast, trustworthy logic/game checks with
minimal setup. Aim across Windows, Linux, macOS, shells, loaders, Java/Kotlin and
version families, including backports. Changing loader is a separate capability.
The limited Fabric beta is a starting point, not the finished scope.

## Where the work is (update this block when a phase step changes)

ROADMAP.md holds the phases and numbered steps; only statuses there are claims.
Live board: `docs/progress.html`, rendered from `coverage/ledger.json` by
`python bench/progress.py`. A port run that is not in the ledger did not happen.

- Phase 1 step 7 (doing): AppleSkin 1.20.1 → 1.21.6. Loom 1.8→1.10 and Gradle
  8.10→8.13 done by hand, JEI dropped (upstream 1.21.6 has none), mappings went
  through, 61 semantic errors remain: render pipeline 20 sites, hunger API,
  tooltip component, networking. Tree: `.runs/beta-trials/appleskin-1201/`.
- Phase 1 step 8 (next): render-pipeline rule family, then re-run step 7.
- Phase 3 (doing): 26.3 is reachable. AppleSkin NeoForge 1.21.6 → 26.3 now configures and
  compiles with no intermediary: the tool resolves NeoForge 26.3.0.97, moves NeoGradle 7.0 to
  7.1, Java 21 to 25 and archivesBaseName to base.archivesName by itself, and four name rules
  (ResourceLocation → Identifier, GuiGraphics → GuiGraphicsExtractor, GameRules and RenderType
  packages) took 82 errors to 22 in 52s. Next errors: HUD stat fields, KeyHelper's GLFW access,
  tooltip draw calls, one GameRules import. Tree: `.runs/beta-trials/appleskin-neoforge-263/`.
- Phase 3 step 14 (doing): `VersionMap <v> <v> --from-names yarn --to-names mojmap` converts one
  version's names (1.21.6: 9,357 classes); applying it to a whole tree is still missing. A 26.3
  build of AppleSkin's own 26.3-fabric checkout compiles in 14s.

Read README.md for the public goal/support table and PLAN.md for the evidence
behind the steps above. Finish the pending final game check before claiming
the current automatic semantic port works. Do not treat a passing build, mixin
audit, or loaded world as proof of the mod's behavior.

Optimize the whole source-to-packaged-and-checked result. Report port/build,
game startup and behavior checks separately, with cache conditions. Warm caches
and reusable sessions are welcome; first-use setup matters too. Never present
preview/edit-only timings as complete ports or invent a universal speed target.

Expand through untouched real mods, reusable migrations and meaningful game
checks. Return a precise actionable blocker when dependencies or behavior cannot
be verified. No silent feature removal or weakened checks to make a port pass.
Current input is a Gradle source checkout; compiled-JAR conversion is unsupported.

Use the portable command for current work: `modaccel.cmd` on Windows,
`./modaccel` on POSIX, or `java -jar modaccel.jar`. See `help` and `support`.
Use `port --from OLD --to NEW --fix`, `fast`, `ui-check`, `world-check`,
`mp-check`, `game-status`, and `game-stop`. Portable hot reload is unfinished;
stop and rebuild after source changes. Package locally; publishing needs approval.
Run builds and game launches through modaccel, never raw Gradle or manual game
launches. Show only inspected 1280x720 Minecraft framebuffer captures, never desktop grabs.

## Earlier Windows source-checkout lanes

The commands, timings and resident-client details below describe the older
Windows lanes. Do not apply their hot-reload or timing claims to the portable beta.

One command is the loop. Do not launch the game by hand, do not run Gradle tasks
directly, and do not add `--no-daemon`.

- Logic edits: `.\modaccel` (about a second, no Minecraft).
- Screen edits: `.\modaccel ui` (about 1-2s, recompiles, redefines, screenshots).
- Block/entity/item behaviour edits: `.\modaccel node` (about 3-4s, real world, real ticker).
- Several checks in one go: `.\modaccel suite` (a file of named scenarios, one warm client).
- New registration, mixins, or a clean-client check: `.\modaccel world` or `.\modaccel look`.
- Moving to another Minecraft version: `.\modaccel port -New <jar>`.
- Multiplayer behaviour: `.\modaccel mp -Steps "place=..."` (dedicated server + hidden client).
- What is running: `.\modaccel status` (port, pid and age of every lab process).

There are no pixel goldens and no `-Update` flag: `ui`, `look` and `scenario` save PNGs and
print their paths for a human or an agent to look at. Never assert on a hash of a frame.

Every shot comes from Minecraft's framebuffer. Scenario `size=1280x720` sets the exact frame;
`crop=x,y,w,h` crops the next capture and fails if the rectangle does not fit. Inspect the saved
PNG before showing it. Never substitute a desktop capture.

A tier that cannot catch the mistake you made is not evidence. Say which tier you ran;
never claim a rendering result without a screenshot.

Fast lane: `.modaccel\daemon.pid`, resident JVM, tests tagged `pure` only. A `pure` test must
not touch a Minecraft class.

Resident client: `.modaccel\client.pid`, port 27399, agent port 27398, started by `.\modaccel ui`,
`.\modaccel node` or `.\modaccel suite`, stopped by `.\modaccel stop`. `HotUi` recompiles
`src/main/java` and `src/client/java`, asks `LabAgent` to redefine loaded main-class bodies, and
loads `com.prismlab.client.*` child-first. Adding a field/method or a registry entry still needs a
restart; mixin edits need `.\modaccel world`.

Scenarios: one line per case, `name|place=namespace:id|wait=<ticks>|assert=<method>|screen=<class>|click=<label>|settle|capture=<name>`.
`wait=40` is 40 ticks (2 seconds). `settle` waits until two frames two ticks apart differ in at
most 0.2% of pixels, and fails after 20 tries instead of saving a half-drawn shot.

The client runs on JBR (`.tools\jbr-*`) with `-XX:+AllowEnhancedClassRedefinition -XX:TieredStopAtLevel=1`. Do not remove the C1-only flag: C2 plus redefinition crashes the VM (hs_err in `run\`). `client.options.pauseOnLostFocus = false` is set by the control mod, otherwise the integrated server pauses and `node` never charges.

Block entity sync: a dedicated server sends block entity data only when the class
overrides `getUpdatePacket()` and `getUpdateTag()`. 26.3's default update tag is empty,
so singleplayer hides what a real client never receives; `mp` is the tier that shows it.

Gotchas: primary mouse button in `MouseButtonInfo` is 1, not 0. Exact screenshots need a
fixed size, GUI scale 2, a deterministic background, and toasts/tutorial disabled. Item
components bind only once a datapack loads, so a title-screen UI cannot build an
`ItemStack`. `BlockBehaviour.Properties` needs `.setId(...)`.

Logs: `.runs\logs`, history `.runs\index.jsonl`, daemon `.modaccel\daemon.out|err`, client
`.modaccel\client.log|err`, warm shots `build\run\warm\shots`.
