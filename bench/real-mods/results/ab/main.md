# Real mods through the modaccel loop

Run 2026-10-25T21:22:04-04:00 on win32, modaccel at `80aaf19`, pinned commits. Every mod was cloned fresh; global Gradle cache warm at start (C:\Users\Jk101\.gradle).

| mod | clone | doctor | setup | port | compile | test | ui-check-1 | ui-check-2 | stale-refused | revert-passes | eval | world-check | eval-until | reload | reload-current | reload-revert | game-stop | restart-unchanged | java-ui-check | game-stop-final | nodes-compile | no-leftovers |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AppleSkin `a1c60fc` | 38s | 1.8s | 168s | – | 28s | – | 118s | 3.4s | 3.0s | 5.3s | 5.5s | 18s | **FAIL** 7.7s | 310s | 5.6s | 18s | 9.3s | 46s | 43s | 4.6s | – | 4.1s |
| F3Nope `d65a225` | 22s | 1.7s | 85s | 120s | 6.7s | – | 177s | 3.7s | 2.5s | 3.0s | 5.2s | 17s | **FAIL** 6.6s | 156s | 3.9s | 22s | 8.5s | 67s | 60s | 22s | – | 9.2s |
| Jade `6701b1e` | 42s | 1.4s | 42s | – | 623s | – | 89s | 4.4s | 2.4s | 3.4s | 3.5s | **FAIL** 2.4s | **FAIL** 63s | 40s | 3.7s | 31s | 4.4s | **FAIL** 2.7s | **FAIL** 2.9s | 3.0s | – | 4.3s |

Times are wall-clock seconds per command. `–` means the entry has nothing for that step; `blocked` means an earlier failure left nothing to check. Captures need a look by a person or an agent; nothing here compares pixels.

## Failures and next actions

- **AppleSkin / eval-until** (fail): Game action failed: Snippet did not compile:
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\appleskin\eval-until.log`
- **F3Nope / eval-until** (fail): Game action failed: Snippet did not compile:
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\f3nope\eval-until.log`
- **Jade / world-check** (fail): C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\saves
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\jade\world-check.log`
- **Jade / eval-until** (fail): Game action failed: Still not true after 60 s; last value: false
  - next: Read the step log; the error above is the first thing to fix.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\jade\eval-until.log`
- **Jade / restart-unchanged** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\.runs\portable\2026-10-26T02-05-58.545448100Z-3786b6f6\2-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\jade\restart-unchanged.log`
- **Jade / java-ui-check** (fail): Client startup failed: java.util.concurrent.ExecutionException: java.io.IOException: Client stopped before its first rendered frame (exit 1). Log: C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\.runs\portable\2026-10-26T02-06-04.048697500Z-66939792\2-game.log
  - next: The client did not reach its first frame; read the game log named in the error.
  - log: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\logs\20261025-212204\jade\java-ui-check.log`

## Wrapper distribution already cached

- AppleSkin: True
- F3Nope: True
- Jade: True

## Captures

- AppleSkin ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-26-03.059871600Z-fb247733.png`
- AppleSkin ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-28-00.989622600Z-7a6111f6.png`
- AppleSkin revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-28-10.689484900Z-74fec54e.png`
- AppleSkin world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-28-27.735420400Z-15817433.png`
- AppleSkin reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-34-03.417228600Z-7f6f1d63.png`
- AppleSkin restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-34-36.093806600Z-63813e57.png`
- AppleSkin java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\appleskin\build\modaccel\shots\2026-10-26T01-35-26.276727300Z-6a7f796e.png`
- F3Nope ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-40-13.188147Z-66c59134.png`
- F3Nope ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-43-10.802941Z-b5a0214b.png`
- F3Nope revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-43-18.526936200Z-ad4cdbf7.png`
- F3Nope world-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-43-33.458127100Z-3f557cf8.png`
- F3Nope reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-46-33.589855200Z-5aac38e3.png`
- F3Nope restart-unchanged: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-47-07.397568600Z-e33f48f1.png`
- F3Nope java-ui-check: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\f3nope\build\modaccel\shots\2026-10-26T01-48-27.771578400Z-42a33ff7.png`
- Jade ui-check-1: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-26T02-01-46.686095500Z-e0c78dc6.png`
- Jade ui-check-2: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-26T02-03-15.423927800Z-3d46a412.png`
- Jade revert-passes: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-26T02-03-23.819009800Z-706a6e8c.png`
- Jade reload-current: `C:\Users\Jk101\AppData\Local\modaccel-real-mods\jade\build\modaccel\shots\2026-10-26T02-05-19.978238200Z-cc061c9a.png`
