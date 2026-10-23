# modlabbridge: the lab control socket for a client that belongs to someone else's mod

A tiny NeoForge 1.21.1 client mod whose only job is the lab socket: `root`, `ping`,
`ui <Class>`, `screenshot`, `quit`. It is added to a run through `-Dfml.modFolders`,
so the host mod's sources, build files and launch scripts are never edited.

Verified on this PC: the node's own client (`versions/neoforge-1.21.1`, prismlab)
launched with the bridge alongside it, answered `root` and wrote a real 854x480 PNG,
and the host mod's control socket was switched off in the same launch (`-Dprismlab.lab.port=`),
so every answer came from the bridge.

## Build

```
cd bench\bridge
.\gradlew.bat jar --console=plain
```

Output: `bench\bridge\build\libs\modlabbridge-1.0.0.jar` (about 10 KB).

Borrowed wrapper (`gradlew`, `gradlew.bat`, `gradle\wrapper`) from the NeoForge node;
`gradle.properties` pins the node's own versions (NeoForge 21.1.264, ModDevGradle 2.0.148,
Java 21), so all artifacts are already in the local Gradle cache and the build takes
about 15 s cold, 1 s warm.

## Launch addition (the whole change)

One JVM property, appended to the node's existing dev launch command:

```
-Dfml.modFolders="prismlab%%<node>\build\classes\java\main;prismlab%%<node>\build\resources\main;modlabbridge%%<repo>\bench\bridge\build\libs\modlabbridge-1.0.0.jar"
```

In a `.bat`/`.cmd` every literal `%` must be doubled, so `%%` becomes `%%%%` (this is
exactly what `modaccel.ps1` already does for `prismlab`).

Supporting properties, all optional:

| property | meaning |
| --- | --- |
| `-Dmodlabbridge.port=27699` | bridge port; falls back to `-Dprismlab.lab.port`, then 27399 |
| `-Dmodlabbridge.shots=<dir>` | PNG folder; defaults to `<gameDir>/screenshots/modlabbridge` |
| `-Dmodlab.root=<dir>` | what `root` answers; defaults to the game directory |
| `-Dprismlab.lab.port=` | blank disables the host mod's own control socket |

If the run goes through ModDevGradle instead of a captured command, the same property
goes in the run block (`systemProperty 'fml.modFolders', 'modlabbridge%%<jar>'`); the
`-Dfml.modFolders` form above is the one proven here.

## Reproduce the proof

```
cd bench\bridge
.\gradlew.bat jar --console=plain
.\run-bridge.ps1                        # starts the node's client with the bridge, port 27699
.\ask.ps1 root
.\ask.ps1 screenshot
.\ask.ps1 quit
```

`run-bridge.ps1` replays the node's ModDevGradle recipe
(`build/moddev/clientWarmRunVmArgs.txt`, `.modaccel/neoforge-cp.args`,
`build/moddev/clientWarmRunProgramArgs.txt`) directly, with a throwaway game directory
in `bench\bridge\run`, and waits for the port. Logs: `client.log`, `client.err`.

The listener binds the JVM's loopback address, which is `::1` on this PC, so connect to
`localhost`, not `127.0.0.1`. `ask.ps1 "screenshot name"` names the PNG.

Results from the verified run: `.\ask.ps1 root` →
`{"root":"...\\versions\\neoforge-1.21.1","mod":"modlabbridge"}`;
`.\ask.ps1 "screenshot bridge-shot"` → `shots\shot.png`, 854x480, 228 KB, first attempt.
`.\ask.ps1 "ui com.prismlab.client.PrismScreen"` then `"screenshot host-ui"` opened and
captured the host mod's own screen (`shots/host-ui.png`), which is the case that matters:
a screen belonging to another mod, driven without touching it.

## What the installer must emit

1. Build the jar once: `bench\bridge\gradlew.bat jar --console=plain` (or ship the jar
   and skip Gradle), then use the absolute jar path.
2. Append the `fml.modFolders` entry shown above to the client launch command, with
   `%%` doubled to `%%%%` if it is written into a `.cmd`.
3. Add `-Dmodlabbridge.port=<port>`, `-Dmodlabbridge.shots=<repo>\bench\bridge\shots`,
   `-Dmodlab.root=<project root>`, and blank `-Dprismlab.lab.port` when the host mod has
   its own control socket on the same client.
4. Wait for `MODLABBRIDGE-READY <port>` in the client log (or the port accepting a
   connection on `localhost`) before sending commands; the socket only opens once a real
   screen is up.

## Notes

- `build/`, `.gradle/`, `run/`, `logs/`, `*.log` are ignored by this folder's `.gitignore`.
  The sources are meant to be committed; the repo's root `.gitignore` does not ignore
  `bench/bridge`, so whoever owns that file decides whether to add the line.
- No hot reload in v1: the bridge only drives the client. A source edit in the host mod
  still needs its own loop.
- Screenshot readback comes a few ticks after the resize (a same-frame readback is black),
  with up to 4 attempts, which is why `screenshot` takes a fraction of a second.
