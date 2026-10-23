#requires -Version 7.0
<#
  lab - one command for checking a Minecraft mod.

    .\modaccel              fast lane: compile + pure logic tests              about a second
    .\modaccel world        registry check in a JVM, then a real server world   about 25s
    .\modaccel node -Expect 64   the block probe must report exactly 64        about 3s (no -Expect: must match the last run)
    .\modaccel ui           UI check inside the already-running client        about a second (cold: one client start)
    .\modaccel scenario     scripted steps in the warm world + screenshots    0.2-3s
    .\modaccel suite        every case in scenarios\*.txt, one warm client    seconds
    .\modaccel diff         references your code makes that a jar lacks      1-2s
    .\modaccel port -Log <log>   the compiler's own errors, grouped by file    instant
    .\modaccel port -Fix -Log <log>   apply the known fixes for those errors, rebuild, repeat   seconds per round
    .\modaccel port -Check <repo> -To <mc>   can this repo build at all?     10-90s
    .\modaccel look         full client game test from a fresh client         about 35s
    .\modaccel all          fast + world + ui, stops at the first failure
    .\modaccel watch        fast lane again on every save
    .\modaccel doctor       JDK, versions, fast lane and warm client state
    .\modaccel logs         recent runs with timings
    .\modaccel mp           dedicated server + hidden client + scenario     first run: server + client boot
    .\modaccel mp -Cold     the same, after stopping a server that predates src
    .\modaccel status       what is running, on which port, for how long     instant
    .\modaccel -Version 1.21.11 ui   the same check on another Minecraft node
    .\modaccel stop         stop the fast lane, both clients and the server
#>
param(
	[Parameter(Position = 0)]
	[ValidateSet('check', 'world', 'ui', 'node', 'look', 'all', 'watch', 'doctor', 'logs', 'stop', 'fast', 'bench', 'diff', 'port', 'scenario', 'suite', 'mp', 'status', 'restart')]
	[string]$Verb = 'check',

	[switch]$Open,
	[switch]$Update,
	[ValidateSet('fast', 'lint', 'ui', 'node', 'world', 'look', 'scenario', 'suite')][string]$Tier = 'ui',
	[int]$Count = 5,
	# Positional so `port -Api <old jar> <new jar>` works as typed.
	[Parameter(Position = 1)][string]$Old = '',
	[Parameter(Position = 2)][string]$New = '',
	# port -Api <old jar> <new jar>: the game's own API difference between two versions.
	[switch]$Api,
	[string]$Steps = '',
	[string]$File = '',
	[switch]$Apply,
	[switch]$DryRun,
	[switch]$Two,
	[string]$Log = '',
	# Toolchain gate: a repo to inspect, and the Minecraft version you want to build it at.
	[string]$Check = '',
	[string]$To = '',
	# Automatic fixes from a failing build log: the gradle task to rebuild, how many rounds to allow.
	[switch]$Fix,
	[string]$Task = 'compileJava',
	[int]$Max = 5,
	[string]$Repo = '',
	# Empty means this repo's own Minecraft version; otherwise versions\<name>\warm.ps1 drives it.
	[string]$Version = '',
	# What the node probe must report (`node -Expect 64`). Unset means the last value recorded for
	# this repo and tier; a drift is a failure, never a pass because the number was above zero.
	[int]$Expect = -1,
	# mp: stop this repo's warm server and client first, so they load the sources on disk now.
	[switch]$Cold
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ModLabWin {
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
}
"@ -ErrorAction SilentlyContinue

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$lab = Join-Path $root '.modaccel'
$runs = Join-Path $root '.runs'
$logs = Join-Path $runs 'logs'
$pidFile = Join-Path $lab 'daemon.pid'
$portFile = Join-Path $lab 'daemon.port'
$clientPid = Join-Path $lab 'client.pid'
$clientLog = Join-Path $lab 'client.log'
$clientPort = 27399
# A node can declare its own control port in the run config (NeoForge node uses 27599).
$nodeVmArgs = Join-Path $root 'build\moddev\clientWarmRunVmArgs.txt'
if (Test-Path $nodeVmArgs) {
	$declared = [regex]::Match((Get-Content $nodeVmArgs -Raw), 'prismlab\.lab\.port=(\d+)').Groups[1].Value
	if ($declared) { $clientPort = [int]$declared }
}
$declaredPort = Join-Path $root '.modaccel\client.port'
if (Test-Path $declaredPort) {
	$fromFile = [int]((Get-Content $declaredPort -Raw).Trim())
	if ($fromFile -gt 0) { $clientPort = $fromFile }
}
New-Item -ItemType Directory -Force $lab, $logs | Out-Null

# Temurin for everything that starts a fresh Minecraft: JBR is several seconds slower to
# get a server to its first log line. The warm client overrides this with JBR below.
function Ensure-Jdks {
	# A fresh clone has no JDK: fetch the two this lab needs, once.
	$ProgressPreference = 'SilentlyContinue'
	$tools = Join-Path $root '.tools'
	New-Item -ItemType Directory -Force $tools | Out-Null
	$temurin = Get-ChildItem $tools -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue
	if (-not $temurin) {
		Write-Host 'First run: downloading Temurin 25 JDK (about 180MB, once)...' -ForegroundColor DarkGray
		$api = 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
		$link = (Invoke-RestMethod $api)[0].binary.package.link
		$zip = Join-Path $tools 'temurin.zip'
		Invoke-WebRequest -Uri $link -OutFile $zip -UseBasicParsing
		tar -xf $zip -C $tools
		Remove-Item $zip -Force
	}
	$jbr = Get-ChildItem $tools -Directory -Filter 'jbr-*' -ErrorAction SilentlyContinue
	if (-not $jbr) {
		Write-Host 'First run: downloading JetBrains Runtime 25 (about 100MB, once)...' -ForegroundColor DarkGray
		$zip = Join-Path $tools 'jbr.zip'
		Invoke-WebRequest -Uri 'https://cache-redirector.jetbrains.com/intellij-jbr/jbr-25.0.4.1-windows-x64-b635.70.zip' -OutFile $zip -UseBasicParsing
		tar -xf $zip -C $tools
		Remove-Item $zip -Force
	}
}

Ensure-Jdks

$jdk = Get-ChildItem -Path (Join-Path $root '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
	Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) {
	Write-Host 'Could not set up a JDK in .tools. Install Temurin 25 there manually.' -ForegroundColor Red
	exit 2
}
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$env:PATH"

# A wrapper decides which JDK the build can run on: Gradle below 8.5 is happiest on 17, 8.5-8.14
# on 21, and only 9+ can use 25 (Gradle 8 refuses class file major 69).
$wrapperProps = Join-Path $root 'gradle\wrapper\gradle-wrapper.properties'
if (Test-Path $wrapperProps) {
	$distribution = [regex]::Match((Get-Content $wrapperProps -Raw), 'gradle-(\d+)\.(\d+)')
	if ($distribution.Success) {
		$gradleMajor = [int]$distribution.Groups[1].Value
		$gradleMinor = [int]$distribution.Groups[2].Value
		$want = if ($gradleMajor -ge 9) { 25 } elseif ($gradleMajor -eq 8 -and $gradleMinor -ge 5) { 21 } else { 17 }
		if ($want -ne 25) {
			$found = @(
				@(Get-ChildItem (Join-Path $root '.tools') -Directory -Filter "jdk-$want*" -ErrorAction SilentlyContinue) +
				@(Get-ChildItem (Join-Path $env:USERPROFILE 'tools') -Directory -Filter "jdk-$want*" -ErrorAction SilentlyContinue)
			) | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
				Sort-Object Name -Descending | Select-Object -First 1
			if ($found) {
				$env:JAVA_HOME = $found.FullName
				$env:PATH = "$($found.FullName)\bin;$env:PATH"
				Write-Host ("gradle {0}.{1}: using JDK {2} at {3}" -f $gradleMajor, $gradleMinor, $want, $found.FullName) -ForegroundColor DarkGray
			}
			else {
				Write-Host "gradle $gradleMajor.$gradleMinor needs JDK $want, which is not installed. Put a JDK $want in .tools (or %USERPROFILE%\tools), or bump the wrapper." -ForegroundColor Yellow
			}
		}
	}
}

function Write-Run {
	param([string]$Tier, [bool]$Ok, [double]$Seconds, [string]$Summary, [string[]]$Screens = @(), [string]$Log = '')
	$tag = if ($Ok) { 'PASS' } else { 'FAIL' }
	$color = if ($Ok) { 'Green' } else { 'Red' }
	$label = switch ($Tier) { 'fast' { 'logic' } 'ui' { 'screen' } 'node' { 'block' } 'world' { 'server' } 'look' { 'full' } default { $Tier } }
	Write-Host ("{0,-5} {1,-6} {2,6}s  {3}" -f $tag, $label, $Seconds, $Summary) -ForegroundColor $color
	foreach ($s in $Screens) { Write-Host ("        {0}" -f $s) -ForegroundColor DarkGray }
	if (-not $Ok -and $Log) { Write-Host ("        log: {0}" -f $Log) -ForegroundColor DarkGray }
	[pscustomobject]@{ tier = $Tier; ok = $Ok; seconds = $Seconds; summary = $Summary; at = (Get-Date).ToString('s') } |
		ConvertTo-Json -Compress | Add-Content (Join-Path $runs 'index.jsonl')
}

function Create-ShotBaseline {
	# Which PNGs already exist, so a printed path is only ever a file this run wrote.
	param([string]$Dir)
	$seen = @{}
	if (Test-Path $Dir) {
		Get-ChildItem $Dir -Filter *.png -File -ErrorAction SilentlyContinue |
			ForEach-Object { $seen[$_.FullName] = $_.LastWriteTimeUtc }
	}
	return $seen
}

function Get-NewShots {
	# New files, and files this run overwrote: the previous run's PNGs are not evidence.
	param([string]$Dir, [hashtable]$Before, [int]$Keep = 2)
	if (-not (Test-Path $Dir)) { return @() }
	$new = @(Get-ChildItem $Dir -Filter *.png -File -ErrorAction SilentlyContinue | Where-Object {
			$old = $Before[$_.FullName]
			(-not $old) -or ($_.LastWriteTimeUtc -gt $old)
		})
	if ($Keep -gt 0 -and $new.Count -gt $Keep) {
		$new = @($new | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First $Keep)
	}
	return @($new | Sort-Object LastWriteTimeUtc | ForEach-Object { $_.FullName })
}

function Get-NodeBaseline {
	# The last node value this repo's probe reported, per tier, with the tick count it was measured at.
	$file = Join-Path $lab 'node-expect.json'
	if (-not (Test-Path $file)) { return $null }
	try { $all = @(Get-Content $file -Raw | ConvertFrom-Json) } catch { return $null }
	return ($all | Where-Object { $_ -and $_.repo -eq $root -and $_.tier -eq 'node' } | Select-Object -Last 1)
}

function Set-NodeBaseline {
	param([int]$Value, [int]$Ticks)
	$file = Join-Path $lab 'node-expect.json'
	$all = @()
	if (Test-Path $file) { try { $all = @(Get-Content $file -Raw | ConvertFrom-Json) } catch { $all = @() } }
	$all = @($all | Where-Object { $_ -and -not ($_.repo -eq $root -and $_.tier -eq 'node') })
	$all += [pscustomobject]@{ repo = $root; tier = 'node'; value = $Value; ticks = $Ticks; at = (Get-Date).ToString('s') }
	$all | ConvertTo-Json -Depth 4 | Set-Content $file
}

function Test-NodeValue {
	# `node` used to pass whenever the probe was > 0, so 64 drifting to 1 read as PASS. The value
	# must now equal -Expect, or the last value recorded for this repo; otherwise it is a failure
	# that prints both numbers.
	param([int]$Value, [int]$Ticks)
	$expected = $null
	$from = ''
	if ($Expect -ge 0) { $expected = $Expect; $from = '-Expect' }
	else {
		$last = Get-NodeBaseline
		if ($last) { $expected = [int]$last.value; $from = "last run $($last.at), after $($last.ticks) ticks" }
	}
	if ($null -eq $expected) {
		Set-NodeBaseline -Value $Value -Ticks $Ticks
		return [pscustomobject]@{ ok = $true; match = $false; summary = "baseline recorded: this is the first node value for this repo, later runs must match $Value" }
	}
	if ($Value -eq $expected) {
		Set-NodeBaseline -Value $Value -Ticks $Ticks
		return [pscustomobject]@{ ok = $true; match = $true; summary = "matches $expected ($from)" }
	}
	return [pscustomobject]@{ ok = $false; match = $false; expected = $expected; from = $from }
}

# A dead loopback port is not refused instantly on this PC: the SYN is dropped and Windows retries
# 2s later, so one address costs 2.04s and both families cost 4.1s (measured 40/40 times). A live
# listener completes the handshake in the kernel in 0-4ms, so a 400ms budget separates the two by
# two orders of magnitude and makes every probe a fast signal instead of a timeout.
$script:labProbeMs = 400
# The family that last answered, per port: a running lab answers on one of them, so the other
# address is only tried once nothing is there.
$script:labFamily = @{}

function Connect-Lab {
	# A NeoForge client binds its control socket to ::1, a Fabric one to 127.0.0.1, so both are tried.
	param([int]$Port, [int]$TimeoutMs = 0)
	if ($TimeoutMs -le 0) { $TimeoutMs = $script:labProbeMs }
	$hosts = @('127.0.0.1', '::1')
	$known = $script:labFamily[$Port]
	if ($known) { $hosts = @($known) + @($hosts | Where-Object { $_ -ne $known }) }
	foreach ($hostName in $hosts) {
		$client = [System.Net.Sockets.TcpClient]::new()
		try {
			if ($client.ConnectAsync($hostName, $Port).Wait($TimeoutMs)) {
				$script:labFamily[$Port] = $hostName
				return $client
			}
		}
		catch { }
		# A connect still pending after the budget is a port nobody is listening on: drop it rather
		# than wait out the retry, and never sleep here - the timeout is the signal.
		$client.Close()
	}
	throw "no lab listener on port $Port"
}

function Send-Line {
	param([int]$Port, [string]$Text)
	$client = Connect-Lab -Port $Port
	$stream = $client.GetStream()
	$writer = [System.IO.StreamWriter]::new($stream)
	$reader = [System.IO.StreamReader]::new($stream)
	$writer.WriteLine($Text); $writer.Flush()
	$line = $reader.ReadLine()
	$client.Close()
	return $line
}

# --- bridge: a client that belongs to someone else's mod ----------------------
# The standalone jar in bench\bridge (or .modaccel\bridge.jar, which install.ps1 copies) is the
# control socket for a client whose own mod has none. It is added through -Dfml.modFolders, so
# the host mod's sources, build files and launch scripts are never edited.

function Get-BridgeJar {
	$installed = Join-Path $lab 'bridge.jar'
	if (Test-Path $installed) { return (Resolve-Path $installed).Path }
	$built = Get-ChildItem (Join-Path $root 'bench\bridge\build\libs') -Filter 'modlabbridge-*.jar' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if ($built) { return $built.FullName }
	return ''
}

# --- forge lane: the same socket, as a mod in the host's own mods folder ------------
# ForgeGradle writes no launch recipe, so a Forge client can only be started by replaying a
# command line captured once from a Gradle run. The bridge jar is a mod the game loads from
# <repo>\mods (the ForgeGradle game dir is the repo root), not a -Dfml.modFolders entry.

function Test-ForgeRepo {
	if (Test-Path (Join-Path $root 'build\moddev\clientWarmRunVmArgs.txt')) { return $false }
	# A captured launch line is the strongest signal: only ForgeGradle needs one.
	if (Get-ForgeLaunch) { return $true }
	# Several mods (MineColonies among them) keep their build logic in a shared remote script, so
	# the loader often appears nowhere in build.gradle: ForgeGradle's own outputs settle it.
	if (Test-Path (Join-Path $root 'build\fg_cache')) { return $true }
	if (Test-Path (Join-Path $root 'build\classpath\runClient_minecraftClasspath.txt')) { return $true }
	foreach ($name in @('build.gradle', 'build.gradle.kts', 'gradle.properties')) {
		$file = Join-Path $root $name
		if ((Test-Path $file) -and ((Get-Content $file -Raw) -match 'net\.minecraftforge|ForgeGradle|forgeGradle')) { return $true }
	}
	# mods.toml without a neoforge.mods.toml is a Forge mod.
	if ((Test-Path (Join-Path $root 'src/main/resources/META-INF/mods.toml')) -and
		-not (Test-Path (Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml'))) { return $true }
	return $false
}

function Get-ForgeBridgeJar {
	# .modaccel\bridge-forge.jar is what install.ps1 copies; the harness build output and a jar
	# an earlier run already put in mods\ are the fallbacks, so a hand-installed repo still works.
	$installed = Join-Path $lab 'bridge-forge.jar'
	if (Test-Path $installed) { return (Resolve-Path $installed).Path }
	$dir = $root
	for ($up = 0; $up -lt 3; $up++) {
		$built = Get-ChildItem (Join-Path $dir 'bench\forgebridge\build\libs') -Filter 'modlabbridge-forge-*.jar' -ErrorAction SilentlyContinue |
			Sort-Object LastWriteTime -Descending | Select-Object -First 1
		if ($built) { return $built.FullName }
		$parent = Split-Path -Parent $dir
		if (-not $parent -or $parent -eq $dir) { break }
		$dir = $parent
	}
	$inMods = Get-ChildItem (Join-Path $root 'mods') -Filter 'modlabbridge-forge-*.jar' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if ($inMods) { return $inMods.FullName }
	return ''
}

function Get-ForgeJava {
	# The captured line bakes in no java path, and JAVA_HOME here is the wrapper's JDK (25 for a
	# Gradle 9 wrapper): Forge 1.20.1 needs the 17 the repo builds its classes with.
	$declared = Join-Path $lab 'forge.java'
	if (Test-Path $declared) {
		$path = (Get-Content $declared -Raw).Trim()
		if (Test-Path $path) { return $path }
	}
	foreach ($dir in @((Join-Path $root '.tools'), (Join-Path $env:USERPROFILE 'tools'), (Split-Path -Parent $root))) {
		$found = Get-ChildItem $dir -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
			Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
			Sort-Object Name -Descending | Select-Object -First 1
		if ($found) { return (Join-Path $found.FullName 'bin\java.exe') }
	}
	return (Join-Path $env:JAVA_HOME 'bin\java.exe')
}

function ConvertTo-ForgeArg {
	# A captured line arrives with single, doubled or quadrupled separators (tools\capture-launch.ps1
	# doubles them for a Java argfile, which this lane does not use). Collapse any run to one
	# separator and hand java forward slashes, so nothing downstream depends on escaping.
	param([string]$Text)
	return ($Text -replace '\\+', '\').Replace('\', '/')
}

function Split-ForgeArgs {
	# One token per argument, de-quoted and path-normalised. An empty result means "nothing to run".
	param([string]$Text)
	$tokens = [System.Collections.Generic.List[string]]::new()
	$current = [System.Text.StringBuilder]::new()
	$quoted = $false
	$started = $false
	foreach ($ch in $Text.ToCharArray()) {
		if ($ch -eq '"') { $quoted = -not $quoted; $started = $true; continue }
		if (-not $quoted -and [char]::IsWhiteSpace($ch)) {
			if ($started) { $tokens.Add((ConvertTo-ForgeArg $current.ToString())); $current.Clear() | Out-Null; $started = $false }
			continue
		}
		$current.Append($ch) | Out-Null
		$started = $true
	}
	if ($started) { $tokens.Add((ConvertTo-ForgeArg $current.ToString())) }
	return @($tokens | Where-Object { $_ })
}

function Get-ForgeLaunch {
	# The captured command line, from .modaccel\forge.args (tools\capture-launch.ps1) or a
	# forge-launch.txt capture note in the repo or next to it. Returns the tokens after java.exe.
	foreach ($file in @(
			(Join-Path $lab 'forge.args'),
			(Join-Path $lab 'forge-launch.txt'),
			(Join-Path $root 'forge-launch.txt'),
			(Join-Path (Split-Path -Parent $root) 'forge-launch.txt'))) {
		if (-not (Test-Path $file)) { continue }
		$text = Get-Content $file -Raw
		if (-not $text) { continue }
		$line = $text
		if ($text -match '(?m)^\s*##\s*Full CommandLine') {
			# A capture note: the launch is the one line that starts with the java.exe path.
			$line = @($text -split "`r?`n" | Where-Object { $_ -match '^[A-Za-z]:\\.*java\.exe ' }) | Select-Object -First 1
			if (-not $line) { continue }
			# A note kept outside the repo is only replayed when it names this repo.
			if (-not $file.StartsWith($root, 'OrdinalIgnoreCase') -and $line.IndexOf($root, 'OrdinalIgnoreCase') -lt 0) { continue }
		}
		$tokens = @(Split-ForgeArgs -Text $line)
		if ($tokens.Count -gt 0 -and $tokens[0] -match 'java(w)?\.exe$') { $tokens = @($tokens | Select-Object -Skip 1) }
		if ($tokens.Count -lt 2) { continue }
		return [pscustomobject]@{ File = $file; Tokens = $tokens }
	}
	return $null
}

function Test-OwnControlMod {
	# A dev\modlab client of this repo's own is richer than the jar (scenario steps, block probe),
	# so when one is present it stays the listener and its socket is left alone.
	foreach ($dir in @('src\client\java\dev\modlab', 'src\main\java\dev\modlab')) {
		if (Test-Path (Join-Path $root $dir)) { return $true }
	}
	return $false
}

function Test-PortBusy {
	# The bridge binds ::1 on this PC, so a plain 127.0.0.1 probe does not see it.
	param([int]$Port)
	try { (Connect-Lab -Port $Port).Close(); return $true } catch { return $false }
}

function Get-LabAnswer {
	# One line to a listener on either loopback family, or '' when nothing answers.
	param([int]$Port, [string]$Text = 'root')
	try { return (Send-Line -Port $Port -Text $Text) } catch { return '' }
}

function Invoke-BridgeCommand {
	# Same wire protocol as the dev\modlab socket, but never throws: a missing answer becomes a
	# result with ok = false, so a bridge that died mid-check is reported instead of swallowed.
	param([string]$Text)
	$raw = Get-LabAnswer -Port $clientPort -Text $Text
	if (-not $raw) { return [pscustomobject]@{ ok = $false; error = "no answer on port $clientPort (bridge gone?)" } }
	try { return ($raw | ConvertFrom-Json) } catch { return [pscustomobject]@{ ok = $false; error = "bad answer: $raw" } }
}

function Select-BridgePort {
	# 27699 unless something else holds it; the choice is remembered so a restart reuses it.
	$file = Join-Path $lab 'bridge.port'
	if (Test-Path $file) {
		$recorded = 0
		if ([int]::TryParse((Get-Content $file -Raw).Trim(), [ref]$recorded) -and $recorded -gt 0) {
			if (-not (Test-PortBusy -Port $recorded)) { return $recorded }
			$answer = Get-LabAnswer -Port $recorded -Text 'ping'
			if ($answer -and ($answer | ConvertFrom-Json).mod -eq 'modlabbridge') { return $recorded }
		}
	}
	for ($candidate = 27699; $candidate -lt 27720; $candidate++) {
		if (-not (Test-PortBusy -Port $candidate)) {
			Set-Content $file $candidate -NoNewline
			return $candidate
		}
	}
	return 0
}

function Get-DeclaredScreen {
	# The screen the repo's own lab opens (a modlab.screen system property in its run config); the
	# bridge opens the same class, so `ui` still checks the host mod's screen and not just a tint.
	$vmArgs = Join-Path $root 'build\moddev\clientWarmRunVmArgs.txt'
	if (-not (Test-Path $vmArgs)) { return '' }
	$hit = [regex]::Match((Get-Content $vmArgs -Raw), 'modlab\.screen=([^\s"]+)')
	if ($hit.Success) { return $hit.Groups[1].Value }
	return ''
}

# What the warm client is launched with. $hostPort keeps the port the run config declares, so a
# client started without the bridge can be told apart from one started with it.
$hostPort = $clientPort
$forgeRepo = Test-ForgeRepo
$bridgeJar = Get-BridgeJar
$forgeJar = ''
if ($forgeRepo) {
	# A Forge repo takes the Forge twin only: the NeoForge jar is compiled against 21.1 APIs.
	$bridgeJar = ''
	$forgeJar = Get-ForgeBridgeJar
}
$bridgePort = 0
$bridgeShots = Join-Path $root 'build\run\warm\shots'
$bridgeListener = $false
if ($bridgeJar -or $forgeJar) {
	$bridgePort = Select-BridgePort
	if ($bridgePort -le 0) {
		Write-Host 'Every bridge port 27699-27719 is held; trying 27699 anyway.' -ForegroundColor Yellow
		$bridgePort = 27699
	}
	# A repo with no control mod of its own has nothing to lose by blanking the host socket: the
	# bridge then answers everything. Our own labs keep their richer dev\modlab client.
	$bridgeListener = -not (Test-OwnControlMod)
	if ($bridgeListener) { $clientPort = $bridgePort }
}

# --- fast lane ---------------------------------------------------------------

function Get-DaemonPort {
	if (-not (Test-Path $portFile)) { return 0 }
	[int](Get-Content $portFile -Raw).Trim()
}

function Test-Daemon {
	$port = Get-DaemonPort
	if ($port -le 0) { return $false }
	# Same short-budget probe as the client: an untimed connect to a daemon that has not opened its
	# port yet cost 2s per poll of the start loop.
	try { (Connect-Lab -Port $port).Close(); return $true } catch { return $false }
}

function Stop-Daemon {
	$port = Get-DaemonPort
	if ($port -gt 0) {
		try { Send-Line -Port $port -Text '{"cmd":"stop"}' | Out-Null; Start-Sleep -Milliseconds 300 } catch { }
	}
	if (Test-Path $pidFile) {
		$old = (Get-Content $pidFile -Raw).Trim()
		if ($old) { Stop-Process -Id ([int]$old) -Force -ErrorAction SilentlyContinue }
		Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
	}
	Remove-Item $portFile -Force -ErrorAction SilentlyContinue
}

function Get-LaneJUnit {
	# One small, known set of jars for the lane itself. A Fabric repo usually ships them through
	# fabric-loader-junit; a Forge repo usually ships none, and the daemon would not compile.
	$lib = Join-Path $lab 'lib'
	New-Item -ItemType Directory -Force $lib | Out-Null
	$wanted = @(
		'org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar',
		'org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar',
		'org/junit/platform/junit-platform-commons/1.11.4/junit-platform-commons-1.11.4.jar',
		'org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar',
		'org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar',
		'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar',
		'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar'
	)
	foreach ($path in $wanted) {
		$dest = Join-Path $lib (Split-Path $path -Leaf)
		if (Test-Path $dest) { continue }
		try {
			Invoke-WebRequest "https://repo1.maven.org/maven2/$path" -OutFile $dest -UseBasicParsing -TimeoutSec 60
		}
		catch {
			Write-Host "Could not fetch $(Split-Path $path -Leaf) for the fast lane (offline?)." -ForegroundColor Yellow
			return @()
		}
	}
	return @(Get-ChildItem $lib -Filter '*.jar' | ForEach-Object { $_.FullName })
}

function Start-Daemon {
	$cpFile = Join-Path $lab 'classpath.txt'
	if (-not (Test-Path $cpFile)) {
		Write-Host 'First run: asking Gradle for the classpath (once)...' -ForegroundColor DarkGray
		& (Join-Path $root 'gradlew.bat') labClasspath --console=plain *> (Join-Path $logs 'setup-classpath.log')
		if (-not (Test-Path $cpFile)) {
			Write-Host 'Could not build the classpath. See .runs\logs\setup-classpath.log' -ForegroundColor Red
			return $false
		}
	}
	else {
		# A cleaned Gradle cache leaves the recorded paths pointing at jars that are gone, which
		# reads as "cannot find symbol" much later. Re-ask Gradle once when that happens.
		$recorded = (Get-Content $cpFile -Raw).Trim() -split ';'
		$gone = @($recorded | Where-Object { $_ -and -not (Test-Path $_) }).Count
		if ($gone -gt 2) {
			Write-Host "Classpath has $gone missing entr(ies) (dependency cache changed); asking Gradle again..." -ForegroundColor DarkGray
			& (Join-Path $root 'gradlew.bat') labClasspath --console=plain *> (Join-Path $logs 'setup-classpath.log')
		}
	}
	$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
	$listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
	Set-Content $portFile $port -NoNewline

	$raw = (Get-Content $cpFile -Raw).Trim()
	$entries = @($raw -split ';' | Where-Object {
		$_ -and ($_ -notmatch '\\build\\classes\\') -and ($_ -notmatch '\\build\\resources\\') -and ($_ -notmatch 'fabric-loader-junit')
	})
	# The daemon itself needs the JUnit Platform, and a Forge repo's test classpath often has none.
	# Keep our own copy so the lane does not depend on the repo's test dependencies.
	$junit = Get-LaneJUnit
	if ($junit.Count -gt 0) { $entries = @($junit) + $entries }
	$laneCp = Join-Path $lab 'lane-classpath.txt'
	Set-Content $laneCp ($entries -join ';')
	$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
	$args = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-cp', ($entries -join ';'),
		(Join-Path $root 'tools\LabDaemon.java'), $root, $laneCp, $port)
	$excludes = Join-Path $lab 'excludes.txt'
	if (Test-Path $excludes) { $args += $excludes }
	$proc = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $root `
		-WindowStyle Hidden -PassThru `
		-RedirectStandardOutput (Join-Path $lab 'daemon.out') -RedirectStandardError (Join-Path $lab 'daemon.err')
	Set-Content $pidFile $proc.Id -NoNewline

	$deadline = (Get-Date).AddSeconds(40)
	while ((Get-Date) -lt $deadline) {
		if (Test-Daemon) { return $true }
		if ($proc.HasExited) { break }
		Start-Sleep -Milliseconds 150
	}
	Write-Host 'Fast lane did not start. See .modaccel\daemon.err' -ForegroundColor Red
	return $false
}

function Invoke-Fast {
	if (-not (Test-Daemon)) {
		Stop-Daemon
		if (-not (Start-Daemon)) { return $null }
	}
	try { $line = Send-Line -Port (Get-DaemonPort) -Text '{"cmd":"run"}' } catch { return $null }
	if (-not $line) { return $null }
	$result = $line | ConvertFrom-Json
	$summary = if ($result.ok) { "$($result.passed) passed" } else { "$($result.failed) failed" }
	if ($result.failures.Count -gt 0) { $summary = "$summary | " + ($result.failures -join ' | ') }
	Write-Run -Tier 'fast' -Ok ([bool]$result.ok) -Seconds ([math]::Round($result.ms / 1000, 1)) -Summary $summary
	return $result
}

# --- warm client -------------------------------------------------------------

function Test-WarmClient {
	try {
		$c = Connect-Lab -Port $clientPort
		$stream = $c.GetStream()
		$w = [System.IO.StreamWriter]::new($stream)
		$r = [System.IO.StreamReader]::new($stream)
		$w.WriteLine('root'); $w.Flush()
		$line = $r.ReadLine()
		$c.Close()
		# A client from another repo must never answer our checks.
		return ($line -and ($line | ConvertFrom-Json).root -eq $root)
	}
	catch { return $false }
}

function Build-Agent {
	$classes = Join-Path $lab 'agent-classes'
	$jar = Join-Path $lab 'agent.jar'
	$manifest = Join-Path $lab 'agent-manifest.txt'
	$src = Join-Path $root 'tools\LabAgent.java'
	if ((Test-Path $jar) -and ((Get-Item $jar).LastWriteTime -gt (Get-Item $src).LastWriteTime)) { return }
	New-Item -ItemType Directory -Force $classes | Out-Null
	& "$env:JAVA_HOME\bin\javac.exe" -d $classes (Join-Path $root 'tools\LabAgent.java') 2>&1 | Out-Null
	Set-Content $manifest "Premain-Class: LabAgent`nAgent-Class: LabAgent`nCan-Redefine-Classes: true`nCan-Retransform-Classes: true"
	& "$env:JAVA_HOME\bin\jar.exe" --create --file $jar --manifest $manifest -C $classes . 2>&1 | Out-Null
}

function Test-PortOpen {
	# The bridge binds ::1, a host control mod usually binds 127.0.0.1: try both.
	try { $c = Connect-Lab -Port $clientPort; $c.Close(); return $true } catch { return $false }
}

function Start-WarmClient {
	if ((Test-PortOpen) -and -not (Test-WarmClient)) {
		Write-Host "Port $clientPort is held by another repo's lab client. Run .\modaccel stop in that repo, or close its window." -ForegroundColor Red
		return $false
	}
	if ($bridgeListener -and $clientPort -ne $hostPort -and -not (Test-PortOpen)) {
		# A client started before the bridge was installed holds this repo's game folder; a second
		# one on the same folder conflicts, so say what to do instead of starting it.
		$old = Get-LabAnswer -Port $hostPort
		if ($old -and ($old | ConvertFrom-Json).root -eq $root) {
			Write-Host "A client for this repo is already running on port $hostPort without the bridge. Run .\modaccel stop, then again: it restarts once with the bridge." -ForegroundColor Yellow
			return $false
		}
	}
	# The agent only serves a dev\modlab client; the bridge sockets need no instrumentation, and a
	# Forge repo would spend 17s compiling a class its client never loads.
	if (-not $forgeRepo -and -not $forgeJar) { Build-Agent }
	# The warm client loads a small saved world: an item's components only bind once a
	# datapack loads, and the UI shows the real item icon.
	$world = Join-Path $root 'build\run\warm\saves\lab'
	if (-not $forgeRepo -and -not (Test-Path (Join-Path $world 'level.dat'))) {
		$source = Get-ChildItem (Join-Path $root 'build\run\clientGameTest\saves') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
		if (-not $source) {
			Write-Host 'No lab world yet: running the full client test once to create one...' -ForegroundColor DarkGray
			& (Join-Path $root 'gradlew.bat') "$(Get-GradleTaskPrefix)runClientGameTest" --console=plain *> (Join-Path $logs 'warm-world-setup.log')
			$source = Get-ChildItem (Join-Path $root 'build\run\clientGameTest\saves') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
		}
		if ($source) {
			New-Item -ItemType Directory -Force $world | Out-Null
			Copy-Item (Join-Path $source.FullName '*') $world -Recurse -Force
		}
		# Older labs kept the world in run\saves\lab; carry it over once.
		$legacy = Join-Path $root 'run\saves\lab'
		if ((Test-Path (Join-Path $legacy 'level.dat')) -and -not (Test-Path (Join-Path $world 'level.dat'))) {
			New-Item -ItemType Directory -Force $world | Out-Null
			Copy-Item (Join-Path $legacy '*') $world -Recurse -Force
		}
	}
	if (Test-Path $clientLog) { Remove-Item $clientLog -Force -ErrorAction SilentlyContinue }
	Write-Host 'Starting the warm client once (no world, title screen only)...' -ForegroundColor DarkGray
	# JBR with enhanced redefinition lets the agent add fields and methods, not just bodies.
	$jbr = Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'jbr-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
	$clientHome = if ($jbr) { $jbr.FullName } else { $env:JAVA_HOME }
	# Detach through cmd start: a plain Start-Process client dies with the calling shell job.
	$launcher = Join-Path $lab 'start-client.cmd'
	# NeoForge runs from ModDevGradle's generated recipe: java + @vmargs + -cp + @programargs.
	# Launching that directly skips Gradle entirely, so a restart is a JVM start, not a build.
	$neoVm = Join-Path $root 'build\moddev\clientWarmRunVmArgs.txt'
	$neoProg = Join-Path $root 'build\moddev\clientWarmRunProgramArgs.txt'
	$neoCpFile = Join-Path $lab 'neoforge-cp.txt'
	if (-not (Test-Path $neoCpFile)) {
		# The installer's labClasspath writes the same list (verified: same 111 entries), so a repo
		# installed through install.ps1 needs no hand capture at all.
		$installedCp = Join-Path $lab 'classpath.txt'
		if (Test-Path $installedCp) { $neoCpFile = $installedCp }
	}
	$forge = if ($forgeRepo) { Get-ForgeLaunch } else { $null }
	if ($forgeRepo) {
		# ForgeGradle (Forge) writes no launch recipe: the whole command is one inline line, captured
		# once into .modaccel\forge.args (tools\capture-launch.ps1). Replaying it skips Gradle, and the
		# bridge joins as a jar in the game folder, which for ForgeGradle is the repo root.
		if (-not $forge) {
			Write-Host 'Forge: this repo has no captured launch, so the client cannot start without Gradle.' -ForegroundColor Yellow
			Write-Host '       once: .\gradlew runClient, then in another window:' -ForegroundColor Yellow
			Write-Host '       .\tools\capture-launch.ps1 -Match forgeclientuserdev -Out .modaccel\forge.args' -ForegroundColor Yellow
			Write-Host '       close that client, then .\modaccel ui replays the line (no Gradle).' -ForegroundColor DarkGray
			return $false
		}
		if (-not $forgeJar) {
			Write-Host 'Forge: no bridge jar. Re-run install.ps1 in the harness, or build it once: bench\forgebridge\build.ps1' -ForegroundColor Red
			return $false
		}
		$hostMods = Join-Path $root 'mods'
		New-Item -ItemType Directory -Force $hostMods | Out-Null
		$bridgeInMods = Join-Path $hostMods (Split-Path -Leaf $forgeJar)
		if (-not (Test-Path $bridgeInMods)) {
			Copy-Item $forgeJar $bridgeInMods -Force
			Write-Host "Forge: bridge jar copied to $bridgeInMods" -ForegroundColor DarkGray
		}
		# Properties go first: java parses options in order, and the captured line ends with the main
		# class, so anything appended after it would become a program argument instead.
		$forgeProps = @(
			"-Dmodlabbridge.port=$bridgePort",
			"-Dmodlabbridge.shots=$(ConvertTo-ForgeArg $bridgeShots)",
			"-Dmodlab.root=$(ConvertTo-ForgeArg $root)"
		)
		$argLine = (@($forgeProps) + @($forge.Tokens | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })) -join ' '
		$forgeJava = Get-ForgeJava
		# 21 KB of arguments do not fit a .cmd line, so the launch is a one-line PowerShell file.
		# Start-Process takes the line as one string itself, and cmd start keeps java off this shell.
		$runScript = Join-Path $lab 'start-forge.ps1'
		Set-Content $runScript ("Start-Process -FilePath '{0}' -ArgumentList '{1}' -WorkingDirectory '{2}' -RedirectStandardOutput '{3}' -RedirectStandardError '{4}' -WindowStyle Hidden" -f
			$forgeJava, ($argLine -replace "'", "''"), $root, $clientLog, (Join-Path $lab 'client.err'))
		Set-Content $launcher ("@echo off`r`nstart `"`" /b powershell -NoProfile -ExecutionPolicy Bypass -File `"$runScript`"`r`nexit")
		Write-Host ("Forge: replaying the captured launch from {0} (no Gradle); bridge on port {1}" -f $forge.File, $bridgePort) -ForegroundColor DarkGray
	}
	elseif ((Test-Path $neoVm) -and (Test-Path $neoProg) -and (Test-Path $neoCpFile)) {
		$neoCp = (Get-Content $neoCpFile -Raw).Trim() -split ';' | Where-Object { $_ -and (Test-Path $_) }
		$modClasses = Join-Path $root 'build\classes\java\main'
		$modResources = Join-Path $root 'build\resources\main'
		$neoCp = @($modClasses, $modResources) + $neoCp
		$modFolders = "prismlab%%$modClasses;prismlab%%$modResources"
		# In a .bat every literal percent must be doubled, so %% becomes %%%%.
		$modFoldersBat = "prismlab%%%%$modClasses;prismlab%%%%$modResources"
		# The bridge joins the same mod-folder list, one more entry: the host mod's files are
		# untouched, and a repo with no control mod of its own gets one from the jar.
		$bridgeArgs = ''
		if ($bridgeJar) {
			$modFolders += ";modlabbridge%%$bridgeJar"
			$modFoldersBat += ";modlabbridge%%%%$bridgeJar"
			$bridgeArgs = " -Dmodlabbridge.port=$bridgePort -Dmodlabbridge.shots=`"$bridgeShots`" -Dmodlab.root=`"$root`""
			# Blank the host's own socket only when this repo has no dev\modlab client: then every
			# answer, including the PNG, comes from the bridge. After the vmargs file, so it wins.
			if ($bridgeListener) { $bridgeArgs += ' -Dprismlab.lab.port=' }
		}
		$neoHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { $clientHome }
		# The game JVM is the toolchain ModDevGradle picked, not necessarily this shell's JDK:
		# NeoForge 21.1.x runs on Java 21 even when the Gradle wrapper itself uses 25.
		$neoVersion = ''
		$neoArtifact = Get-ChildItem (Join-Path $root 'build\moddev\artifacts') -Filter 'neoforge-*.jar' -ErrorAction SilentlyContinue |
			Where-Object { $_.Name -match '^neoforge-\d+\.' } | Select-Object -First 1
		if ($neoArtifact) { $neoVersion = [regex]::Match($neoArtifact.Name, '^neoforge-(\d+)\.').Groups[1].Value }
		if ($neoVersion) {
			$toolchain = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue |
				Where-Object { $_.Name -match "(^|[-_.])$neoVersion([-_.]|$)" -and (Test-Path (Join-Path $_.FullName 'bin\java.exe')) } |
				Sort-Object Name -Descending | Select-Object -First 1
			if ($toolchain) { $neoHome = $toolchain.FullName }
		}
		$neoJava = Join-Path $neoHome 'bin\java.exe'
		# cmd.exe cannot take a 16k command line, so the classpath goes in a Java @argfile.
		$neoArgs = Join-Path $lab 'neoforge-cp.args'
		# Java @argfiles use backslash as an escape character, so Windows paths go in doubled.
		$neoCpEscaped = (($neoCp -join ';') -replace '\\', '\\\\')
		Set-Content $neoArgs ('-cp "' + $neoCpEscaped + '"') -NoNewline
		Set-Content $launcher ("@echo off`r`nset JAVA_HOME=$neoHome`r`ncd /d `"$root`"`r`n`"$neoJava`" @`"$neoVm`" @`"$neoArgs`" -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant -Dfml.modFolders=`"$modFoldersBat`"$bridgeArgs net.neoforged.devlaunch.Main @`"$neoProg`" > `"$clientLog`" 2> `"$(Join-Path $lab 'client.err')`"`r`nexit")
		# The bridge says so in the client log, which is the only proof that the jar loaded.
		if ($bridgeJar) { Write-Host ("Bridge: modlabbridge%%{0} on port {1}" -f $bridgeJar, $bridgePort) -ForegroundColor DarkGray }
		Write-Host 'NeoForge: launching from the ModDevGradle recipe (no Gradle).' -ForegroundColor DarkGray
	}
	elseif ($bridgeListener) {
		# The bridge can only be added to the ModDevGradle recipe, and this repo has none yet.
		# One Gradle pass writes it (about 90s, once); then the launch is a plain JVM start.
		Write-Host 'No client recipe yet: one Gradle pass to write it (about 90s, once)...' -ForegroundColor DarkGray
		$recipeLog = Join-Path $logs 'client-recipe.log'
		& (Join-Path $root 'gradlew.bat') compileJava processResources prepareClientWarmRun --console=plain *> $recipeLog
		$cpMissing = -not ((Test-Path (Join-Path $lab 'neoforge-cp.txt')) -or (Test-Path (Join-Path $lab 'classpath.txt')))
		if ($cpMissing) { & (Join-Path $root 'gradlew.bat') labClasspath --console=plain *>> $recipeLog }
		if ((Test-Path $neoVm) -and (Test-Path $neoProg)) { return (Start-WarmClient) }
		Write-Host 'Gradle did not write the client recipe, so the bridge cannot join this launch. See .runs\logs\client-recipe.log' -ForegroundColor Red
		return $false
	}
	else {
		Set-Content $launcher "@echo off`r`nset JAVA_HOME=$clientHome`r`ncd /d `"$root`"`r`ngradlew.bat $(Get-GradleTaskPrefix)runClientWarm --console=plain > `"$clientLog`" 2> `"$(Join-Path $lab 'client.err')`"`r`nexit"
	}
	# A pid from an earlier run may name a process that is gone, or one that is someone else's.
	Remove-Item $clientPid -Force -ErrorAction SilentlyContinue
	Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'start', '""', '/b', $launcher) -WorkingDirectory $root -WindowStyle Hidden
	# modaccel expands $root, so this repo's recipe paths only appear in this repo's game JVM.
	$rootPattern = [regex]::Escape($root)
	# gradlew.bat hands off to java and exits, so the port is the only reliable signal.
	$deadline = (Get-Date).AddSeconds(300)
	while ((Get-Date) -lt $deadline) {
		if (Test-WarmClient) {
			# Hide the game window so starting the lab never steals focus.
			$game = $null
			for ($i = 0; $i -lt 40; $i++) {
				$game = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
					Where-Object { $_.CommandLine -match 'devlaunchinjector|devlaunch\.Main|BootstrapLauncher' -and $_.CommandLine -match $rootPattern } |
					Select-Object -First 1
				if ($game) { break }
				Start-Sleep -Milliseconds 250
			}
			if ($game) {
				Set-Content $clientPid $game.ProcessId -NoNewline
				for ($i = 0; $i -lt 40; $i++) {
					$win = Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue
					if ($win -and $win.MainWindowHandle -ne 0) { [ModLabWin]::ShowWindow($win.MainWindowHandle, 0) | Out-Null; break }
					Start-Sleep -Milliseconds 250
				}
			}
			return $true
		}
		Start-Sleep -Milliseconds 250
	}
	Write-Host 'Warm client did not come up. See .modaccel\client.log' -ForegroundColor Red
	return $false
}

function Invoke-Restart {
	# Schema changes (a new field or method) cannot be redefined into a running JVM, so the one
	# manual sequence - stop, compile, start - becomes a command. About 20s, not 22s of guessing.
	$started = Get-Date
	Stop-WarmClient
	$prefix = Get-GradleTaskPrefix
	$task = "${prefix}compileJava"
	$log = Join-Path $logs "$($started.ToString('yyyyMMdd-HHmmss'))-restart.log"
	Push-Location $root
	try { & (Join-Path $root 'gradlew.bat') $task --console=plain *> $log } finally { Pop-Location }
	$code = $LASTEXITCODE
	if ($code -ne 0) {
		Write-Host "restart: compile failed, see $log" -ForegroundColor Red
		return $false
	}
	if (-not (Start-WarmClient)) { return $false }
	Write-Run -Tier 'restart' -Ok $true -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary 'stopped, compiled, started' -Log $log
	return $true
}

function Stop-WarmClient {
	# start.cmd leaves cmd /K shells behind; they hold the folder open. Only this repo's, or
	# stopping one lab would close another repo's client.
	$launcher = Join-Path $lab 'start-client.cmd'
	$launcherPattern = [regex]::Escape($launcher)
	Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and ($_.CommandLine -match $launcherPattern) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	if (Test-WarmClient) {
		try { Send-Line -Port $clientPort -Text 'quit' | Out-Null; Start-Sleep -Milliseconds 500 } catch { }
	}
	if (Test-Path $clientPid) {
		$old = (Get-Content $clientPid -Raw).Trim()
		if ($old) { Stop-Process -Id ([int]$old) -Force -ErrorAction SilentlyContinue }
		Remove-Item $clientPid -Force -ErrorAction SilentlyContinue
	}
}

function Invoke-Auto {
	# One command: look at what changed and run the cheapest thing that covers it.
	$changed = @()
	if (Test-Path (Join-Path $root '.git')) {
		$changed = git -C $root status --porcelain | ForEach-Object { $_.Substring(3).Trim() } | Where-Object { $_ }
	}
	$changed = @($changed | Where-Object { $_ -like 'src/*' })
	if (-not $changed) {
		Write-Host 'Nothing changed since the last commit. Running the quick checks.' -ForegroundColor DarkGray
		return (Invoke-Fast)
	}
	$touched = ($changed -join ' ')
	$server = $changed | Where-Object { $_ -match 'src/main' } | ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content $_ -Raw -ErrorAction SilentlyContinue } | Out-String
	$needsBoot = ($changed | Where-Object { $_ -match 'fabric\.mod\.json|mixins\.json' }).Count -gt 0 -or $server -match 'Registry\.register'
	Write-Host ("Changed: " + (($changed | Select-Object -First 4) -join ', ')) -ForegroundColor DarkGray
	if ($needsBoot) { return (Invoke-World) }
	if ($touched -match 'src/client|src/main/resources/assets') { Invoke-Fast | Out-Null; return (Invoke-Ui) }
	if ($touched -match 'src/main') { Invoke-Fast | Out-Null; return (Invoke-Ui -Command 'node') }
	return (Invoke-Fast)
}

function Invoke-Lint {
	$started = Get-Date
	$out = pwsh -NoProfile -File (Join-Path $root 'tools\lint.ps1') 2>&1
	$ok = $LASTEXITCODE -eq 0
	$summary = if ($ok) { ($out | Select-Object -Last 1) } else { ($out | Select-Object -First 3) -join ' | ' }
	Write-Run -Tier 'lint' -Ok $ok -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary $summary
	return $ok
}

function Invoke-BridgeCheck {
	# The `ui` tier without a control mod in the host: the bridge opens the screen the repo names
	# in its own run config, then captures the frame. It has no hot reload and no block probe.
	param([string]$Tier, [datetime]$Started)
	if ($Tier -eq 'node') {
		Write-Run -Tier 'node' -Ok $true -Seconds 0 -Summary 'skipped: the bridge cannot place blocks; that tier needs a dev\modlab client in the repo'
		Write-Host '        (the bridge drives screens and screenshots only)' -ForegroundColor DarkGray
		return $true
	}
	$lines = @()
	$ok = $true
	$shots = @()
	if ($Update) { Write-Host 'The bridge does not hot-reload: this checks the code the client started with.' -ForegroundColor DarkGray }
	$screen = Get-DeclaredScreen
	if ($screen) {
		$opened = Invoke-BridgeCommand -Text "ui $screen"
		if ($opened.ok) { $lines += "opened $($opened.screen)" }
		else { $ok = $false; $lines += "ui ${screen}: $($opened.error)" }
	}
	$shot = Invoke-BridgeCommand -Text 'screenshot ui'
	if ($shot.ok) {
		$lines += "$($shot.file) $($shot.width)x$($shot.height) ($($shot.attempts) tries)"
		$shots += $shot.file
	}
	else { $ok = $false; $lines += "screenshot: $($shot.error)" }
	$seconds = [math]::Round(((Get-Date) - $Started).TotalSeconds, 1)
	Write-Run -Tier $Tier -Ok $ok -Seconds $seconds -Summary ($lines -join ' | ') -Screens $shots
	return $ok
}

function Invoke-BridgeScenario {
	# The lab's scenario lines, as far as the bridge can carry them: open a screen, wait, capture.
	# Anything else (place, assert, click, at) needs the host mod's own control mod, and is named
	# as unsupported instead of being silently dropped.
	param([string]$Steps, [datetime]$Started)
	$plan = @()
	$unsupported = @()
	foreach ($raw in ($Steps -split '\|')) {
		$token = $raw.Trim()
		if (-not $token) { continue }
		if ($token -eq 'screenshot') { $plan += @{ kind = 'shot'; name = 'scenario' }; continue }
		if ($token -like 'capture=*') { $plan += @{ kind = 'shot'; name = $token.Substring(8).Trim() }; continue }
		if ($token -like 'screen=*') { $plan += @{ kind = 'ui'; value = $token.Substring(7).Trim() }; continue }
		if ($token -like 'wait=*') {
			$ticks = 0
			if ([int]::TryParse($token.Substring(5).Trim(), [ref]$ticks)) { $plan += @{ kind = 'wait'; ticks = $ticks }; continue }
		}
		if ($token -eq 'settle') { $plan += @{ kind = 'wait'; ticks = 20 }; continue }
		$unsupported += ($token -split '=')[0]
	}
	if ($plan.Count -eq 0 -and $unsupported.Count -eq 0) { $plan += @{ kind = 'shot'; name = 'scenario' } }
	if ($unsupported.Count -gt 0) {
		$summary = "the bridge cannot run $(($unsupported | Sort-Object -Unique) -join ', '): that needs a dev\modlab client in the repo"
		$seconds = [math]::Round(((Get-Date) - $Started).TotalSeconds, 1)
		Write-Run -Tier 'scenario' -Ok $false -Seconds $seconds -Summary $summary
		Write-Host "        $summary" -ForegroundColor Red
		return $false
	}
	$lines = @()
	$ok = $true
	$shots = @()
	foreach ($step in $plan) {
		switch ($step.kind) {
			'wait' { Start-Sleep -Milliseconds ([int]($step.ticks * 50)) }
			'ui' {
				$answer = Invoke-BridgeCommand -Text "ui $($step.value)"
				if ($answer.ok) { $lines += "opened $($answer.screen)" }
				else { $ok = $false; $lines += "ui: $($answer.error)" }
			}
			'shot' {
				$answer = Invoke-BridgeCommand -Text "screenshot $($step.name)"
				if ($answer.ok) { $lines += "$($answer.file) $($answer.width)x$($answer.height)"; $shots += $answer.file }
				else { $ok = $false; $lines += "screenshot: $($answer.error)" }
			}
		}
	}
	$seconds = [math]::Round(((Get-Date) - $Started).TotalSeconds, 1)
	Write-Run -Tier 'scenario' -Ok $ok -Seconds $seconds -Summary ($lines -join ' | ') -Screens $shots
	return $ok
}

function Invoke-Ui {
	param([string]$Command = 'ui')
	$cold = -not (Test-WarmClient)
	if ($cold) {
		if (-not (Start-WarmClient)) { return $false }
	}
	$started = Get-Date
	# The shot folder keeps every earlier frame: snapshot it, so only a PNG this run wrote can be
	# printed as evidence.
	$shotDir = Join-Path $root 'build\run\warm\shots'
	$shotBefore = Create-ShotBaseline -Dir $shotDir
	if ($Update) { $Command = 'ui-update' }
	if ($bridgeListener) { return (Invoke-BridgeCheck -Tier $Command -Started $started) }
	$line = $null
	try { $line = Send-Line -Port $clientPort -Text $Command } catch { }
	$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
	$result = $null
	if ($line) { try { $result = $line | ConvertFrom-Json } catch { } }
	if (-not $result) {
		# A render exception takes the whole client down, so there is no answer and no frame.
		# Name that instead of printing a transport error plus an old path.
		$why = if ($line) { "the client answered with no result: $line" } else { 'the client died before it answered (see .modaccel\client.log)' }
		Write-Run -Tier $Command -Ok $false -Seconds $seconds -Summary "$why | no screenshot was written by this run"
		return $false
	}
	$ok = [bool]$result.ok
	$summary = if ($result.ok) { $result.lines } elseif ($result.lines) { $result.lines } else { $result.error }
	$shots = @()
	if ($Command -in @('ui', 'ui-update')) {
		if ($ok) {
			$shots = Get-NewShots -Dir $shotDir -Before $shotBefore
			if ($shots.Count -eq 0) { $ok = $false; $summary = "$summary | the run reported ok but wrote no screenshot, so there is no frame to look at" }
		}
		else { $summary = "$summary | no screenshot was written by this run" }
	}
	if ($Command -eq 'node' -and $ok) {
		$probe = [regex]::Match([string]$result.lines, '=\s*(-?\d+)\s+after\s+(\d+)\s+ticks')
		if (-not $probe.Success) {
			$ok = $false
			$summary = "$summary | the node probe reported no value to check"
		}
		else {
			$verdict = Test-NodeValue -Value ([int]$probe.Groups[1].Value) -Ticks ([int]$probe.Groups[2].Value)
			if (-not $verdict.ok) {
				# The probe's own number moves with the lab world's light (1 and 2 both seen with
				# unchanged code), so a single reading is not evidence of a code change: take a
				# second one. Real drift repeats; a one-off reading goes back to the expectation.
				$again = Send-Line -Port $clientPort -Text $Command
				$second = [regex]::Match([string](($again | ConvertFrom-Json).lines), '=\s*(-?\d+)\s+after\s+(\d+)\s+ticks')
				$value = [int]$probe.Groups[1].Value
				$ticks = [int]$probe.Groups[2].Value
				if ($again -and $second.Success -and [int]$second.Groups[1].Value -eq $verdict.expected) {
					$ok = $true
					Set-NodeBaseline -Value ([int]$second.Groups[1].Value) -Ticks ([int]$second.Groups[2].Value)
					$summary = "$summary | one probe read $value, the repeat read $($second.Groups[1].Value) = the expected value ($($verdict.expected)), so this is the probe, not the code"
				}
				else {
					$ok = $false
					$repeat = if ($second.Success) { "then $($second.Groups[1].Value)" } else { 'then no value' }
					$summary = "$what drift: expected $($verdict.expected) ($($verdict.from)), got $value after $ticks ticks, $repeat after $ticks ticks"
				}
			}
			else { $summary = "$summary | $($verdict.summary)" }
		}
	}
	Write-Run -Tier $Command -Ok $ok -Seconds $seconds -Summary $summary -Screens $shots
	if ($cold) { Write-Host '        (first check includes the one-time client start)' -ForegroundColor DarkGray }
	return $ok
}

# --- gradle tiers ------------------------------------------------------------

function Get-GradleTaskPrefix {
	# Stonecutter keeps one project per Minecraft node; a bare task name is ambiguous at the root.
	if (-not (Test-Path (Join-Path $root 'stonecutter.gradle.kts'))) { return '' }
	$active = [regex]::Match((Get-Content (Join-Path $root 'stonecutter.gradle.kts') -Raw), 'active\s+"([^"]+)"').Groups[1].Value
	if ($active) { return (':{0}:' -f $active) }
	return ''
}

function Invoke-GradleTier {
	param([string]$Tier, [string[]]$TaskArgs)
	$started = Get-Date
	$log = Join-Path $logs "$($started.ToString('yyyyMMdd-HHmmss'))-$Tier.log"
	& (Join-Path $root 'gradlew.bat') @TaskArgs --console=plain *> $log
	$code = $LASTEXITCODE
	$lines = Get-Content $log -ErrorAction SilentlyContinue
	$screens = @()
	$summary = ''
	switch ($Tier) {
		'world' {
			$ok = $lines | Select-String -Pattern 'required tests passed' | Select-Object -Last 1
			$bad = $lines | Select-String -Pattern 'tests? failed|BUILD FAILED|error:' | Select-Object -Last 1
			if ($ok) { $summary = "$($ok.Line.Trim()) (plus the in-JVM registry check)" }
			elseif ($bad) { $summary = ($bad.Line -replace '^\[[^\]]+\]\s*\[[^\]]+\]\s*\([^)]*\)\s*', '').Trim() }
			else { $summary = 'no result line' }
		}
		'look' {
			$dir = Join-Path $root 'build\run\clientGameTest\screenshots'
			if ($code -eq 0 -and (Test-Path $dir)) {
				$keep = Join-Path $runs $started.ToString('yyyyMMdd-HHmmss')
				New-Item -ItemType Directory -Force $keep | Out-Null
				$screens = Get-ChildItem $dir -Filter *.png | ForEach-Object {
					$dest = Join-Path $keep $_.Name
					Copy-Item $_.FullName $dest -Force
					$dest
				}
			}
			$summary = if ($code -eq 0) { "$($screens.Count) screenshots" } else { 'client test failed' }
		}
	}
	Write-Run -Tier $Tier -Ok ($code -eq 0) -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary $summary -Screens $screens -Log $log
	return ($code -eq 0)
}

function Invoke-World {
	# Direct replay of the gametest launch command: no Gradle, no daemon, about 16s.
	# Rebuild the gametest launch command from Loom's own launch config: no Gradle in the loop.
	$argFile = Join-Path $root 'build\loom-cache\argFiles\runGameTest'
	$launchCfg = Join-Path $root '.gradle\loom-cache\launch.cfg'
	$cmdFile = Join-Path $lab 'gametest-cmd.txt'
	if ((Test-Path $argFile) -and (Test-Path $launchCfg)) {
		$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
		$built = "$java -Dfabric-api.gametest -Dfabric.dli.config=$launchCfg -Dfabric.dli.env=server -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer @$argFile --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant net.fabricmc.devlaunchinjector.Main nogui"
		Set-Content $cmdFile $built
	}
	if (Test-Path $cmdFile) {
		$started = Get-Date
		$log = Join-Path $logs "$($started.ToString('yyyyMMdd-HHmmss'))-world.log"
		$cmd = (Get-Content $cmdFile -Raw).Trim()
		$runDir = Join-Path $root 'build\run\gameTest'
		New-Item -ItemType Directory -Force $runDir | Out-Null
		$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', $cmd) -WorkingDirectory $runDir -PassThru `
			-RedirectStandardOutput $log -RedirectStandardError (Join-Path $logs 'world.err') -WindowStyle Hidden
		$proc.WaitForExit(300000) | Out-Null
		$ok = $proc.ExitCode -eq 0
		$lines = Get-Content $log -ErrorAction SilentlyContinue
		$hit = $lines | Select-String -Pattern 'required tests passed|tests? failed' | Select-Object -Last 1
		$summary = if ($hit) { ($hit.Line -replace '^\[[^\]]+\]\s*\[[^\]]+\]\s*\([^)]*\)\s*', '').Trim() } else { 'no result line' }
		Write-Run -Tier 'world' -Ok $ok -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary $summary -Log $log
		return $ok
	}
	$prefix = Get-GradleTaskPrefix
	# A repo that ships its own gametest suite would run that suite here (Diorama: ten minutes).
	# Only run the world tier when the harness's own starter test is present; otherwise say so.
	$ours = Get-ChildItem (Join-Path $root 'src/gametest/java') -Recurse -Filter 'StarterGameTest.java' -ErrorAction SilentlyContinue
	if (-not $ours) {
		Write-Run -Tier 'world' -Ok $true -Seconds 0 -Summary 'skipped: this repo ships its own gametest suite; nothing claimed'
		return $true
	}
	return (Invoke-GradleTier -Tier 'world' -TaskArgs @("${prefix}runGameTest"))
}

function Start-Watch {
	Write-Host 'Watching src for saves. Ctrl+C to stop.' -ForegroundColor DarkGray
	Invoke-Fast | Out-Null
	$watcher = New-Object System.IO.FileSystemWatcher
	$watcher.Path = Join-Path $root 'src'
	$watcher.IncludeSubdirectories = $true
	$watcher.Filter = '*.*'
	$watcher.EnableRaisingEvents = $true
	while ($true) {
		$change = $watcher.WaitForChanged([System.IO.WatcherChangeTypes]::All, 600000)
		if ($change.TimedOut) { continue }
		Start-Sleep -Milliseconds 250
		Invoke-Fast | Out-Null
	}
}

. (Join-Path $root 'tools\mp.ps1')
. (Join-Path $root 'tools\portlog.ps1')
. (Join-Path $root 'tools\check.ps1')

function Get-NewestSource {
	# The newest file the server and client actually load. build outputs are not under src, so a
	# timestamp here is a human edit.
	$dirs = @((Join-Path $root 'src\main'), (Join-Path $root 'src\client')) | Where-Object { Test-Path $_ }
	if (-not $dirs) { return $null }
	return (Get-ChildItem $dirs -Recurse -File -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)
}

function Get-OldestMpStart {
	# The earlier of the server and the mp client: code newer than either one means the run would
	# answer with stale classes.
	$rows = @()
	foreach ($entry in @(@{ what = 'server'; port = $serverPort }, @{ what = 'client'; port = $mpPort })) {
		$owner = Get-PortOwner -Port $entry.port
		if ($owner -and $owner.Since) { $rows += [pscustomobject]@{ what = $entry.what; since = $owner.Since } }
	}
	if ($rows.Count -eq 0) { return $null }
	return ($rows | Sort-Object since | Select-Object -First 1)
}

function Stop-MpLab {
	# Only this repo's mp launchers: another project's lab is not ours to stop.
	$pattern = (@('start-server.cmd', 'start-mp.cmd', 'start-mp2.cmd') |
		ForEach-Object { [regex]::Escape((Join-Path $lab $_)) }) -join '|'
	Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and ($_.CommandLine -match $pattern) } |
		ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	Stop-Mp
	$deadline = (Get-Date).AddSeconds(30)
	while ((Get-Date) -lt $deadline) {
		if (-not (Test-MpClient) -and -not (Test-LabServer)) { return $true }
		Start-Sleep -Milliseconds 300
	}
	return (-not (Test-MpClient) -and -not (Test-LabServer))
}

# A second node keeps its own Gradle project and warm client. Route the verbs it can answer;
# say plainly when a tier is not ported there instead of pretending.
if ($Version -and $Version -ne '26.3') {
	$driver = Join-Path $root "versions\$Version\warm.ps1"
	if (-not (Test-Path $driver)) {
		$available = Get-ChildItem (Join-Path $root 'versions') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }
		Write-Host "No node '$Version'. Available: $(@($available) -join ', '), or omit -Version for 26.3." -ForegroundColor Red
		exit 2
	}
	switch ($Verb) {
		'ui' { & pwsh -NoProfile -File $driver ui; exit $LASTEXITCODE }
		'node' { & pwsh -NoProfile -File $driver node; exit $LASTEXITCODE }
		'start' { & pwsh -NoProfile -File $driver start; exit $LASTEXITCODE }
		'status' { & pwsh -NoProfile -File $driver status; exit $LASTEXITCODE }
		'stop' { & pwsh -NoProfile -File $driver stop; exit $LASTEXITCODE }
		'scenario' {
			if (-not $Steps) { Write-Host 'usage: modaccel -Version <node> scenario -Steps "..."' -ForegroundColor Red; exit 2 }
			& pwsh -NoProfile -File $driver scenario $Steps; exit $LASTEXITCODE
		}
		'suite' {
			$suiteFile = if ($File) { $File } else { Join-Path $root 'scenarios\prismlab.txt' }
			if (-not (Test-Path $suiteFile)) { Write-Host "no suite file $suiteFile" -ForegroundColor Red; exit 2 }
			$failed = 0; $cases = 0
			foreach ($line in Get-Content $suiteFile) {
				$t = $line.Trim()
				if (-not $t -or $t.StartsWith('#')) { continue }
				$split = $t.IndexOf('|')
				if ($split -lt 1) { continue }
				$cases++
				& pwsh -NoProfile -File $driver scenario $t.Substring($split + 1).Trim()
				if ($LASTEXITCODE -ne 0) { $failed++ }
			}
			Write-Host ("suite on {0}: {1} case(s), {2} failed" -f $Version, $cases, $failed) -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
			exit $(if ($failed -eq 0) { 0 } else { 1 })
		}
		default {
			Write-Host "'$Verb' is not ported to $Version yet. Ported there: ui, node, scenario, suite, start, status, stop." -ForegroundColor Yellow
			exit 2
		}
	}
}

switch ($Verb) {
	{ $_ -in 'check', 'fast' } {
		if (-not (Invoke-Lint)) { exit 1 }
		# `fast` is the lane itself; `check` also routes the change to the tier that can catch it.
		if ($Verb -eq 'fast') {
			$result = Invoke-Fast
			if (-not $result -or -not $result.ok) { exit 1 }
		}
		elseif (-not (Invoke-Auto)) { exit 1 }
	}
	'world' { if (-not (Invoke-World)) { exit 1 } }
	'ui' { if (-not (Invoke-Ui)) { exit 1 } }
	'node' { if (-not (Invoke-Ui -Command 'node')) { exit 1 } }
	'look' {
		if (-not (Invoke-GradleTier -Tier 'look' -TaskArgs @("$(Get-GradleTaskPrefix)runClientGameTest"))) { exit 1 }
		if ($Open) { Invoke-Item (Join-Path $root 'build\run\clientGameTest\screenshots') }
	}
	'all' {
		if (-not (Invoke-Lint)) { exit 1 }
		if (-not (Invoke-Fast)) { exit 1 }
		if (-not (Invoke-Ui)) { exit 1 }
		if (-not (Invoke-Ui -Command 'node')) { exit 1 }
		if (-not (Invoke-World)) { exit 1 }
	}
	'watch' { Start-Watch }
	'restart' { if (-not (Invoke-Restart)) { exit 1 } }
	'doctor' {
		$props = Get-Content (Join-Path $root 'gradle.properties') -Raw
		[pscustomobject]@{
			jdk        = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1)
			minecraft  = [regex]::Match($props, '(?m)^minecraft_version=(.+)$').Groups[1].Value
			loader     = [regex]::Match($props, '(?m)^loader_version=(.+)$').Groups[1].Value
			fabricApi  = [regex]::Match($props, '(?m)^fabric_api_version=(.+)$').Groups[1].Value
			fastLane   = if (Test-Daemon) { "running on port $(Get-DaemonPort)" } else { 'not running' }
			warmClient = if (Test-WarmClient) { "running on port $clientPort" } else { 'not running' }
		} | Format-List
	}
	'logs' {
		$file = Join-Path $runs 'index.jsonl'
		if (Test-Path $file) {
			Get-Content $file | Select-Object -Last 15 | ForEach-Object { $_ | ConvertFrom-Json } |
				Format-Table tier, ok, seconds, summary -AutoSize
		}
		else { 'No runs yet.' }
	}
	'diff' {
		if (-not $Old -or -not $New) {
			Write-Host 'usage: modaccel diff -Old <old minecraft jar> -New <new minecraft jar>' -ForegroundColor Red
			exit 2
		}
		$classes = Join-Path $root 'build\classes\java\main'
		$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
		$started = Get-Date
		& $java (Join-Path $root 'tools\SymbolDiff.java') $Old $New $classes
		$code = $LASTEXITCODE
		Write-Run -Tier 'diff' -Ok ($code -eq 0) -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary 'missing references in the target jar'
		exit $code
	}
	'port' {
		if ($Api) {
			if (-not $Old -or -not $New) {
				Write-Host 'usage: modaccel port -Api <old minecraft jar> <new minecraft jar>' -ForegroundColor Red
				Write-Host '       both jars must use the same mappings (yarn with yarn, mojmap with mojmap)' -ForegroundColor Red
				exit 2
			}
			# The version lives in the mappings segment of a loom jar path, which stays right even
			# when the checkout folder is named after the previous version (TB-1.21.4 holds 1.21.5).
			$label = {
				param($path)
				$match = [regex]::Match($path, '(?:mappings\.|yarn\.)(\d+_\d+(?:_\d+)?)')
				$text = if ($match.Success) { $match.Groups[1].Value } else { '' }
				if (-not $text) { $text = [regex]::Match($path, '\d+\.\d+(?:\.\d+)?').Value }
				$text -replace '_', '.'
			}
			$oldLabel = & $label $Old
			$newLabel = & $label $New
			if (-not $oldLabel -or -not $newLabel) {
				Write-Host "api: cannot read a Minecraft version out of these paths: $Old / $New" -ForegroundColor Red
				exit 2
			}
			$out = Join-Path $root ("knowledge\api-{0}-to-{1}.json" -f $oldLabel, $newLabel)
			$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
			$clock = [Diagnostics.Stopwatch]::StartNew()
			& $java (Join-Path $root 'tools\ApiDiff.java') $Old $New --out $out
			$code = $LASTEXITCODE
			if ($code -ne 0) { exit $code }
			$diff = Get-Content $out -Raw | ConvertFrom-Json
			if (-not $diff) {
				Write-Host "api: $out did not parse" -ForegroundColor Red
				exit 1
			}
			$counts = $diff.counts
			Write-Host ("api: {0} -> {1}: classes {2} removed, {3} moved, {4} added; members {5} removed ({6} methods, {7} fields); {8} signature changes" -f `
				$oldLabel, $newLabel, $counts.classesRemoved, $counts.classesMoved, $counts.classesAdded, `
				$counts.membersRemoved, $counts.methodsRemoved, $counts.fieldsRemoved, $counts.signatureChanges) -ForegroundColor Green
			foreach ($note in @($diff.notes)) {
				if ($note) { Write-Host "        $note" -ForegroundColor Yellow }
			}
			foreach ($row in @($diff.topChangedClasses) | Select-Object -First 5) {
				if (-not $row) { continue }
				Write-Host ("        {0}  methods -{1} fields -{2} signatures -{3}" -f $row.class, $row.methodsRemoved, $row.fieldsRemoved, $row.signatureChanges) -ForegroundColor DarkGray
			}
			Write-Host "        $out" -ForegroundColor DarkGray
			Write-Run -Tier 'api' -Ok $true -Seconds ([math]::Round($clock.Elapsed.TotalSeconds, 1)) -Summary ("{0} -> {1}: {2} classes moved, {3} members removed, {4} signature changes" -f $oldLabel, $newLabel, $counts.classesMoved, $counts.membersRemoved, $counts.signatureChanges)
			exit 0
		}
		if ($Fix) {
			if (-not $Log) {
				Write-Host 'usage: modaccel port -Fix -Log <build.log> [-Task <gradle task>] [-Max 5] [-DryRun] [-Repo <repo>]' -ForegroundColor Red
				exit 2
			}
			$result = Invoke-PortFix -Log $Log -Repo $Repo -Task $Task -Max $Max -DryRun:$DryRun `
				-FixesFile (Join-Path $root 'knowledge\fixes.json') -LogDir $logs -Harness $root
			if ($DryRun) { exit 0 }
			Write-Run -Tier 'portfix' -Ok $result.Clean -Seconds $result.Seconds -Summary ("{0} -> {1} error(s) in {2} round(s), {3} replacement(s)" -f $result.ErrorsBefore, $result.ErrorsAfter, $result.Rounds, $result.Replacements)
			if ($result.Clean) { exit 0 }
			exit 1
		}
		if ($Check) {
			# PowerShell variable names are case-insensitive, so a local $check would overwrite the
			# [string] parameter and the result would be cast back to a string. Keep them apart.
			$gate = Invoke-ToolchainCheck -Repo $Check -To $To
			if ($gate.Verdict -eq 'READY') { exit 0 }
			exit 1
		}
		if ($Log) {
			$report = Join-Path $root 'PORT-REPORT.md'
			$result = New-PortReportFromLog -Log $Log -Jar $New -Out $report -KnowledgeFile (Join-Path $root 'knowledge\version-changes.json') -Root $root -To $To
			Write-Host ("port: {0} error(s) in {1} file(s); report at {2}" -f $result.Errors, $result.Files, $report) -ForegroundColor DarkGray
			Get-Content $report | Select-Object -First 24 | ForEach-Object { Write-Host $_ }
			exit 1
		}
		if (-not $New) {
			Write-Host 'usage: modaccel port -New <target minecraft jar> [-Old <current jar>] [-Apply]' -ForegroundColor Red
			Write-Host '       modaccel port -Log <build log> [-New <target jar>]' -ForegroundColor Red
			Write-Host '       modaccel port -Fix -Log <build log> [-Task <gradle task>] [-Max 5] [-DryRun] [-Repo <repo>]' -ForegroundColor Red
			Write-Host '       modaccel port -Check <repo> [-To <minecraft version>]' -ForegroundColor Red
			exit 2
		}
		$classes = Join-Path $root 'build\classes\java\main'
		$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
		# Mechanical renames first: only the pairs this project actually hit, and only when asked.
		$renamesFile = Join-Path $root 'knowledge\renames.json'
		if (Test-Path $renamesFile) {
			$renames = Get-Content $renamesFile -Raw | ConvertFrom-Json | Where-Object { -not $_.skip -and $_.from -ne $_.to }
			$hits = @()
			$srcDir = Join-Path $root 'src'
			foreach ($path in [System.IO.Directory]::EnumerateFiles($srcDir, '*.java', [System.IO.SearchOption]::AllDirectories)) {
				$text = [System.IO.File]::ReadAllText($path)
				$updated = $text
				foreach ($rename in $renames) {
					$pattern = '\b' + [regex]::Escape($rename.from) + '\b'
					$count = ([regex]::Matches($updated, $pattern)).Count
					if ($count -gt 0) {
						$hits += [pscustomobject]@{ file = $path.Substring($root.Length + 1); from = $rename.from; to = $rename.to; count = $count }
						$updated = [regex]::Replace($updated, $pattern, $rename.to)
					}
				}
				if ($updated -ne $text -and $Apply) { [System.IO.File]::WriteAllText($path, $updated) }
			}
			if ($hits.Count -eq 0) {
				Write-Host 'renames: nothing in src matches the table' -ForegroundColor DarkGray
			}
			else {
				$hits | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
				if ($Apply) { Write-Host "renames: applied $($hits.Count) symbol(s); run .\modaccel next" -ForegroundColor Green }
				else { Write-Host 'renames: dry run, nothing written (add -Apply)' -ForegroundColor Yellow }
			}
		}
		$started = Get-Date
		$raw = & $java (Join-Path $root 'tools\SymbolDiff.java') ($(if ($Old) { $Old } else { $New })) $New $classes --json 2>$null
		$code = $LASTEXITCODE
		$result = $raw | ConvertFrom-Json
		$knowledge = @()
		$knowledgeFile = Join-Path $root 'knowledge\version-changes.json'
		if (Test-Path $knowledgeFile) { $knowledge = Get-Content $knowledgeFile -Raw | ConvertFrom-Json }
		$notes = @()
		$tokenPattern = '[A-Za-z_][A-Za-z0-9_]{2,}'
		foreach ($miss in @($result.missing) + @($result.classes)) {
			if (-not $miss) { continue }
			$name = if ($miss.name) { $miss.name } else { $miss.class }
			$short = ($name -split '/')[-1]
			if ($short.Contains('(')) { $short = $short.Substring(0, $short.IndexOf('(')) }
			foreach ($entry in $knowledge) {
				if (-not $entry.from -or -not $entry.to) { continue }
				$before = @([regex]::Matches([string]$entry.from, $tokenPattern) | ForEach-Object { $_.Value })
				$after = @([regex]::Matches([string]$entry.to, $tokenPattern) | ForEach-Object { $_.Value })
				if ($before -contains $short -and -not ($after -contains $short)) {
					$notes += "  $short -> $($entry.to)  [$($entry.jump) $($entry.kind)] $($entry.note)"
				}
			}
		}
		$report = Join-Path $root 'PORT-REPORT.md'
		$lines = @("# Port report", "", "Target jar: $New", "Generated: $(Get-Date -Format s)", "")
		$lines += "Missing members: $($result.counts.missingMembers) / missing classes: $($result.counts.missingClasses) / mod classes: $($result.counts.modClasses)"
		$lines += ""
		if ($notes.Count -gt 0) { $lines += "## Known fixes from the knowledge base"; $lines += ($notes | Sort-Object -Unique); $lines += "" }
		$lines += "## Handoff"
		$lines += "Work through the diff below: each entry is file, symbol and the nearest candidate in the target jar."
		$lines += "After each fix, run ``.\modaccel`` for logic and ``.\modaccel world`` for registrations."
		$lines += ""
		$lines += '```'
		$lines += (& $java (Join-Path $root 'tools\SymbolDiff.java') ($(if ($Old) { $Old } else { $New })) $New $classes 2>$null)
		$lines += '```'
		$lines | Set-Content $report
		Write-Host "port: report at $report" -ForegroundColor DarkGray
		if ($notes.Count -gt 0) { $notes | Sort-Object -Unique | Select-Object -First 12 | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray } }
		Write-Run -Tier 'port' -Ok ($code -eq 0) -Seconds ([math]::Round(((Get-Date) - $started).TotalSeconds, 1)) -Summary "$($result.counts.missingMembers) missing members, $($result.counts.missingClasses) missing classes"
		exit $code
	}
	'scenario' {
		if (-not $Steps) {
			Write-Host 'usage: modaccel scenario -Steps "place=ns:id|wait=40|assert=charge|capture=name"' -ForegroundColor Red
			exit 2
		}
		if (-not (Test-WarmClient)) {
			if (-not (Start-WarmClient)) { exit 1 }
		}
		if ($bridgeListener) {
			if (-not (Invoke-BridgeScenario -Steps $Steps -Started (Get-Date))) { exit 1 }
			exit 0
		}
		$started = Get-Date
		$scenarioShots = Join-Path $root 'build\run\warm\shots'
		$scenarioBefore = Create-ShotBaseline -Dir $scenarioShots
		$line = Send-Line -Port $clientPort -Text ("scenario " + $Steps)
		$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
		$result = $line | ConvertFrom-Json
		$shots = @()
		if ($result.ok) { $shots = Get-NewShots -Dir $scenarioShots -Before $scenarioBefore }
		$summary = if ($result.ok) { $result.lines } else { "$($result.error) | no screenshot was written by this run" }
		Write-Run -Tier 'scenario' -Ok ([bool]$result.ok) -Seconds $seconds -Summary $summary -Screens $shots
		if (-not $result.ok) { exit 1 }
	}
	'mp' {
		if (-not $Steps) {
			Write-Host 'usage: modaccel mp -Steps "place=ns:id|wait=60|assert=charge|capture=mp-node" [-Cold]' -ForegroundColor Red
			exit 2
		}
		Reset-Stage
		Write-Stage 'mp: dedicated server + hidden client + one scenario'
		if ($Cold) {
			Write-Stage 'mp -Cold: stopping this repo''s mp server and client so they load the sources on disk'
			if (-not (Stop-MpLab)) {
				$why = 'could not stop the old mp server/client (ports 25565 or 27389 still busy)'
				Write-Run -Tier 'mp' -Ok $false -Seconds ([math]::Round($script:stageClock.Elapsed.TotalSeconds, 1)) -Summary $why
				Write-Stage $why 'Red'
				exit 1
			}
		}
		if (-not (Test-MpClient)) {
			if (-not (Start-LabServer)) { exit 1 }
			if (-not (Start-MpClient)) { exit 1 }
		}
		# A warm server keeps the classes it started with, so a block entity that lost its update
		# overrides, or a ticker that stopped, still reads as PASS. Sources newer than the process
		# mean the answer is about old code: refuse instead of reporting it.
		$newest = Get-NewestSource
		$oldest = Get-OldestMpStart
		if ($newest -and $oldest -and $newest.LastWriteTime -gt $oldest.since) {
			$summary = "server predates these sources; run .\modaccel mp -Cold ({0} changed {1:HH:mm:ss}, {2} started {3:HH:mm:ss})" -f `
				$newest.Name, $newest.LastWriteTime, $oldest.what, $oldest.since
			Write-Run -Tier 'mp' -Ok $false -Seconds ([math]::Round($script:stageClock.Elapsed.TotalSeconds, 1)) -Summary $summary
			Write-Stage $summary 'Red'
			exit 1
		}
		Write-Stage "mp: running scenario (waits, commands and screenshots happen inside the client)"
		$started = Get-Date
		$shotDir = Join-Path $root 'build\run\mp\shots'
		$shotBefore = Create-ShotBaseline -Dir $shotDir
		$line = Send-Line -Port $mpPort -Text ('scenario ' + $Steps)
		$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
		$result = $line | ConvertFrom-Json
		$shots = @()
		if ($result.ok) { $shots = Get-NewShots -Dir $shotDir -Before $shotBefore }
		$summary = if ($result.ok) { $result.lines } else { "$($result.error) | no screenshot was written by this run" }
		Write-Stage ("mp: finished in {0:N1}s total" -f $script:stageClock.Elapsed.TotalSeconds) 'DarkGray'
		Write-Run -Tier 'mp' -Ok ([bool]$result.ok) -Seconds $seconds -Summary $summary -Screens $shots
		if (-not $result.ok) { exit 1 }
		if ($Two) {
			# A second player proves the server actually sent the block and its data to someone
			# who is not the one who placed it.
			$coords = [regex]::Match($result.lines, 'at (-?\d+), (-?\d+), (-?\d+)')
			if (-not $coords.Success) {
				Write-Host 'mp -Two: the first scenario did not report coordinates (use place=...)' -ForegroundColor Yellow
				exit 1
			}
			if (-not (Start-MpClient -Port 27379 -Config 'clientMp2' -Tag 'mp2')) { exit 1 }
			$at = "$($coords.Groups[1].Value),$($coords.Groups[2].Value),$($coords.Groups[3].Value)"
			Write-Stage "mp: second client checks the block the server placed at $at"
			$secondStart = Get-Date
			$secondDir = Join-Path $root 'build\run\mp2\shots'
			$secondBefore = Create-ShotBaseline -Dir $secondDir
			$secondLine = Send-Line -Port 27379 -Text ("scenario at=$at|assert=charge|settle|capture=mp-second")
			$secondSeconds = [math]::Round(((Get-Date) - $secondStart).TotalSeconds, 1)
			$second = $secondLine | ConvertFrom-Json
			$secondShots = @()
			if ($second.ok) { $secondShots = Get-NewShots -Dir $secondDir -Before $secondBefore -Keep 1 }
			$secondSummary = if ($second.ok) { $second.lines } else { "$($second.error) | no screenshot was written by this run" }
			Write-Run -Tier 'mp2' -Ok ([bool]$second.ok) -Seconds $secondSeconds -Summary $secondSummary -Screens $secondShots
			if (-not $second.ok) { exit 1 }
		}
	}
	'status' {
		Show-LabStatus
	}
	'suite' {
		$suiteFile = if ($File) { $File } else { Join-Path $root 'scenarios\prismlab.txt' }
		if (-not (Test-Path $suiteFile)) {
			Write-Host "usage: modaccel suite -File <scenarios.txt> (default scenarios\prismlab.txt)" -ForegroundColor Red
			exit 2
		}
		$cases = @()
		foreach ($line in Get-Content $suiteFile) {
			$trimmed = $line.Trim()
			if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
			$split = $trimmed.IndexOf('|')
			if ($split -lt 1) { Write-Host "bad line: $trimmed" -ForegroundColor Red; exit 2 }
			$cases += [pscustomobject]@{ Name = $trimmed.Substring(0, $split).Trim(); Steps = $trimmed.Substring($split + 1).Trim() }
		}
		if ($cases.Count -eq 0) { Write-Host "no cases in $suiteFile" -ForegroundColor Red; exit 2 }
		if (-not (Test-WarmClient)) {
			if (-not (Start-WarmClient)) { exit 1 }
		}
		$started = Get-Date
		$failed = 0
		$shots = @()
		foreach ($case in $cases) {
			$caseStart = Get-Date
			if ($bridgeListener) {
				if (-not (Invoke-BridgeScenario -Steps $case.Steps -Started $caseStart)) { $failed++ }
				continue
			}
			try {
				$line = Send-Line -Port $clientPort -Text ("scenario " + $case.Steps)
				if (-not $line) { throw "the warm client stopped answering (see .modaccel\client.log)" }
				$result = $line | ConvertFrom-Json
				$seconds = [math]::Round(((Get-Date) - $caseStart).TotalSeconds, 1)
				$summary = if ($result.ok) { $result.lines } else { $result.error }
				if (-not $result.ok) { $failed++ }
				Write-Run -Tier ("suite:" + $case.Name) -Ok ([bool]$result.ok) -Seconds $seconds -Summary $summary
			}
			catch {
				$failed++
				Write-Host ("FAIL  {0,-18} {1}" -f $case.Name, $_.Exception.Message) -ForegroundColor Red
			}
		}
		$total = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
		if (Test-Path (Join-Path $root 'build\run\warm\shots')) {
			$shots = Get-ChildItem (Join-Path $root 'build\run\warm\shots') -Filter *.png | Sort-Object LastWriteTime -Descending | Select-Object -First 4 | ForEach-Object { $_.FullName }
		}
		Write-Host ("suite: {0} case(s), {1} failed, {2}s total" -f $cases.Count, $failed, $total) -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
		$shots | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
		if ($failed -gt 0) { exit 1 }
	}
	'bench' {
		$times = @()
		for ($i = 1; $i -le $Count; $i++) {
			$started = Get-Date
			switch ($Tier) {
				'fast' { Invoke-Fast | Out-Null }
				'lint' { Invoke-Lint | Out-Null }
				'ui' { Invoke-Ui | Out-Null }
				'node' { Invoke-Ui -Command 'node' | Out-Null }
				'world' { Invoke-World | Out-Null }
				'look' { Invoke-GradleTier -Tier 'look' -TaskArgs @("$(Get-GradleTaskPrefix)runClientGameTest") | Out-Null }
				'scenario' {
					$first = Get-Content (Join-Path $root 'scenarios\prismlab.txt') | Where-Object { $_ -match '\|' -and -not $_.TrimStart().StartsWith('#') } | Select-Object -First 1
					Send-Line -Port $clientPort -Text ('scenario ' + $first.Substring($first.IndexOf('|') + 1).Trim()) | Out-Null
				}
				'suite' {
					foreach ($line in Get-Content (Join-Path $root 'scenarios\prismlab.txt')) {
						$t = $line.Trim()
						if (-not $t -or $t.StartsWith('#')) { continue }
						Send-Line -Port $clientPort -Text ('scenario ' + $t.Substring($t.IndexOf('|') + 1).Trim()) | Out-Null
					}
				}
			}
			$times += [math]::Round(((Get-Date) - $started).TotalSeconds, 2)
		}
		$sorted = $times | Sort-Object
		$result = [pscustomobject]@{
			tier = $Tier; runs = $Count; times = $times
			min = $sorted[0]; median = $sorted[[int]($sorted.Count / 2)]; max = $sorted[-1]
			mean = [math]::Round(($times | Measure-Object -Average).Average, 2)
		}
		$result | ConvertTo-Json -Compress | Add-Content (Join-Path $runs 'bench.jsonl')
		$result | Format-List
	}
	'stop' {
		Stop-Daemon
		Stop-WarmClient
		if (Get-Command Stop-Mp -ErrorAction SilentlyContinue) { Stop-Mp }
		foreach ($node in Get-ChildItem (Join-Path $root 'versions') -Directory -ErrorAction SilentlyContinue) {
			$driver = Join-Path $node.FullName 'warm.ps1'
			if (Test-Path $driver) { & pwsh -NoProfile -File $driver stop }
		}
		Write-Host 'Fast lane and warm client stopped.' -ForegroundColor DarkGray
	}
}


























































