# Fault matrix - do the tiers fail when they should?

Run 2026-10-22 23:10-23:35 on `C:\Users\Jk101\Projects\modding-accelerator` (Fabric 26.3 lab,
JDK 25 via `.tools\jdk-25*`). One fault at a time in `src/`, reverted with `git checkout -- <file>`
before the next; `git status src` empty at the end.

Clean-tree baseline (all PASS, seconds are the tier's own):

| tier | command | time | result line |
|---|---|---|---|
| fast (lint+logic) | `.\modaccel fast` | 6.3s | `PASS lint 0.6s lint: 3 registrations, all files present` / `PASS logic 0.8s 8 passed` |
| ui | `.\modaccel ui` | 3.9s | `PASS screen 3.9s ... screenshot prismlab-ui.png \| clicked 'More shards' ...` |
| node | `.\modaccel node` | 6.0s | `PASS block 6s PrismNodeEntity.charge() = 64 after 40 ticks in the warm world` |
| world | `.\modaccel world` | 12.7s | `PASS server 12.8s All 5 required tests passed :)` (after the repair in "gaps", see 1) |
| mp | `.\modaccel mp -Steps "place=prismlab:prism_node\|wait=60\|assert=charge\|capture=mp-node"` | 9.7s warm / 56.7s cold | `PASS mp ... assert charge() = 64` |

## Matrix

FAIL = tier failed and named the fault. PASS = tier ran and said nothing was wrong. `-` = not run.

| # | fault (file) | fast/logic | lint | ui | node | world | mp |
|---|---|---|---|---|---|---|---|
| 1 | `PrismMath.lightLevel` loses its clamp (`PrismMath.java`) | **FAIL** 0.8s `[5] 64, 15: AssertionFailedError: expected: <15> but was: <192>` | PASS 0.6s | PASS 2.9s (draws "Light level: 192", nothing asserts it) | - | - | - |
| 2 | client compile error: `this.shardz` (`PrismScreen.java`) | **FAIL** `PrismScreen.java:95 cannot find symbol / symbol: variable shardz` (0 tests run) | - | **FAIL** 1.7s `IllegalStateException: compile failed: PrismScreen.java:95 cannot find symbol symbol: variable shardz` - refused, wrote no PNG | - | - | - |
| 3 | wrong registration id: block key `prism_nod` vs `prism_node` assets (`PrismBlocks.java`) | **FAIL** via lint (logic lane not reached) | **FAIL** 0.5s `lint: block prism_nod has no prism_nod.json` (x2 + item model) | - | PASS 3.2s `charge() = 1 after 40 ticks` (baseline 64) | PASS 12.8s `All 5 required tests passed` | - |
| 4 | dead `@Shadow` field `prismlab$noSuchField` on `MinecraftServer` (`ServerLoadMixin.java`) | PASS (compiles, 8 passed) | PASS | - | - | PASS 13.8s, 5 tests; log still shows `PRISMLAB-MIXIN active tick=0` | - |
| 5 | screen throws in `extractRenderState` (`PrismScreen.java`) | - | - | **FAIL** exit 1, **0 PNGs**, `Unable to read data from the transport connection` + client log `java.lang.IllegalStateException: prism screen: injected render fault` | - | - | - |
| 6 | block never ticks: `getTicker` returns null (`PrismNode.java`) | PASS | PASS | - | **FAIL** 17.2s `AssertionError: PrismNodeEntity.charge() was still 0 after 200 ticks in the world` | - | - |
| 7 | block entity drops `getUpdatePacket`/`getUpdateTag` (`PrismNodeEntity.java`) | PASS | PASS | - | PASS 3.4s `charge() = 1 after 40 ticks` | PASS | **FAIL** only on a cold server: `AssertionError: charge() was 0 at 4, -58, -2 block=Block{prismlab:prism_node}` (8.9s). Warm mp (server started before the edit) PASSed - stale classes. |

Every FAIL above was followed by the revert and a re-run that PASSed again: 1 fast 8 passed, 2 ui PASS,
3 world PASS, 4 (clean tree) fast/ui/node/world PASS, 5 restart then ui PASS, 6 node PASS,
7 cold mp PASS `charge() = 8`. After reverting 7, warm mp kept FAILing until the mp server was
restarted: hot reload cannot bring a removed override back.

Gradle cross-check: clean tree, `.\modaccel fast` PASS (8 passed, lint PASS), then
`.\modaccel restart` PASS 219.7s `stopped, compiled, started`, and a direct
`gradlew runGameTest` with the repo's JDK 25: `BUILD SUCCESSFUL in 21s`, 45 mods including
`prismlab 1.0.0`, `All 5 required tests passed`. The daemon's javac and Gradle agreed on the same tree.

## Gaps found (read this before trusting a tier)

The `world` tier was false-passing when this run started: its replayed launch command
(`.modaccel\gametest-cmd.txt` built from `build\loom-cache\argFiles\runGameTest`) still pointed at
`C:\Users\Jk101\Projects\minecraft-mod-lab`, so the test server loaded 43 mods without `prismlab`
and ran 1 unrelated test - it would have passed with any fault, including faults 3 and 4. Deleting
`build\loom-cache\argFiles\runGameTest` and `runServer` and re-running the Gradle game-test task
rewrote the recipe; it now loads 45 mods with `prismlab 1.0.0`, runs 5 tests, and the numbers above
are from that repaired tier. Also fixed: `Start-WarmClient` took the bridge branch whenever any
`modlabbridge-*.jar` existed in `bench\bridge\build\libs`, even in a repo with its own
`dev\modlab` control mod, so `ui` refused to start ("Gradle did not write the client recipe");
gated on `$bridgeListener` instead (one line, `modaccel.ps1`).

The dead `@Shadow` is caught by nothing: it compiles, lint only checks that the mixin config file
exists, and Mixin 0.8.7 applies the mixin and runs its `@Inject` while silently ignoring the
unlocatable field - `world` logged `PRISMLAB-MIXIN active tick=0` and `All 5 required tests passed`.
A stale shadow is invisible to every tier until something reads it; only a bytecode/apply audit
(e.g. run with `-Dmixin.debug.verbose=true` and fail on "Shadow field ... was not located", or a
check that every `@Shadow` name exists in the target class) would catch it. Related: `world` never
compares a registered id with its assets, so fault 3 is lint-only; `node` passes a wrong id because
the item id still resolves to the renamed block, and its assert only requires `charge() > 0`, so a
drift from 64 to 1 after 40 ticks is reported as PASS. Two more tier weaknesses: a render exception
kills the shared warm client and `ui` reports a raw transport error instead of naming the fault
(no PNG is saved, which is the right half of the behaviour), and `ui` prints the previous run's PNG
paths in its failure output, so a screenshot path is not proof a new frame was written. Finally,
warm tiers can hide a fault that was added before the lab process started, and cannot show a fault
being reverted - fault 7 needed a cold `mp` in both directions.

## Status after the fixes (same day)

| gap from the run | now |
|---|---|
| world false-passed from a stale argFile | fixed: it runs 45 mods / 5 tests |
| ui hijacked by a bridge jar | fixed: the bridge branch is gated on the launch recipe |
| dead @Shadow invisible | `tools\MixinAudit.java` fails lint (+0.17s); found TB's dead target |
| world never checked ids against assets | world asserts blockstates / items / lang and names the missing path |
| node assert only > 0 | exact `-Expect`, otherwise drift against this repo's last passing value |
| ui printed the previous run's PNGs | only files written by this run are listed; a dead client is named |
| mp warm passed on stale classes | refuses and points at `mp -Cold` |

Still open after these: @At/injection-point targets, handler signatures, and mixin remapping
are outside the audit; the pre-build scan counts classes, not member use.
