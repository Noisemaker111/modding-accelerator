# Zombie siege: accept or reject a change to a real mod

A complete `perf-optimize` experiment for
[Zombie Apocalypse Addon](https://github.com/Riqqqque/ZombieApocalypseAddon) 2.8.0
(NeoForge 1.21.1, commit `1bb67d4a487f57c3f005c73f35ebf60b1370c684`). It measures the
mod's per-entity tick handler while 224 zombies besiege a player with the mod's
block breaking, block placing and towering turned on, and decides `gating.diff`.

`gating.diff` is a candidate written by hand from the perf report. Breaking, placing
and towering each ran their shared start-day check (several config reads and the
overworld day) for every zombie on every tick, before their own per-zombie schedule,
which lets one tick in 20 or 100 through. The diff checks the schedule first, stops
re-deriving "is this a zombie-class mob" inside the AI check that the handler has
just made, and skips the registry lookup when the include/exclude lists are empty.
All three moved checks are free of side effects, so every decision is unchanged.

## Run it

Windows uses `modaccel.cmd`. From the Mod Accel checkout:

```sh
git clone https://github.com/Riqqqque/ZombieApocalypseAddon .runs/mods/zombie
git -C .runs/mods/zombie checkout 1bb67d4a487f57c3f005c73f35ebf60b1370c684

# Once: make the starting save (superflat, fixed seed, no natural spawning).
./modaccel ui-check --root .runs/mods/zombie
./modaccel eval --root .runs/mods/zombie --file bench/zombie-siege/create-world.java
./modaccel eval --root .runs/mods/zombie --until "mc.player != null" --timeout 120
./modaccel game-stop --root .runs/mods/zombie
mkdir -p .runs/zombie-siege && cp -r .runs/mods/zombie/runs/client/saves/siege .runs/zombie-siege/siege

./modaccel perf-optimize --root .runs/mods/zombie --experiment bench/zombie-siege/experiment.json \
  --patch bench/zombie-siege/gating.diff --runs 5 --dry-run --out .runs/zombie-siege/result
```

Each launch copies `siege.toml` (siege features on, action chances 1.0, the mod's own
spawners, hordes and blood moons off) and
`client-options.txt` (no pause on lost focus, no tutorial) into the arm, opens a fresh copy
of the save and runs `siege-setup.java`. The metric is the exact inclusive wall time
of `EventHandler.onEntityTick` per server tick (`probes-handler.json`), cold and warm.

## Gates

- `siege-behaviour` (expect `true`): after both captures, the player alive (resistance
  V blocks all damage), at least 200 zombies, all inside the pen, at least 80%
  targeting the player, and at least one zombie tower or a broken pillar block.
- `decisions` (exact): a SHA-256 of every entity type's zombie-class result, the AI
  eligibility of each zombie type, and 2,400 break/place/tower decisions by synthetic
  zombies beside a plank wall over 100 consecutive game times, including every block
  position the mod asked permission to break or place. Permissions deny and the
  synthetic entities are never added, so the mod edits nothing.
- `frame` (0 different pixels): the 1280x720 framebuffer looking down at untouched
  grass outside the pen, with entities, items and inventory cleared and chat faded first.

The live siege diverges between launches (mob AI uses the world's random), so it is
checked by assertions; exact output is checked where inputs can be frozen. Chances of
1.0 keep the random roll from deciding outcomes in the `decisions` trace. These gates
cover the changed paths, not every behaviour of the mod.
