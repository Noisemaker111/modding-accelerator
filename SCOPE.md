# Scope: what modaccel supports, and how new things get in

Status: adopted 2026-10-25; platform change, Paper included, became a goal on 2026-11-09. ROADMAP.md holds the steps.

The four goals (README.md) meet this file in the ladder of section 2: rungs 1 to 4 are Goal 1 (test a mod in seconds), rung 5 is Goal 2 (port to any version) and rung 6 is Goal 4 (change loader or platform). Goal 3 (modpacks) takes installed packs, not source projects, so it has no rung.

## 1. Kinds of projects

A project has a **platform** (what it's written against) and a **side** (where it runs).

| Platform | What it is | Players install it? | Examples we've run |
|---|---|---|---|
| Fabric mod | Changes the game itself, through the Fabric loader | Yes, if it has client parts | AppleSkin, F3Nope, Krypton |
| NeoForge mod | Same idea, NeoForge loader (successor of Forge from 1.20.2) | Yes, if it has client parts | ZombieApocalypseAddon, Jade |
| Forge mod | The older loader; most big modpacks up to 1.20.1 | Yes, if it has client parts | MineColonies |
| Quilt mod | Fabric fork; runs most Fabric mods | Yes | none yet |
| Multi-version build (Stonecutter) | One source, many versions and loaders, via comment switches | Yes | Diorama |
| Paper / Spigot plugin | Runs only on a server, through the Bukkit API; does not change the game's code | **No**: vanilla clients join | AgentCraft |
| Proxy plugin (Velocity, BungeeCord) | Runs on the proxy that links servers | No | none |
| Datapack / resource pack | Data and art only, no Java | Data: no; art: offered by the server | none |

| Side | Meaning | Check that proves it works |
|---|---|---|
| Client only | HUDs, minimaps, menus (AppleSkin, F3Nope) | Game client: `ui-check`, `eval` |
| Server only | Plugins, server tweaks (AgentCraft, Krypton) | A server with the project loaded, plus a bot player |
| Both sides | New blocks, items, mobs; both must have the mod (Diorama, ZombieApocalypseAddon) | Singleplayer `world-check`, then `mp-check` (a dedicated server plus a client) |

"Singleplayer vs multiplayer" is really **side**. Singleplayer runs a hidden server inside the client, so a both-sides mod can pass in singleplayer and still break on a real server. That's why `mp-check` exists.

## 2. What "supported" means: the ladder

Each platform climbs these rungs in order. A rung counts only when it passes on a real, untouched project, with a ledger row.

| Rung | Means | Fabric | NeoForge | Forge | Stonecutter | Paper |
|---|---|---|---|---|---|---|
| 1 Build | Fast, warm build of the jar | yes | yes | yes | yes | yes (1.8 s jar) |
| 2 Tests | Logic tests in seconds, no game | yes | yes | yes | yes | yes (all 17 AgentCraft tests, 2-5 s warm) |
| 3 Run check | Loads in the real game/server, screenshot or command proof | yes | yes | partial | yes (26.3 node) | yes, on MockBukkit (a fake Paper server, no real server): `plugin-check` |
| 4 Multiplayer check | Dedicated server plus client, or server plus bot | partial | partial | no | no | yes, real Paper server plus a bot player: `server-check` (bot speaks 26.3 only) |
| 5 Version port | `port --from --to`, routed through hubs | yes (1.14.4+) | yes (1.14.4+) | no | via its nodes | no: config only, done by hand (AgentCraft 26.3 to 1.21.10 passes `plugin-check`); `port` refuses plugins and names the edits |
| 6 Platform change | e.g. Fabric → NeoForge, Forge → Paper | later | later | later | n/a | later: server-side code only |

## 3. Policy

1. **Who we're for.** People who write mods or plugins from source, and modpack users who need their installed mods to work together. Compiled jars and pack exports are planned inputs for compatibility diagnosis; porting a mod still requires its source.
2. **What gets first-class support.** Fabric and NeoForge first, then Forge (old modpacks), then Paper (servers). Quilt rides on Fabric. Proxies and datapacks come later.
3. **When a platform earns the next rung.**
   - A real project someone uses needs it.
   - The bench shows a measured gain.
   - One fixture from that platform joins the merge gate.
4. **Ports between Paper versions: yes.** The Paper API changes slowly; AgentCraft runs on 1.21.10 through 26.3 with config changes only. This is rung 5 and should be cheap.
5. **Plugin ↔ mod moves: yes, as part of Goal 4.**
   - Server-side code can be mapped between a loader's API and the Bukkit API: events, commands, the scheduler, config.
   - Client code, mixins into the game and new blocks or items cannot exist on a plugin server, where vanilla clients join. A move names each of them and stops; it never drops them.
   - Decided 2026-11-09, replacing "no for now" from 2026-10-25. Nothing is built yet (ROADMAP step 39).
6. **Loader and platform changes stay separate from version ports**, with separate claims. This is already decided in ROADMAP.
7. **Every claim carries its rung.** The README and the roadmap page (with its porting map) say which rung a platform reached, never "supports X".

## 4. Intake: what happens when someone brings a new project

1. **Bench it.** Run the `bench-new-mod` skill: their workflow, before/after numbers, blockers, versions.
2. **Classify it.** Platform and side from section 1, and the highest rung it passes today.
3. **Decide.**
   - **Fits a supported platform and passes:** add it to the test set if it covers a new kind of build.
   - **Fits but fails a rung:** the smallest fix goes on a branch, and a ROADMAP step is added with the exact blocker.
   - **New platform:** apply policy 3. If it qualifies, add a ROADMAP step under its goal for rung 1 or 2 only, not the whole ladder.
   - **Out of scope:** say so plainly in the report, with the reason.
4. **Record it.** A ledger row for any port run and the merge gate updated if it became a fixture. Pages regenerate on main from the source records.

## 5. Example: where AgentCraft lands

- Paper plugin, server side, at rung 4.
- Rung 1: `modaccel compile --task :jar` builds the plugin jar.
- Rung 2: `modaccel fast` runs all 17 of its tests, because no Minecraft or loader is on the test classpath.
- Rung 3: `modaccel plugin-check` loads the jar on MockBukkit. It is a fake server: world, entity and network behaviour are not proven.
- Rung 4: `modaccel server-check` starts a real Paper server with the plugin, joins a bot player and runs a scenario with expect steps. Two breaks made on purpose fail at the step that catches them.
- It is the first server fixture in the merge gate (`bench/server-plugins/manifest.json`, run by `tools/tests/ServerPluginFixtures.java`).
- Next: rung 5, `port` between Paper versions (ROADMAP step 28).

What each check covers is in `modaccel help plugin-check` and `modaccel help server-check`; the timings are measures in `ROADMAP.md`.
