# Scope: what modaccel supports, and how new things get in

Status: adopted 2026-10-25. ROADMAP.md holds the steps; platform-change for Paper is still open.

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
| 2 Tests | Logic tests in seconds, no game | yes | yes | yes | yes | needs the plain-test fix |
| 3 Run check | Loads in the real game/server, screenshot or command proof | yes | yes | partial | yes (26.3 node) | no (needs a server check) |
| 4 Multiplayer check | Dedicated server plus client, or server plus bot | partial | partial | no | no | no (bot exists in their repo) |
| 5 Version port | `port --from --to`, routed through hubs | yes (1.14.4+) | yes (1.14.4+) | no | via its nodes | no (often just config) |
| 6 Platform change | e.g. Fabric → NeoForge | later | later | later | n/a | **decide** |

## 3. Proposed policy (decide)

1. **Who we're for.** People who write mods or plugins from source. Not players, and not compiled jars.
2. **What gets first-class support.** Fabric and NeoForge first, then Forge (old modpacks), then Paper (servers). Quilt rides on Fabric. Proxies and datapacks come later.
3. **When a platform earns the next rung.**
   - A real project someone uses needs it.
   - The bench shows a measured gain.
   - One fixture from that platform joins the merge gate.
4. **Ports between Paper versions: yes.** The Paper API changes slowly; AgentCraft runs on 1.21.10 through 26.3 with config changes only. This is rung 5 and should be cheap.
5. **Plugin ↔ mod conversion: no for now.**
   - A plugin uses the Bukkit API, while a mod changes the game directly. Converting means rewriting, not porting.
   - Hybrid servers (Arclight and similar) already run plugins next to mods.
   - Revisit only if real demand shows up.
6. **Loader changes (Fabric ↔ NeoForge) stay separate from version ports**, with separate claims. This is already decided in ROADMAP.
7. **Every claim carries its rung.** The README, the bench page and the porting map say which rung a platform reached, never "supports X".

## 4. Intake: what happens when someone brings a new project

1. **Bench it.** Run the `bench-new-mod` skill: their workflow, before/after numbers, blockers, versions.
2. **Classify it.** Platform and side from section 1, and the highest rung it passes today.
3. **Decide.**
   - **Fits a supported platform and passes:** add it to the test set if it covers a new kind of build.
   - **Fits but fails a rung:** the smallest fix goes on a branch, and a ROADMAP step is added with the exact blocker.
   - **New platform:** apply policy 3. If it qualifies, add a ROADMAP phase step for rung 1 or 2 only, not the whole ladder.
   - **Out of scope:** say so plainly in the report, with the reason.
4. **Record it.** A ledger row for any port run, the pages regenerated (AGENTS.md rule), and the merge gate updated if it became a fixture.

## 5. Where AgentCraft lands

- Paper plugin, server side, at rung 1 today.
- Next is rung 2: the plain-test fix, about half a day, measured at 2–4 s against 8–11 s.
- Then rung 3: a Paper server check, an estimated 2–3 days.
- It becomes the first server fixture in the merge gate.
