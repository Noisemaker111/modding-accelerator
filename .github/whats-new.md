## What's new

**Minecraft Modding Tools** is the new name for Modding Accelerator.

- **One-file desktop app (`mmt-desktop.exe` / `mmt-desktop-linux`).** Download the standalone app directly from this release and double-click it — no ZIP to extract, no terminal needed, and Windows downloads a verified Java runtime automatically if you don't have one.
- **Port `.jar` mods right in the desktop app.** Drop a compiled Fabric (1.14.4+) or NeoForge (1.20.5+) `.jar` onto the app (or run `mmt port --jar`), pick the target Minecraft version, and get a ported `.jar` back with mixins, overrides, and access transformers updated.
- **Switch a mod's loader.** Move a mod's source between Fabric and NeoForge on the same Minecraft version (in the app or with `mmt convert`), with one-click undo.
- **Fix broken modpacks.** Drop a CurseForge `.zip`, Modrinth `.mrpack`, or launcher instance folder to test it on its real Forge, NeoForge, Fabric, or Quilt server, flag the mod causing crashes or conflicts, and apply proven repairs (`mmt pack-check --fix`, plus `--side client` for NeoForge clients).
- **Find what makes Minecraft slow.** Attach to a running game from the **Measure** tab (or `mmt perf`) to see FPS, frame-time spikes, per-mod CPU/memory cost, and hot classes; compare changes with exact gates using `mmt perf-optimize`.
- **Test mods and plugins in the real game, hidden and muted.** Run unit tests, game-data and mixin checks, hidden client checks, and real Paper server scenarios with a bot player. A program a mod starts from a test game (such as a crash helper) can't open a window on your desktop either.
