# 1.21.11 node: measured

All numbers from commands run in this directory on 2026-10-21, same PC, JDK 25
(`..\..\.tools\jdk-25.0.4.1+1`) for Gradle and JBR 25 for the game process.

## Build

| What | Seconds | Command |
| --- | --- | --- |
| clean build, outputs deleted, dependency caches warm | **16.7** | `.\gradlew.bat clean build` |
| build with nothing to do | 6.4 | `.\gradlew.bat build` |
| first build ever in this directory | ~210 | includes downloading the Gradle 9.8.0 distribution and Minecraft 1.21.11 + Fabric API 0.141.6 and remapping them |

## Warm client (ports 27499 / 27498)

| What | Seconds |
| --- | --- |
| client start to a listening socket (title screen) | **19.8**–**25.0** (6 runs; includes Gradle configure + compile) |
| `node` scenario, first one after a client start (has to open the saved world) | **9.1**, 9.2, 9.5, 10.4 |
| `node` scenario, second one on the same client (already in the world) | **4.1** |
| `ui` command (open screen, screenshot, click "More shards", screenshot) | **1.2**, 1.2, 1.2 |

The `node` scenario is the deliverable: place `prismlab:prism_node`, wait 40 ticks,
`charge()` > 0, settle, screenshot. Latest runs:

```
PASS   9.12s  placed prismlab:prism_node at -40, 64, -9 | assert charge() = 1 | settled (max channel delta 13) | screenshot 1.21.11-node.png 854x480
PASS   4.06s  placed prismlab:prism_node | assert charge() = 2 | settled (max channel delta 6) | screenshot 1.21.11-node.png 854x480
```

`charge()` is 1–2 after 40 ticks because the scenario clears the spot before placing, so the
block entity really is 2 seconds old. The block is placed 3 blocks along the line of sight, so
the PNG shows the node itself.

## Screenshot

- Path: `versions\1.21.11\build\run\warm\shots\1.21.11-node.png`
- Pixels: **854 x 480** (fixed window 854x480, GUI scale 2)
- `prismlab-ui-1.21.11.png` and `prismlab-ui-1.21.11-clicked.png`: 854 x 480 as well

## Slower / faster than the 26.3 node

26.3 numbers are the parent's own run history (`.runs\index.jsonl`, same machine, warm client
already in the world, hot reload on): `node` 2.1 s, `ui` 1.5 s, scenario with settle 2.8 s.

- **Slower: `node` 4.1 s warm / 10 s when the world still has to open, vs 2.1 s on 26.3.**
  Two reasons: opening the integrated world costs 5–6 s from a cold client, and settle needs
  two samples 6 ticks apart instead of the parent's 2.
- **`ui` is 1.2–1.6 s with nothing changed and 1.7–2.2 s after an edit**, so it matches the
  26.3 lane (1.5 s) now that this node recompiles and redefines instead of restarting.
- **Settle is not exact here.** Two visually identical 1.21.11 frames differ by up to 14 levels
  per channel (323,131 pixels changed, max delta 14, measured with `.modaccel\PngDiff.java`),
  so the 26.3 strict-equality settle can never converge. 1.21.11's settle passes at max channel
  delta <= 16; a real scene change is far above that (a black/pre-render frame vs a drawn frame
  measured max delta 255). Also, two screenshots asked for back-to-back come back all black, so
  samples are 6 ticks apart.
- **Build is heavier here**: loom 1.19.2 needs Gradle 9.8.0 (the parent is on 9.7.1) and the
  obfuscated 1.21.11 target needs the `fabric-loom-remap` plugin plus `modImplementation`
  dependencies, which adds the remap step to `build` (16.7 s clean vs the parent's plain
  unobfuscated build).

## Hot reload

Measured 2026-10-22 on the same PC, client already up (ports 27499 / 27498), one edit at a
time, wall seconds from the `Get-Date` around the command to the command returning:

| What | Seconds |
| --- | --- |
| edit `PrismScreen.java` (hint text), then **`warm.ps1 ui`** | **1.75**, 1.88, 2.24 |
| the same edit, then **`modaccel -Version 1.21.11 ui`** | **3.47**, 3.74 |
| edit a main class (`PrismBanner.label()`), then `warm.ps1 ui` | **2.03**, 2.64 |
| `warm.ps1 ui` with nothing changed | **1.18**, 1.20, 1.32, 1.39, 1.60 |
| first `ui` on a freshly started client (both sides compile) | 3.78 – 4.28 |
| **restart path** for comparison: `warm.ps1 stop` (6.4) then `ui` (21.7) | **28.0** |
| restart path, cold Gradle daemon (first start of the day) | 37.3 |
| restart path, Loom build failing once (see below) | 268 |

So a screen edit is **1.7–2.2 s** against **28 s** for a restart: the whole Gradle build, the
client launch and the world/title-screen boot disappear. `modaccel -Version 1.21.11 ui` costs
about 1.7 s more than `warm.ps1 ui` before the client is touched, which is `modaccel.ps1`
parsing and probing (the same cost on the 26.3 node), not the check itself.

Where the 1.7–2.2 s goes: `javac` 0.6–1.4 s for the side that changed (inside
`HotUi`, measured and printed: `main: ... (javac 715 ms)`), plus 22 client ticks ≈ 1.1 s for
the two screenshots and the click, plus the socket round trip.

Both proofs, screenshots kept in `.workers\hotreload\`:

- Client class: `HINT` "Click More shards." → "Hot reload, no restart." → back. `L-final-reverted.png`
  vs `G-client-edit2.png` differ in that one line; no restart in between.
- Main class: `PrismBanner.label()` "Prism node online" → "Prism banner hot edit" → back
  (`F-main-reverted.png` vs `E-main-edit.png`). The string comes from a main-source class that
  was already loaded when the server side initialised, and `main: redefined 1 main classes`
  is the agent's answer, so this is a real redefinition, not a fresh load.

What still needs a restart (unchanged from the 26.3 rules): adding a field or method, a new
registration, a mixin, and edits to `dev.modlab.client.*` itself (the control classes are not
reloaded — `LabControl.java` and `HotUi.java` only take effect on the next client start).

Two things this node needs that the 26.3 node does not, both now in `warm.ps1`:

- **Stale first frame.** 1.21.11's async readback returns the frame from *before* the screen
  change (and from before a 1.7 s compile stall), so `ui` captures twice, 4 ticks apart, and
  the second capture overwrites the first. A single capture showed the onboarding screen twice.
- **One client per node.** A start that overlapped a slow Gradle build left a second client
  that could not own the port; `start` now waits for a client that is already booting,
  replaces one that never answers, and `stop` waits for the process to actually exit.

The one flake worth knowing: three starts in this session died at configure time with
`Failed to remap sources ... fabric-networking-api-v1-...-sources.jar` after a source edit,
and the next start worked. `warm.ps1 start` now retries once when the launcher exits without a
client instead of waiting out the 300 s timeout.

## Caveat about the source tree

The numbers above are for the parent sources as they stood at 22:56 on 2026-10-21. Another
session was editing `..\..\src` at the same time (`PrismLab.java`, `PrismNodeEntity.java`), and
this node compiles those files live, so a parent change that uses a 26.3-only API will break
this node's build until a matching override is added here.

