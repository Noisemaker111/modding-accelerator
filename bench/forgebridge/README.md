# forgebridge - screenshot and command a Forge 1.20.1 client that belongs to someone else's mod

The Forge 1.20.1 twin of `bench\bridge` (NeoForge 21.1). Drop one jar into a dev
run's game-dir `mods\` folder and the client opens a loopback control socket, so
the harness can ask it for `root`, a screenshot and `quit`. No source, Gradle file
or launch script of the host mod is touched.

## Build

```
cd bench\forgebridge
.\build.ps1
```

`build.ps1` uses **javac + jar only - no Gradle, no network**. It compiles against
the classpath a dev run has already produced
(`<host>\build\classpath\runClient_minecraftClasspath.txt`, which contains the
mapped Forge 47.1.3 jar) with the JDK 17 copy at
`bench\mods\jdk-17.0.20.1+1`.

Jar: `bench\forgebridge\build\libs\modlabbridge-forge-1.0.0.jar` (8,869 bytes).
The host run has to be built once (`gradlew classes`, or any earlier launch) so
that classpath file exists; after that the bridge rebuilds in about 2 seconds.

Other run: `.\build.ps1 -ClasspathFile <other-repo>\build\classpath\runClient_minecraftClasspath.txt -Jdk <jdk17>`

## Add it to any Forge 1.20.1 dev run

1. Copy the jar into the **game directory** the run uses (`--gameDir`, which for
   ForgeGradle is the repo root, so `<repo>\mods\`).
2. Add the properties to the client JVM:
   `-Dmodlabbridge.port=27699 -Dmodlabbridge.shots=<dir for PNGs> [-Dmodlabbridge.size=1024x640]`
3. Start the client. `MODLABBRIDGE-READY <port>` on stdout means the socket is up.

For this repo that is exactly what the two scripts do:

```
cd bench\forgebridge
.\run-host.ps1                 # copies the jar to bench\mods\minecolonies\mods, replays
                               # bench\mods\forge-launch.txt with CWD = the repo root
.\ask.ps1 root
.\ask.ps1 screenshot title     # -> bench\forgebridge\shots\title.png
.\ask.ps1 quit
```

With `gradlew runClient` instead of a captured launch line, the properties go in
that repo's own `build.gradle` (`runs { client { property ... } }` / `jvmArgs`),
which is the one thing this bridge deliberately does not do for you.

## Protocol

One line in, one JSON line out, a new connection per command.

| command | answer |
| --- | --- |
| `root` | `{"root":"<game dir>","mod":"modlabbridge"}` |
| `ping` | `{"ok":true,"pong":true,"mod":"modlabbridge","port":27699,"root":"..."}` |
| `screenshot [name]` | `{"ok":true,"file":"...\\shots\\title.png","width":854,"height":480,"bytes":471728,"attempts":1}` |
| `quit` | `{"ok":true,"quit":true}`, then the client stops |

Properties: `modlabbridge.port` (default 27699, then `prismlab.lab.port`),
`modlabbridge.shots` (default `<gameDir>\screenshots\modlabbridge`),
`modlab.root` (default the game dir), `modlabbridge.size` (optional `WxH` resize
before the shot; unset keeps the window as it is). Both `::1` and `127.0.0.1` are
bound, because Forge launches with `-Djava.net.preferIPv6Addresses=system`.

## Measured on MineColonies 1.20.1 (Forge 47.1.3, JDK 17.0.20.1)

- replay of `bench\mods\forge-launch.txt`, CWD = repo root, jar in `<repo>\mods\`
- 44.5 s and 45.5 s to `MODLABBRIDGE-READY 27699` (two runs)
- `root` -> `C:\Users\Jk101\Projects\modding-accelerator\bench\mods\minecolonies`, `"mod":"modlabbridge"`
- `screenshot title` -> `shots\title.png`, 854x480, 471,728 bytes, real title screen
- `screenshot big` with `-Dmodlabbridge.size=1024x640` -> `shots\big.png`, 1024x640, 676,930 bytes
- `quit` -> `Stopping!` in the log, process gone

## Limits (v1)

- **Main-thread capture only.** The socket thread parks a request; the client tick
  does the `glReadPixels`. One capture at a time, and it needs a live frame - a
  window that never rendered (headless, minimised after a device loss) answers
  `frame read back black after 8 attempts`.
- **No hot reload.** This jar can only ask; it does not redefine classes. Editing
  the host mod still means a restart, and mixin edits still mean a relaunch.
- **Commands are `root`, `ping`, `screenshot`, `quit`.** No `ui`, no scenario
  step, no click - those are the NeoForge bridge plus LabAgent's job.
- The socket is bound to loopback only, appears only once a screen exists (or
  after 40 ticks), and gives up waiting after 5 minutes.
- Pinned to 1.20.1 / Forge 47.x by the classpath it was compiled against; for
  another version rebuild with that run's classpath file.
- A dev client is a real window: it needs an interactive desktop session and a
  GPU, and it is visible while it runs.
