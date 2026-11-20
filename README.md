<p align="center"><img src="docs/logo.svg" width="64" height="64" alt=""></p>

<h1 align="center">Minecraft Modding Tools</h1>

<p align="center">
  <b>Port mods between Minecraft versions, switch loaders, fix broken modpacks, test mods in the real game, and find what makes Minecraft slow.</b>
</p>

<p align="center">
  <a href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop.exe"><img alt="Download for Windows" src="https://img.shields.io/badge/Download_for_Windows-.exe-5d9c3f?style=for-the-badge"></a>
  <a href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop-linux"><img alt="Download for Linux" src="https://img.shields.io/badge/Download_for_Linux-binary-3a6ea5?style=for-the-badge"></a>
  <a href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest"><img alt="All Releases" src="https://img.shields.io/github/v/release/Noisemaker111/minecraft-modding-tools?include_prereleases&style=for-the-badge&label=all+files"></a>
</p>

<p align="center">
  <a href="https://noisemaker111.github.io/minecraft-modding-tools/">Website &amp; Roadmap</a> ·
  <a href="https://noisemaker111.github.io/minecraft-modding-tools/#porting">Porting Map</a> ·
  <a href="SCOPE.md">What Works Where</a> ·
  <a href="https://github.com/Noisemaker111/minecraft-modding-tools/issues">Issues</a>
</p>

## Download

- **Windows:** Download **[`mmt-desktop.exe`](https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop.exe)** and double-click it. No ZIP to extract and no Java install needed (it downloads a verified Java runtime once if your PC doesn't have one).
- **Linux:** Download **[`mmt-desktop-linux`](https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop-linux)**, mark it executable (`chmod +x mmt-desktop-linux`), and run it (requires Java 17+).
- **Command line / macOS:** Grab `mmt-portable.zip` or `mmt.jar` from the [latest release](https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest).

Everything runs locally on your machine with no AI or cloud calls, using official Mojang, Fabric, and NeoForge data plus checked rules.

## What works where

| Feature | Fabric | NeoForge | Forge | Quilt | Paper |
|---|---|---|---|---|---|
| **Port `.jar`** (no source) | 1.14.4 – 26.3 | 1.20.5 – 26.3 | — | — | — |
| **Port source folder** | 1.14.4 – 26.3 | 1.14.4 – 26.3 | — | — | Lists edits |
| **Switch loader** (source) | ↔ NeoForge | ↔ Fabric | — | — | — |
| **Fix modpacks** | All versions | All versions | All versions | All versions | — |
| **Test in real game** | Client & server | Client & server | Loads only | — | 1.8+ bot server |
| **Build & logic tests** | Yes | Yes | Yes | Via Fabric | Yes |
| **Profile performance** | Yes | Yes | Planned | — | — |

## What you can do today

Open the desktop app and drop a mod's `.jar`, a mod's source folder, or a modpack onto the window:

- **Port a mod to another Minecraft version** — Drop a compiled `.jar` (Fabric 1.14.4+ or NeoForge 1.20.5+) or open a source folder, pick the version you want, and get a ported mod back. Source ports can be previewed and undone.
- **Switch a mod's loader (Fabric ↔ NeoForge)** — Open a Fabric or NeoForge source folder to convert it to the other loader on the same Minecraft version, with one-click undo.
- **Fix a broken modpack** — Drop a CurseForge `.zip`, Modrinth `.mrpack`, or launcher instance folder (CurseForge, Prism, MultiMC, vanilla). Runs the pack on its real Forge, NeoForge, Fabric, or Quilt server, flags which mod caused the crash or conflict, and applies proven fixes (with undo).
- **Test a mod or Paper plugin in the real game** — Open a mod or Paper plugin source folder to run unit tests, mixin/data checks, and real-game checks (hidden and muted) without opening Minecraft yourself.
- **Find what makes Minecraft slow** — Start Minecraft normally, attach to it from the **Measure** tab, and see FPS, frame-time spikes, per-mod CPU/memory cost, and hot classes.

## Using the command line

Every feature in the app is backed by the `mmt` command line (in `mmt-portable.zip` or `java -jar mmt.jar`; use `mmt.cmd` on Windows):

```sh
mmt port --jar my-mod-1.21.1.jar --to 1.21.2          # port a compiled .jar (Fabric or NeoForge)
mmt port --root my-mod --from 1.21.5 --to 1.21.6 --fix # port a source folder (undo with --undo)
mmt convert --root my-mod --to neoforge                # switch Fabric <-> NeoForge (source)
mmt pack-check --pack my-pack.mrpack --fix             # check and repair a modpack
mmt ui-check --root my-mod                             # test a mod in a hidden, muted client
mmt perf                                               # profile a running Minecraft game
```

Run `mmt help` for every command and `mmt help <command>` for options.

## Current limits

- **Compiled `.jar` porting** supports Fabric (1.14.4+) and NeoForge (1.20.5+). Switching loaders (`convert`) takes source folders only. Forge, Quilt, and Paper plugins are not ported or converted yet.
- **Anything that can't be updated automatically is listed clearly** instead of guessed.
- **Operating systems:** Game checks are proven on Windows and partly on Linux; macOS game checks are not verified yet.

## More

- [`SCOPE.md`](SCOPE.md) — exact compatibility matrix and verified numbers
- [`ROADMAP.md`](ROADMAP.md) — where the project is going next
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute and build from source
- [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) — common setup traps
