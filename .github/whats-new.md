## What's new

**Minecraft Modding Tools** is the new name for Modding Accelerator. The command is now `mmt`.

- **A desktop app.** `mmt-desktop` (Windows and Linux) comes in the download. Open your mod, run its checks, change
  its version, check a modpack or speed it up, without a terminal.
- **Switch a mod's loader.** `mmt convert` moves a mod between Fabric and NeoForge on the same Minecraft version, then
  starts both in the game to check the converted one registers and patches the same things.
- **Make modpacks work.** `mmt pack-check --fix` starts Forge, NeoForge, Fabric and Quilt packs on their real servers,
  finds what breaks them and repairs it. 24 of 24 test packs now start.
- **Make mods faster without changing what they do.** `mmt perf` shows which mod costs what in a running game, and
  `mmt perf-optimize` tries a change against the unchanged mod and keeps it only if the game's output stays exactly
  the same. So far: Cyclic's auto-crafter 15.7x, Simple Storage Network 5-6x, Diorama 1.6-2.2x faster.
- **Porting goes further on its own.** `mmt port --fix` uses name maps built from the game's own releases, so more
  mods reach a working build with no hand edits.
- **Tests in the real game,** not just builds: mod state checked in a hidden client, plugins on a real Paper server
  with a bot player.
- **Games start hidden and muted** by default.
