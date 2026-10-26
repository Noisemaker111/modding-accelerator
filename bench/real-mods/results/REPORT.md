# Real mods through the modaccel loop

Run 2026-10-25T18:08:22-04:00 on win32, modaccel at `d7a5f0f`, pinned commits. Every mod was cloned fresh; global Gradle cache warm at start (C:\Users\Jk101\.gradle).

| mod | clone | doctor | setup | port | compile | test | ui-check-1 | ui-check-2 | stale-refused | revert-passes | eval | world-check | eval-until | reload | reload-current | reload-revert | game-stop | restart-unchanged | java-ui-check | game-stop-final | nodes-compile | no-leftovers |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AppleSkin `a1c60fc` | 13s | 1.1s | 88s | – | 25s | – | **FAIL** 58s | 40s | 1.5s | 1.9s | 3.0s | 9.1s | 3.3s | 106s | 1.7s | 9.3s | 9.6s | 23s | 23s | 2.2s | – | 3.7s |
| F3Nope `d65a225` | 3.7s | 1.0s | 44s | 56s | 3.2s | – | 36s | 3.0s | 1.2s | 1.8s | 3.2s | 8.1s | 2.6s | 68s | 2.5s | 7.1s | 9.3s | 32s | 31s | 2.6s | – | 3.7s |
| Jade `6701b1e` | 24s | 0.9s | 29s | – | 372s | – | 55s | 4.5s | 1.5s | 2.1s | 2.5s | **FAIL** 1.5s | **FAIL** 62s | 22s | 2.2s | 16s | 7.5s | 55s | **FAIL** 23s | 1.8s | – | 3.9s |
| ZombieApocalypseAddon `1bb67d4` | 15s | 1.0s | 15s | – | 98s | 18s | 103s | 1.9s | 1.2s | 2.2s | 2.8s | 7.1s | 1.7s | 135s | 1.8s | 4.5s | 9.0s | 20s | 19s | 2.3s | – | 3.7s |
| Diorama :26.3 `2a226da` | 23s | 2.5s | 218s | – | 30s | – | 70s | 3.0s | 1.8s | 2.4s | 2.2s | **FAIL** 122s | **FAIL** 64s | 20s | 2.3s | 7.5s | 7.6s | 28s | 28s | 2.4s | – | 3.7s |
| Diorama :1.21.1 `2a226da` | 5.5s | 0.8s | 148s | – | 30s | – | 41s | 2.2s | 1.5s | 2.1s | 2.7s | 8.6s | 2.2s | 67s | 5.0s | 10s | 9.7s | 23s | 23s | 2.3s | – | 3.6s |
| Architectury API :fabric `321ad30` | 2.6s | 1.2s | 257s | – | 68s | – | 76s | 3.3s | 2.3s | 3.2s | 3.1s | **FAIL** 2.2s | **FAIL** 62s | **FAIL** 106s | **FAIL** 2.0s | **FAIL** 34s | 8.4s | 56s | 38s | 3.5s | – | 3.7s |
| In-Game Account Switcher :26.3-fabric `e271659` | 2.8s | 0.9s | 170s | – | 21s | – | 37s | 2.9s | 1.9s | 2.8s | 1.8s | **FAIL** 122s | **FAIL** 62s | 14s | 2.9s | 3.5s | 7.5s | 23s | 26s | 2.6s | – | 3.7s |
| In-Game Account Switcher :26.3-neoforge `e271659` | 2.4s | 0.9s | 44s | – | **FAIL** 57s | – | **FAIL** 12s | **FAIL** 8.8s | blocked | blocked | blocked | blocked | blocked | blocked | blocked | blocked | 1.8s | **FAIL** 19s | blocked | 1.8s | – | 3.6s |
| Sodium :fabric `148151c` | 2.9s | 0.9s | **FAIL** 22s | – | **FAIL** 2.4s | – | **FAIL** 2.2s | **FAIL** 2.2s | blocked | blocked | blocked | blocked | blocked | blocked | blocked | blocked | 1.7s | **FAIL** 7.2s | blocked | 1.9s | – | 3.5s |
| Diorama (no --project) `2a226da` | 1.4s | 0.8s | 130s | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | 1.8s | **FAIL** 1746s (19/21) | 3.8s |
| Architectury API (no --project) `321ad30` | 3.2s | 1.3s | 78s | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | 2.9s | – | 4.0s |
| In-Game Account Switcher (no --project) `e271659` | 16s | 0.9s | 74s | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | 3.3s | **FAIL** 1462s (10/12) | 4.4s |
| Sodium (no --project) `148151c` | 4.1s | 1.8s | 175s | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | 3.2s | – | 4.1s |
| MineColonies `d1573f7` | 152s | 7.7s | 92s | – | 711s | – | **FAIL** 651s | **FAIL** 39s | 37s | **FAIL** 55s | 5.9s | **FAIL** 38s | **FAIL** 65s | 163s | 161s | 91s | 6.7s | 690s | **FAIL** 462s | 21s | – | 7.5s |

Times are wall-clock seconds per command. `–` means the entry has nothing for that step; `blocked` means an earlier failure left nothing to check. Captures need a look by a person or an agent; nothing here compares pixels.

## Failures and next actions

- **AppleSkin / ui-check-1** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\.runs\portable\2026-10-25T22-10-30.084553500Z-a818e81f\3-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\appleskin\ui-check-1.log`
- **Jade / world-check** (fail): C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\saves
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\jade\world-check.log`
- **Jade / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\jade\eval-until.log`
- **Jade / java-ui-check** (fail): This build's :runClient supplies its classpath only while Gradle runs it, so the recorded launch cannot be started on --java. Start it without --java.
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\jade\java-ui-check.log`
- **Diorama :26.3 / world-check** (fail): Game action failed: java.io.IOException: Game did not reach the requested state; expected screen=, world=true; current screen=BackupConfirmScreen
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\diorama-26.3\world-check.log`
- **Diorama :26.3 / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\diorama-26.3\eval-until.log`
- **Architectury API :fabric / world-check** (fail): C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\fabric\saves
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\architectury\world-check.log`
- **Architectury API :fabric / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\architectury\eval-until.log`
- **Architectury API :fabric / reload** (fail): Compile failed; the game still runs the previous build.
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\architectury\reload.log`
- **Architectury API :fabric / reload-current** (fail): The running game has a different build. Run game-stop, then ui-check; stale game results are refused.
  - next: The running game was refused as a different build; if nothing changed, the fingerprint is unstable.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\architectury\reload-current.log`
- **Architectury API :fabric / reload-revert** (fail): Compile failed; the game still runs the previous build.
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\architectury\reload-revert.log`
- **In-Game Account Switcher :26.3-fabric / world-check** (fail): Game action failed: java.io.IOException: Game did not reach the requested state; expected screen=, world=true; current screen=BackupConfirmScreen
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-fabric\world-check.log`
- **In-Game Account Switcher :26.3-fabric / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-fabric\eval-until.log`
- **In-Game Account Switcher :26.3-neoforge / compile** (fail): ^
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-neoforge\compile.log`
- **In-Game Account Switcher :26.3-neoforge / ui-check-1** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-neoforge\.runs\portable\2026-10-25T23-20-22.521820800Z-2888f26d\3-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-neoforge\ui-check-1.log`
- **In-Game Account Switcher :26.3-neoforge / ui-check-2** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-neoforge\.runs\portable\2026-10-25T23-20-34.701037300Z-ee0582d6\2-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-neoforge\ui-check-2.log`
- **In-Game Account Switcher :26.3-neoforge / stale-refused** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / revert-passes** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / eval** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / world-check** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / eval-until** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / reload** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / reload-current** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / reload-revert** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **In-Game Account Switcher :26.3-neoforge / restart-unchanged** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-neoforge\.runs\portable\2026-10-25T23-20-46.390515100Z-717054b7\2-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\ias-26.3-neoforge\restart-unchanged.log`
- **In-Game Account Switcher :26.3-neoforge / java-ui-check** (blocked): no recorded launch to take the game's own Java from
- **Sodium :fabric / setup** (fail): Build discovery failed. WARNING: A restricted method in java.lang.System has been called
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\sodium\setup.log`
- **Sodium :fabric / compile** (fail): Build discovery failed. WARNING: A restricted method in java.lang.System has been called
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\sodium\compile.log`
- **Sodium :fabric / ui-check-1** (fail): Build discovery failed. WARNING: A restricted method in java.lang.System has been called
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\sodium\ui-check-1.log`
- **Sodium :fabric / ui-check-2** (fail): Build discovery failed. WARNING: A restricted method in java.lang.System has been called
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\sodium\ui-check-2.log`
- **Sodium :fabric / stale-refused** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / revert-passes** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / eval** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / world-check** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / eval-until** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / reload** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / reload-current** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / reload-revert** (blocked): no running game (ui-check did not leave one running), so there is nothing to check against
- **Sodium :fabric / restart-unchanged** (fail): Build discovery failed. WARNING: A restricted method in java.lang.System has been called
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\sodium\restart-unchanged.log`
- **Sodium :fabric / java-ui-check** (blocked): no recorded launch to take the game's own Java from
- **Diorama (no --project) / nodes-compile** (fail): :1.21.6: symbol:   method getPackVersion(PackType)
  - next: Read the step log; the error above is the first thing to fix.
- **In-Game Account Switcher (no --project) / nodes-compile** (fail): :26.3-neoforge: ^
  - next: The step ran past its time limit; read its log for where it stopped.
- **MineColonies / ui-check-1** (fail): Project changed during verification; the capture cannot verify the current build.
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\ui-check-1.log`
- **MineColonies / ui-check-2** (fail): The running game has a different build. Run game-stop, then ui-check; stale game results are refused.
  - next: The running game was refused as a different build; if nothing changed, the fingerprint is unstable.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\ui-check-2.log`
- **MineColonies / revert-passes** (fail): The running game has a different build. Run game-stop, then ui-check; stale game results are refused.
  - next: The running game was refused as a different build; if nothing changed, the fingerprint is unstable.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\revert-passes.log`
- **MineColonies / world-check** (fail): The running game has a different build. Run game-stop, then ui-check; stale game results are refused.
  - next: The running game was refused as a different build; if nothing changed, the fingerprint is unstable.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\world-check.log`
- **MineColonies / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\eval-until.log`
- **MineColonies / java-ui-check** (fail): This build's :runClient supplies its classpath only while Gradle runs it, so the recorded launch cannot be started on --java. Start it without --java.
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-180822\minecolonies\java-ui-check.log`

## Wrapper distribution already cached

- AppleSkin: True
- F3Nope: True
- Jade: True
- ZombieApocalypseAddon: True
- Diorama :26.3: True
- Diorama :1.21.1: True
- Architectury API :fabric: True
- In-Game Account Switcher :26.3-fabric: True
- In-Game Account Switcher :26.3-neoforge: True
- Sodium :fabric: True
- Diorama (no --project): True
- Architectury API (no --project): True
- In-Game Account Switcher (no --project): True
- Sodium (no --project): True
- MineColonies: True

## Captures

- AppleSkin ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-11-34.509169Z-a470888a.png`
- AppleSkin revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-12-09.640537200Z-0cfccfd1.png`
- AppleSkin world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-12-18.421190500Z-24a26cde.png`
- AppleSkin reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-14-16.695040400Z-7019fd68.png`
- AppleSkin restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-14-37.384498300Z-1fd6a24f.png`
- AppleSkin java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-25T22-15-03.273557400Z-1789cd5d.png`
- F3Nope ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-17-20.722585100Z-867c2da7.png`
- F3Nope ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-17-56.631046400Z-a634486e.png`
- F3Nope revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-18-02.074540700Z-a44f3eab.png`
- F3Nope world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-18-10.951306500Z-e1c1bc88.png`
- F3Nope reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-19-30.480381900Z-f874c31b.png`
- F3Nope restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-19-49.098647100Z-de98cc03.png`
- F3Nope java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-25T22-20-23.892460200Z-103e6f9c.png`
- Jade ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-25T22-28-06.742394300Z-93651d0d.png`
- Jade ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-25T22-29-02.849298500Z-5bcb993a.png`
- Jade revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-25T22-29-08.906842100Z-185c7a7e.png`
- Jade reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-25T22-30-40.962301Z-a92449e1.png`
- Jade restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-25T22-31-06.660821800Z-3397eec1.png`
- ZombieApocalypseAddon ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-34-59.442146700Z-c7cfecba.png`
- ZombieApocalypseAddon ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-36-42.260820900Z-76903e3b.png`
- ZombieApocalypseAddon revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-36-46.449535300Z-298bb390.png`
- ZombieApocalypseAddon world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-36-53.602617600Z-328f49fc.png`
- ZombieApocalypseAddon reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-39-17.216205800Z-881266c6.png`
- ZombieApocalypseAddon restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-39-32.540686500Z-e1e4eab6.png`
- ZombieApocalypseAddon java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\zombie\build\modaccel\shots\2026-10-25T22-39-55.060873Z-ce6d2685.png`
- Diorama :26.3 ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-44-55.209433900Z-48e72d92.png`
- Diorama :26.3 ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-46-04.110664200Z-51326ad2.png`
- Diorama :26.3 revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-46-10.156321300Z-b30dff5f.png`
- Diorama :26.3 reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-49-42.841598500Z-5a54af8c.png`
- Diorama :26.3 restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-50-00.299277500Z-f660a0de.png`
- Diorama :26.3 java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-26.3\build\modaccel\shots\2026-10-25T22-50-30.732488Z-d19cb2ae.png`
- Diorama :1.21.1 ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-54-09.902244700Z-d07b8677.png`
- Diorama :1.21.1 ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-54-50.775971200Z-da0b19b6.png`
- Diorama :1.21.1 revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-54-55.409042400Z-d418bfab.png`
- Diorama :1.21.1 world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-55-03.599754200Z-f44cf5e8.png`
- Diorama :1.21.1 reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-56-22.404174400Z-486542d6.png`
- Diorama :1.21.1 restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-56-46.895603300Z-3fb34a5d.png`
- Diorama :1.21.1 java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\diorama-1.21.1\build\modaccel\shots\2026-10-25T22-57-12.518359300Z-901a8cf3.png`
- Architectury API :fabric ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\build\modaccel\shots\2026-10-25T23-03-10.528202800Z-9ff28551.png`
- Architectury API :fabric ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\build\modaccel\shots\2026-10-25T23-04-26.365638500Z-4b1bfedb.png`
- Architectury API :fabric revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\build\modaccel\shots\2026-10-25T23-04-33.090226600Z-2dfaf295.png`
- Architectury API :fabric restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\build\modaccel\shots\2026-10-25T23-08-18.506894100Z-80da3c80.png`
- Architectury API :fabric java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\architectury\build\modaccel\shots\2026-10-25T23-09-18.151648200Z-98875e8d.png`
- In-Game Account Switcher :26.3-fabric ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-13-18.805144Z-070cbdcb.png`
- In-Game Account Switcher :26.3-fabric ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-13-55.792455Z-7b1e7a91.png`
- In-Game Account Switcher :26.3-fabric revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-14-01.756281400Z-8923d998.png`
- In-Game Account Switcher :26.3-fabric reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-17-26.518530Z-fd9112f0.png`
- In-Game Account Switcher :26.3-fabric restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-17-40.353763500Z-98155020.png`
- In-Game Account Switcher :26.3-fabric java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\ias-26.3-fabric\build\modaccel\shots\2026-10-25T23-18-05.524049200Z-dcf21766.png`
- MineColonies reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\minecolonies\build\modaccel\shots\2026-10-26T00-57-57.207094600Z-44d401a6.png`
- MineColonies restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\minecolonies\build\modaccel\shots\2026-10-26T01-02-13.841882600Z-882b31ef.png`
