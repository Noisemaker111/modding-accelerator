# Two real mods against the harness

Measured 2026-10-22 on this PC. Clones, logs and JDKs live under `bench/mods/` (gitignored).
Baselines ran with the mods' own wrappers and JDKs: diorama on JDK 25, minecolonies on a Temurin 17
downloaded for this bench (`bench/mods/jdk-17.0.20.1+1`), because none was installed.

| mod | repo / branch | license | build system | MC target | loaders | baseline | bump | errors |
|---|---|---|---|---|---|---|---|---|
| diorama | SunkenInTime/diorama @ master 0bfb25c | MIT | Gradle 9.7.1, Stonecutter 0.9.8, Loom 1.18-SNAPSHOT, NeoForge moddev 2.0.148, JDK 25 | 26.3 active (nodes 1.21 → 26.3, 21 of them) | Fabric + NeoForge | `:26.3:build -x test` **PASS 204.5 s** | no newer MC node exists; fabric-api pin 0.162.0 → 0.163.0 **PASS 8.2 s** | 0 |
| minecolonies | ldtteam/minecolonies @ version/main d1573f78 | GPL-3.0 | Gradle 8.1.1, ForgeGradle via remote `OperaPublicaCreator/gradle/mod.gradle`, JDK 17 | 1.20.1 (Forge 47.1.3) | Forge | `build -x test` **PASS 328.3 s** (67.1 MB jar) | next branch version/1.20.4 (MC 1.20.4, Forge 20.4.200, Gradle 8.8) **FAIL at 56 s** | 1 Gradle error, 0 javac |

## What blocks adoption

Diorama builds green in 3.4 minutes and is the closest thing to a target user, but the harness
cannot install into it as-is: `install.ps1` reads `minecraft_version` and `fabric_api_version` from
the root `gradle.properties`, and a Stonecutter repo keeps those per node, so it printed
`install: mod 'diorama' package dev.diorama.fabric (Minecraft )` and wrote no Gradle wiring, only
instructions to paste a block by hand.

That block does not compile either. Pasted into `build.unobfuscated.gradle.kts` it failed in 188 s
with 8 Kotlin script errors: `property`, `vmArg` and `ideConfigGenerated` in the `loom { runs { } }`
config are deprecated-as-error under Loom 1.18, and `rootProject.file(...).asFile` is unresolved.

The installer also drops the client bridge into `src/client/java/dev/modlab`, which diorama never
compiles (it has no `splitEnvironmentSourceSets()` and no `src/client` source set), and its
`fabricApi` block turns `enableGameTests` on while diorama already runs client game tests through
its own block, so the two fight.

Minecolonies is outside the harness's shape entirely: `install.ps1` exits 1 with "no
src/main/resources/fabric.mod.json ... is this a Fabric mod repo?", and its build system is
ForgeGradle driven by a floating `7.0.+` NeoGradle pin fetched from a raw GitHub URL, so there is no
`loom-cache` and no Loom run config to hook.

Both real failures were dependency and build-script failures, not javac: `modaccel port` reported
"0 error(s) in 0 file(s)" on each log, because it only parses `<file>.java:line: error:` lines. The
one minecolonies failure worth quoting is a version-drift wall on the newer branch: `Could not
resolve net.neoforged.gradle:userdev:7.0.+` — resolved 7.0.192 declares
`org.gradle.plugin.api-version 8.13` while that branch's wrapper is 8.8.

A second wall is JDK selection: minecolonies' Gradle 8.1.1 refuses JDK 21 outright
(`Unsupported class file major version 65` at the settings script, 7 s), so a machine that only has
the JDKs this lab ships (21 and 25) cannot build the mod at all until a 17 is fetched.

## Installability, per question

| question | diorama | minecolonies |
|---|---|---|
| root build script to append the block to | no (Stonecutter nodes only; installer prints a paste block instead) | yes, `build.gradle`, one line applying a remote script |
| Stonecutter | yes, 21 nodes, per-node `versions/<v>/gradle.properties` | no |
| client-only entrypoint | yes, `environment: client`, only a `client` entrypoint | no, Forge `mods.toml`, server+client |
| run configs per node | none shipped; each node gets its own project, so one run config per node is needed | one Forge run config (ForgeGradle `runClient`) |
| installs as-is | no — manual paste, and the pasted block fails to compile (8 errors) | no — installer exits 1 at the fabric.mod.json guard |
