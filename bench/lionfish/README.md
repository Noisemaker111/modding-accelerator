# Lionfish API's fluid standing in All the Mods 9

Found on a server, not a single-player game: `mmt pack-run --players 4 --commands bench/atm9-server/base.commands` (All
the Mods 9 1.1.1's own server, four stand-in players each at a base with 230 mobs) ran at 6.3 ticks a second, and
Lionfish API (Cataclysm's library) was the mod with the most of each tick: 4.4 ms. With one player it was 1.7 ms.

Lionfish API 2.7 (LGPL, [lender544/Lionfish-API](https://github.com/lender544/Lionfish-API)) modifies the movement of
every living entity in `Entity.move` (`EntityMixin.fluidCollision`), so a mob can stand on a fluid when a listener of its
`StandOnFluidEvent` says so (in this pack, only Cataclysm, for a player in Ignitium Boots over lava). Every time a mob
moves down, which a mob on the ground does every tick, it allocates a table of 12 offsets and looks up the fluid at each
offset from the mob's block. The offsets are 0, 0.5 and -0.5 added to whole block coordinates and floored, so they reach
only 6 distinct blocks.

`distinct-blocks.diff` visits those 6 blocks once each, in the order the 12 offsets first reach them, from a static
table with one `MutableBlockPos`, and makes the mob's inflated box only when a block has fluid. A repeat never won the
strict comparison, so the fluid it finds and the height it returns are the same. Switch: `FluidStanding.DISTINCT_BLOCKS`
(on).

| Run | Verdict | CPU of the handler (ms a second), cold | Warm |
|---|---|---|---|
| `2026-11-16T18-36-39.784348200Z-bd57704f` | accepted | 2.66x (95% 2.16-3.29): 9.65 → 3.68 | 2.36x (95% 1.91-2.94): 13.7 → 6.17 |

All the Mods 9 client with 900 cows penned beside the player, both arms at once in their own client, 4 pairs, on a shared
machine: approximate. Scopes: Lionfish API's allocation 4.97x (cold) and 6.09x (warm) less; the integrated server's whole
tick 0.98x (95% 0.95-1.01), within the spread between launches. Same game before (`perf-toggle` with `toggle.java`,
1,008 armor stand spots, the whole `Entity.move`): 1.20x (99% 1.05-1.37), same results.

Gate (`exact`, every launch of both arms): `standing.java` builds a rig 300 high over the player (a lava pool, still
water, a flowing slope, stone) and moves armor stands at whole, half and odd spots over it down once, bare and in
Ignitium Boots; their heights, ground flags and fall distances are the same text in every launch.

## Run it

1. The pack and world as in `bench/modernfix/README.md`.
2. Lionfish API at what the pack ships: `git clone https://github.com/lender544/Lionfish-API .runs/lionfish`, then commit
   `setup.diff` (version 2.7, and compileJava never from Gradle's build cache, which leaves the mixin refmap out; see
   KNOWN_ISSUES.md). Both arms share it.
3. `mmt perf-optimize --root .runs/lionfish --experiment bench/lionfish/experiment.json --patch bench/lionfish/distinct-blocks.diff`
