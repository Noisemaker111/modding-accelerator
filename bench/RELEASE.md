# Release v1.0.0 — prepared, not published

Jon publishes. Nothing here has been pushed, tagged or uploaded.

## Asset

| what | value |
|---|---|
| asset file | `bench\modaccel-v1.0.0.zip` (0.90 MB, 213 entries) |
| sha256 | `5748BF5DF7B958C959873DA4E5581F170240F8CC076C04A0AAB452B63FCC5955` |
| suggested tag | `v1.0.0` |
| what is inside | the harness at the zip root: `modaccel.ps1`, `modaccel.cmd`, `install-modaccel.ps1`, `bin\modaccel.cmd`, `tools\`, `knowledge\`, `versions\`, `scenarios\`, `src\`, the Gradle wrapper |

## Install, one line

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://github.com/Noisemaker111/modding-accelerator/releases/latest/download/install-modaccel.ps1 | iex"
```

It downloads the zip, extracts to `%LOCALAPPDATA%\modaccel`, writes `%LOCALAPPDATA%\modaccel\bin\modaccel.cmd`,
adds that folder to the user PATH once (no admin), and prints the next commands. Running it again is
safe. No release yet? `install-modaccel.ps1 -Zip <path to zip>` does the same from a local file.

After it, from any folder:

```powershell
modaccel -Repo C:\mods\foo fast
modaccel -Repo C:\mods\foo port -Check -To 1.21.6
```

The first run downloads Temurin and JBR into `%LOCALAPPDATA%\modaccel\.tools` (about 280 MB, once);
every repo after that reuses them.

## Publish (Jon runs this)

```powershell
gh release create v1.0.0 bench\modaccel-v1.0.0.zip install-modaccel.ps1 --title "modaccel v1.0.0" --notes "One command for checking a Minecraft mod, installable once: type modaccel from any repo, -Repo picks it. Windows, PowerShell 7."
```

Both assets matter: the zip is what the installer extracts, and `install-modaccel.ps1` is what the
one-line command fetches from `releases/latest/download/`.

## How this zip was built (rebuild it the same way)

```powershell
$stage = "$env:TEMP\rel\modaccel-1.0.0"
New-Item -ItemType Directory -Force $stage | Out-Null
git archive --format=tar public | tar -x -C $stage
Copy-Item modaccel.ps1, install-modaccel.ps1 -Destination $stage -Force
Copy-Item bin -Destination $stage -Recurse -Force
Remove-Item "$stage\.lab" -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem $stage -File -Force -Filter '.*.err' | Remove-Item -Force
Compress-Archive -Path "$stage\*" -DestinationPath bench\modaccel-v1.0.0.zip -CompressionLevel Optimal
```

Two reasons for the overlay instead of a bare `git archive public`:

- `public` does not have this version of `modaccel.ps1`, `install-modaccel.ps1` or `bin\` yet.
- `bin/` is in `.gitignore` (it is Eclipse's output folder there), so `git archive` never carries
  `bin\modaccel.cmd`. The installer writes that shim itself, so a release without it still works.

`.lab` (a stale `last-compile.txt`) and the committed `.*.err` logs are left out of the asset.

## What v1.0.0 changes

- `-Repo <path>` (alias `-Target`) runs any verb against another repo. The harness stays where it is
  installed: `tools\` and `knowledge\` resolve next to the script, `.modaccel` and `.runs` stay in the
  repo being checked. Without `-Repo` the two are the same folder, so nothing changes in place.
- `port -Check` is a switch now and reads the repo from `-Repo`; `port -Check <path>` still works.
- `bin\modaccel.cmd` finds `modaccel.ps1` from its own location, so it can be copied anywhere.
- `install-modaccel.ps1`: one call, no admin, idempotent.
