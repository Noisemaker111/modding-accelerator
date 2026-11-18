#requires -Version 7.0
<#
  install-mmt.ps1 - one call, no admin, no clone.

    powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/install-mmt.ps1 | iex"

  It downloads the harness release zip (or a zip you point it at), extracts it to
  %LOCALAPPDATA%\mmt, writes the mmt.cmd shim into %LOCALAPPDATA%\mmt\bin, adds that
  folder to your USER PATH if it is not there yet, and prints what to type. Running it twice is
  safe: the same files are overwritten, the PATH entry is added once, and downloaded JDKs in
  %LOCALAPPDATA%\mmt\.tools are left alone.

  Options (only when run as a script, not through `irm | iex`):
    -Zip <path|url>   a local zip or an http(s) URL instead of the latest release asset
    -Install <dir>    where to put the harness (default %LOCALAPPDATA%\mmt)
    -Repo <owner/name>  a different GitHub repo to read releases from
    -NoPath           extract only; do not change PATH
#>
param(
	[Parameter(Position = 0)][string]$Zip = '',
	[string]$Install = '',
	[string]$Repo = 'Noisemaker111/minecraft-modding-tools',
	[switch]$NoPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not (Get-Command pwsh -ErrorAction SilentlyContinue)) {
	Write-Host 'mmt runs on PowerShell 7 (pwsh). Install it first: winget install Microsoft.PowerShell' -ForegroundColor Red
	exit 1
}

if (-not $Install) { $Install = Join-Path $env:LOCALAPPDATA 'mmt' }
$Install = [System.IO.Path]::GetFullPath($Install.TrimEnd('\'))
$binDir = Join-Path $Install 'bin'

# --- where the zip comes from -------------------------------------------------
# A local file, a URL, or the release asset of the repo this script came from. No zip is guessed:
# if there is no release yet, say so and name the flag that fixes it.
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mmt-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $temp | Out-Null
$archive = $null
$label = ''

try {
	if ($Zip -and (Test-Path -LiteralPath $Zip -PathType Leaf)) {
		$archive = (Resolve-Path -LiteralPath $Zip).ProviderPath
		$label = "local $archive"
	}
	elseif ($Zip -match '^https?://') {
		$archive = Join-Path $temp 'mmt.zip'
		Invoke-WebRequest -Uri $Zip -OutFile $archive -UseBasicParsing
		$label = $Zip
	}
	elseif ($Zip) {
		Write-Host "No such zip: $Zip" -ForegroundColor Red
		exit 2
	}
	else {
		$latest = $null
		try {
			$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'mmt-install' } -TimeoutSec 30
		}
		catch {
			Write-Host "Could not read the latest release of $Repo ($($_.Exception.Message))." -ForegroundColor Red
			Write-Host 'Download the zip by hand and run this again with -Zip <path to zip>.' -ForegroundColor Yellow
			exit 1
		}
		$asset = @($latest.assets | Where-Object { $_.name -like '*.zip' }) | Select-Object -First 1
		if (-not $asset) {
			Write-Host "Release $($latest.tag_name) of $Repo has no .zip asset. Run this with -Zip <path to zip>." -ForegroundColor Red
			exit 1
		}
		$archive = Join-Path $temp $asset.name
		Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archive -UseBasicParsing
		$label = "$Repo $($latest.tag_name) : $($asset.name)"
	}

	# --- extract ---------------------------------------------------------------
	$stage = Join-Path $temp 'stage'
	Expand-Archive -LiteralPath $archive -DestinationPath $stage -Force
	# A zip made with `git archive --prefix` has one top folder; a plain one does not.
	$scriptInStage = Join-Path $stage 'mmt.ps1'
	if (-not (Test-Path $scriptInStage)) {
		$inside = @(Get-ChildItem $stage -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'mmt.ps1') }) | Select-Object -First 1
		if ($inside) { $stage = $inside.FullName; $scriptInStage = Join-Path $stage 'mmt.ps1' }
	}
	if (-not (Test-Path $scriptInStage)) {
		Write-Host "That zip has no mmt.ps1 at its root; it does not look like a mmt release." -ForegroundColor Red
		exit 1
	}

	# --- install ---------------------------------------------------------------
	# Copies over the same folder, so a second run is the same state. Nothing is deleted: .tools
	# (the downloaded JDKs) and anything else already there stays.
	New-Item -ItemType Directory -Force $Install, $binDir | Out-Null
	Get-ChildItem $stage -Force | ForEach-Object {
		Copy-Item $_.FullName -Destination $Install -Recurse -Force
	}

	# The shim is written here even when the zip carries one, so the installed path layout is
	# always <install>\bin\mmt.cmd next to <install>\mmt.ps1.
	$shim = @(
		'@echo off'
		'rem mmt - installed shim. It finds mmt.ps1 next to its own folder, wherever that is.'
		'setlocal'
		'set "HARNESS=%~dp0.."'
		'if exist "%HARNESS%\mmt.ps1" goto run'
		'echo mmt: no mmt.ps1 in %HARNESS%; run install-mmt.ps1 again 1>&2'
		'exit /b 2'
		':run'
		'pwsh -NoProfile -File "%HARNESS%\mmt.ps1" %*'
		'exit /b %ERRORLEVEL%'
	) -join "`r`n"
	[System.IO.File]::WriteAllText((Join-Path $binDir 'mmt.cmd'), $shim + "`r`n")

	# --- PATH, user only -------------------------------------------------------
	$pathNote = ''
	if ($NoPath) {
		$pathNote = "PATH untouched (-NoPath). Add $binDir yourself."
	}
	else {
		$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
		$entries = @($userPath -split ';' | Where-Object { $_ -and $_.Trim() } | ForEach-Object { $_.Trim().TrimEnd('\') })
		$already = @($entries | Where-Object { $_.Equals($binDir.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) })
		if ($already.Count -gt 0) {
			$pathNote = "$binDir is already on your PATH."
		}
		else {
			$newPath = (@($entries) + $binDir) -join ';'
			[Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
			$pathNote = "Added $binDir to your user PATH (no admin)."
		}
	}

	$version = if (Test-Path (Join-Path $Install 'VERSION')) { (Get-Content (Join-Path $Install 'VERSION') -Raw).Trim() } else { '' }
	Write-Host ''
	Write-Host ("mmt installed in {0}" -f $Install) -ForegroundColor Green
	Write-Host ("source    {0}" -f $label) -ForegroundColor DarkGray
	if ($version) { Write-Host ("version   {0}" -f $version) -ForegroundColor DarkGray }
	Write-Host ("shim      {0}" -f (Join-Path $binDir 'mmt.cmd')) -ForegroundColor DarkGray
	Write-Host ("path      {0}" -f $pathNote) -ForegroundColor DarkGray
	Write-Host ''
	Write-Host 'Open a NEW terminal (PATH is read at startup), then from any mod repo:' -ForegroundColor Yellow
	Write-Host '  mmt -Repo C:\mods\your-mod fast'
	Write-Host '  mmt -Repo C:\mods\your-mod port -Check -To 1.21.6'
	Write-Host 'The first run downloads Temurin and JBR into .tools (about 280MB, once).' -ForegroundColor DarkGray
	exit 0
}
finally {
	Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
}
