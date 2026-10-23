# Agent notes for this lab

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
