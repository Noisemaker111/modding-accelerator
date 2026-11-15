# Extending pack-check

`pack-check` runs a modpack on its own loader's real server and checks what happens. It is built from five registries;
a new loader, input, check or repair goes into one of them, not into the core (`tools/cli/PackCheck.java`).
`modaccel pack-check --list` prints what each registry holds.

| Part | Where | What it decides |
|---|---|---|
| Inputs | `tools/cli/PackInputs.java` (`ALL`) | How a pack is read: CurseForge export, Modrinth `.mrpack`, CurseForge app instance, Prism/MultiMC instance, vanilla launcher folder, bare `mods/` folder |
| Loaders | `knowledge/pack-loaders.json`, `tools/cli/PackLoaders.java` | How a loader's server is installed (its official installer), started, and which names and metadata files it uses |
| Mod metadata | `tools/cli/PackMods.java` | What each jar declares (`mods.toml`, `neoforge.mods.toml`, `fabric.mod.json`, `quilt.mod.json`, bundled jars), plus class and mixin indexes for naming a crashing mod |
| Checks | `tools/cli/PackChecks.java` (`ALL`) | Findings, before the game (`static`) and from the server starts (`new-world`, `reopen`, `user-world`) |
| Repairs | `knowledge/pack-fixes.json`, `tools/cli/PackRepairs.java` | Proven fixes: a match, an action and a test |

The probe (`tools/packcheck/ModaccelPackProbe.java`) is one loader-neutral mod. It is written in Mojang names against
the vanilla server and reaches the loader only by reflection. pack-check compiles it once per game version and gives
it the metadata file the loader reads. It renames it to the loader's run-time names. Forge and NeoForge use SRG
when the installed game jar has SRG method names (Forge 1.20.1 does), otherwise Mojang names. Fabric and Quilt use
intermediary.

Run `modaccel self-test --only pack-check` after any change; it needs no network and no game.

## Add a game or loader version

Nothing to add. Versions come from each loader's metadata at run time, the game from Mojang's version manifest, and
the Java version from the game's own `version.json`. A pack that names no loader version gets the newest stable one.
If one game version is published under other coordinates (NeoForge for 1.20.1 is), add a `byMinecraft` entry to that
loader in `pack-loaders.json`.

The probe compiles against the game API from 1.16 to 26.x, as Java 8 source (1.16 servers run on Java 8). Every game call that changed between versions is in a
game layer, `tools/packcheck/game/VERSION/ModaccelPackGame.java`, and pack-check compiles the probe with the layer
whose VERSION is the newest at or below the pack's. A version whose API moved again fails at `probe-compile` with
javac's error: add a layer named after the first version with the new API, with the same methods as the others, and
list it in `tools/cli/Installation.java`. Keep loader-specific code out of the layers.

## Add a loader

Add an entry under `loaders` in `knowledge/pack-loaders.json`:

- `style`: `args-file` (an installer that writes `libraries/**/win_args.txt`, like Forge and NeoForge) or `launch-jar`
  (an installer that writes a launch jar beside `server.jar`, like Fabric and Quilt).
- `aliases`: the names packs use for it (`.mrpack` dependency keys, Prism component uids, launcher library groups).
- `versions`, `versionPrefix` or `installers`, `installer`, `install`: where its versions and installer come from, and
  the installer's arguments (`{mc}`, `{version}` and `{dir}` are filled in). Downloads are checked against the SHA-1 the
  maven or metadata publishes.
- `metadata`: the files it reads in a mod jar, most preferred first, with `from`/`below` game versions where that changes.
- `builtIn`, `failed`, `modNamed`, `clientOnly`: mod ids it provides itself, words that mean a start failed, patterns
  whose first group is a mod id it blames, and words that mean a mod reached client-only code on a server.

A loader that needs an exact Java build gets a rule under `javaBuilds`: the JDK member (`call`) its jars under
`libraries/<libraries>` call, and the Temurin `release` that still has it. It applies when the game's Java as installed
lacks that member; the release is downloaded and must match both the vendor's API SHA-256 and its published checksum file.

A loader that fits neither style needs a `Style` subclass in `PackLoaders.java`, added to the `switch` in `all()`.
If it reads a metadata format pack-check does not know, add a reader in `PackMods.read` and the probe's own file
beside `ModaccelPackProbe.java`. If its server keeps the running game somewhere new, add that lookup to `findServer`
in the probe. If its run-time names differ, return them from `names()`. Add an alias and a launch test to
`PackCheckTest.loaders`, then prove the loader on a real public pack (below).

## Which jars a server gets

`PackCheck.serverMods` decides, from the pack's side lists and the loader's own metadata. A jar marked client-side
(CurseForge, Modrinth, the `.mrpack`'s `env`, Fabric's `environment`) is left out, unless a kept mod requires a mod id
only it provides: then it is kept and the loader decides (Fabric and Quilt keep an `environment: client` jar out of the
game and treat a dependency on it as met; Forge runs it). A jar a start showed to be client-only (`client-only.json`)
cannot run on a server, and a mod that requires an id only such jars provide is left out with it, through its
dependants. A jar no list or metadata gives a side is client-only when its Fabric or Quilt server entrypoint reaches,
as it initialises, a game class the installed server does not have (`PackMods.clientCode`). The result's
`game.leftOut` and `game.keptForDependants` say why for each jar.

## Add a pack input

Implement `PackInputs.Input` and put it in `ALL` before any more general shape it overlaps with. `accepts` looks for a
file only that shape has. `read` returns a `PackCheck.Pack`: game version, loader and version (through `loader()` and
`overridden()`, so `--loader`/`--minecraft` still win), the mod jars with their sides, and the folders configs come
from (`layers`). Downloads must be verified by hash. Add a case to `PackCheckTest.inputs`.

## Add a check

Add a `new Check(id, stage, what, body)` to `PackChecks.ALL`. The id is the finding's `kind`. The body reads the
`Context`: the pack, the scan, the loader, and for game stages the start's result (`opened`, `probe`, `failure`,
`suspects`). It reports with `c.add(kind, severity, summary, ...)`:

- `problem` fails the run. Use it only for something the game showed.
- `warning` is a prediction from metadata. `loader-verdict` turns it into a problem when the loader stopped on that jar,
  and into a note when the loader loaded it anyway. Put the jar the finding is about first in `jars`.
- `note` is worth knowing and never fails the run.

If the check needs something new from the game, add it to the probe's JSON, and keep the probe loader-neutral. Add
a case to `PackCheckTest` with a real log or jar layout.

## Add a repair

A repair is an entry in `knowledge/pack-fixes.json`, added only after a real run has proven it:

```json
{
  "id": "short-kebab-id",
  "loader": "neoforge", "minecraft": ["1.21.1"],
  "match": { "type": "mod-below", "mod": "modid", "below": "1.2.3" },
  "check": "world-reopen",
  "symptom": "What the player sees.", "cause": "Why, in one or two sentences.",
  "evidence": ["the upstream issue or changelog", "the pack-check run that proved it: pack, versions, date, what each lane showed"],
  "action": { "type": "replace-mod", "mod": "modid", "file": "modid-1.2.3.jar", "url": "https://cdn.modrinth.com/...", "sha512": "...", "page": "...", "license": "..." },
  "test": { "jars": [ { "file": "a.jar", "mods": [ { "id": "modid", "version": "1.2.2" } ] } ], "added": { "mods": [ { "id": "modid", "version": "1.2.3" } ] } }
}
```

- `match.type`: `mod-below` (the version the loader uses is older than `below`), `mod-range` (it is inside `range`),
  `mods-together` (all of `mods` are present), `dependency-unmet` (`mod` requires `dependency` in a range the pack's
  version misses) or `dependency-missing` (`mod` requires `dependency` and no jar provides it). The loader uses the
  highest version of an id anywhere, bundled or not.
- `action.type`: `add-jar`, `remove-mod` or `replace-mod`. Downloads are checked against `sha512`.
- `check`: the finding kind the repair is proven against; the repaired lane must not show it.
- `test`: jars written in the loader's own metadata format that must match before the action and must not after it.
  Bundled jars go in `bundles`. `self-test` runs every entry's test.

### Rules

A rule is a repair for a kind of fault, not one mod: `"loader": "*"` (or a list) and `"minecraft": ["*"]`, so a new pack
hits it with nothing added. Its action finds the file at run time and checks it before use:

| Match | Action | What it does |
|---|---|---|
| `unavailable-file` | `substitute-file` | A CurseForge file the pack lists that CurseForge no longer serves: the same project's file for the pack's game version and loader, nearest newer first, checked against CurseForge's size, its own metadata and Modrinth's SHA-1 where Modrinth has it. None: the file is dropped from the fixed pack with a note. |
| `dependency-missing` with `"mod": "*"` | `fetch-dependency` | A library no jar provides: the version the dependant's own Modrinth release names (by the dependant jar's SHA-1), else the oldest Modrinth release in every dependant's range; checked against Modrinth's SHA-512 and its own metadata. |
| `dependency-unmet` with `"mod": "*"` | `fetch-dependency` (`"replace": true`) | A library in a version a dependant does not accept: pinned the same way, replacing the pack's copy. |
| `start-failure` (`patterns`, `side`) | `leave-out-of-server` | A mod a pattern finds stopping the server at start-up, which its Modrinth page or the `.mrpack` says a server may go without: the server leaves it out, players keep it (a fixed `.mrpack` marks it `env.server: unsupported`). |

`"when": "start-failed"` matches on a failed start instead of the jars: the failing lines of its log
(`PackRepairs.failing`, not the mod list a crash report prints), so `dependency-*` rules only take a mod id the failure
names. Such a rule's test gives that `log`; an `unavailable-file` test gives `unavailable` (`project`, `file`); a rule's test
names its own `loader` and `minecraft`, and a jar in it can carry `serverSide`.

With `--fix`, repairs known from the jars run beside the shipped pack; then, at most three times, a start that failed
(the repaired pack's, else the shipped one's) is matched against the `start-failed` rules, and a new repair joins the
others for one more repaired start. `fix.rounds` and `fix.notes` say what each round added and did.

A new matcher or action is a small function in `PackRepairs.java` (`MATCHERS`, `ACTIONS` and `change`).

A start is judged by its log, not a fixed time: it is stuck when its log has not grown for `--timeout` seconds (default
600; the probe logs every 30 s while it places), and a ceiling of 30 minutes plus 8 s per mod past 100 stops one that
never finishes. A placed block whose block entity cannot be sent to players (which on a real server stops it at the next
tick) is taken out again and reported as `block-entity-unsendable`; a placed block that still had a scheduled tick when
the world saved and changed when it ran is `settled-after-save`, a note, not `changed-by-save`.

To prove a repair, run `modaccel pack-check --pack PACK --fix`. pack-check starts the repaired pack's server beside
the shipped one, and compares them check by check (`fix.comparison`). It changes the user's pack only when the
repaired one makes a world, opens it again, has no problem of the repair's `check` kind, and has no problem the
shipped pack does not. An instance folder is changed in place (`--undo` restores it). An export or `.mrpack` gets a
fixed copy beside the receipt.
