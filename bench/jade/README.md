# Jade: why it was not the second optimization target

[Jade](https://github.com/Snownee/Jade) (NeoForge 1.21.1, branch `1.21-neoforge`, commit
`8d5743b9`) was the first mod tried for the second `perf-optimize` session, and was dropped after
one capture. `create-world.java` makes a superflat creative world, `scene.java` builds a ring of
24 things Jade describes 4 blocks around the player (block entities with server data, plain blocks,
still mobs, armor stands) and `look.java` turns the view around the ring at 45 degrees a second, so
Jade raycasts, collects a tooltip and requests server data for a new target every few ticks.

In a hidden client with the full JIT (30 s, kept run `2026-11-15T08-10-19.443625400Z-15fa1749`),
Jade cost 0.33 ms of CPU a second: 1 sample of 121, 0.04 ms per tick. Its work is per frame (the
overlay) and per client tick (one raycast and tooltip), and a hidden client presents about 8 frames
a second, so there was nothing to measure. Mods whose cost is per server tick or on worker threads
are measured the same hidden or visible; Pipez (`bench/pipez`) was optimized instead.
