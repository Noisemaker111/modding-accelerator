#requires -Version 7.0
<#
  Warm-client driver for the NeoForge 1.21.1 node.

  Same socket protocol as the parent's modaccel.ps1 warm client and the 1.21.11 node,
  on this node's own ports: client 27599, agent 27598 (the agent is not attached here).
  Runs from versions\neoforge-1.21.1.

    .\warm.ps1 start                 start the client and wait for the socket
    .\warm.ps1 node                  place the node, wait 40 ticks, assert charge, settle, screenshot
    .\warm.ps1 scenario "<steps>"    run any 'a|b|c' step list
    .\warm.ps1 ui                    open PrismScreen, screenshot, click, screenshot
    .\warm.ps1 status | stop
#>
param(
	[Parameter(Position = 0)]
	[ValidateSet('start', 'node', 'scenario', 'ui', 'status', 'stop')]
	[string]$Verb = 'node',
	[Parameter(Position = 1)]
	[string]$Steps = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$lab = Join-Path $root '.modaccel'
$clientPort = 27599
$agentPort = 27598
$clientLog = Join-Path $lab 'client.log'
$clientErr = Join-Path $lab 'client.err'
New-Item -ItemType Directory -Force $lab | Out-Null

$parent = (Resolve-Path (Join-Path $root '..\..')).Path
$jdk = Get-ChildItem (Join-Path $parent '.tools') -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { Write-Host "No JDK in $parent\.tools" -ForegroundColor Red; exit 2 }
# Gradle itself runs on the newest JDK; the game runs on the Java 21 toolchain that
# ModDevGradle resolves (and Gradle downloads it on first use).
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$env:PATH"

function Send-Line {
	param([int]$Port, [string]$Text, [int]$TimeoutSeconds = 240)
	$client = [System.Net.Sockets.TcpClient]::new()
	$client.ReceiveTimeout = $TimeoutSeconds * 1000
	$client.Connect('localhost', $Port)
	$stream = $client.GetStream()
	$writer = [System.IO.StreamWriter]::new($stream)
	$reader = [System.IO.StreamReader]::new($stream)
	$writer.WriteLine($Text); $writer.Flush()
	$line = $reader.ReadLine()
	$client.Close()
	return $line
}

function Test-Lab {
	try {
		$line = Send-Line -Port $clientPort -Text 'root' -TimeoutSeconds 10
		return ($line -and ($line | ConvertFrom-Json).root -eq $root)
	}
	catch { return $false }
}

# The agent holds the Instrumentation handle and redefines class bodies over loopback, which
# is the only way a running client can take new code without a restart.
function Build-Agent {
	$jar = Join-Path $lab 'agent.jar'
	$src = Join-Path $parent 'tools\LabAgent.java'
	if (-not (Test-Path $src)) { Write-Host "No $src" -ForegroundColor Red; return $false }
	if ((Test-Path $jar) -and ((Get-Item $jar).LastWriteTime -gt (Get-Item $src).LastWriteTime) -and
		(& (Join-Path $jdk.FullName 'bin\jar.exe') --list --file $jar 2>$null | Where-Object { $_ -eq 'LabAgent.class' })) { return $true }
	$classes = Join-Path $lab 'agent-classes'
	$manifest = Join-Path $lab 'agent-manifest.txt'
	New-Item -ItemType Directory -Force $classes | Out-Null
	# The game runs on the Java 21 toolchain, so the agent is built for 21 even though
	# javac here is 25 (a class file 69 premain would refuse to load in a 21 VM).
	& (Join-Path $jdk.FullName 'bin\javac.exe') --release 21 -nowarn -d $classes $src 2>&1 | Out-Null
	if ($LASTEXITCODE -ne 0) { Write-Host 'agent did not compile' -ForegroundColor Red; return $false }
	Set-Content $manifest "Premain-Class: LabAgent`nAgent-Class: LabAgent`nCan-Redefine-Classes: true`nCan-Retransform-Classes: true"
	& (Join-Path $jdk.FullName 'bin\jar.exe') --create --file $jar --manifest $manifest -C $classes . 2>&1 | Out-Null
	return (Test-Path $jar)
}

# The Java 21 toolchain ModDevGradle resolved for NeoForge 21.1.x (not the shell's JDK).
function Get-GameJava {
	$version = ''
	$artifact = Get-ChildItem (Join-Path $root 'build\moddev\artifacts') -Filter 'neoforge-*.jar' -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -match '^neoforge-(\d+)\.' } | Select-Object -First 1
	if ($artifact) { $version = [regex]::Match($artifact.Name, '^neoforge-(\d+)\.').Groups[1].Value }
	if ($version) {
		$toolchain = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue |
			Where-Object { $_.Name -match "(^|[-_.])$version([-_.]|$)" -and (Test-Path (Join-Path $_.FullName 'bin\java.exe')) } |
			Sort-Object Name -Descending | Select-Object -First 1
		if ($toolchain) { return (Join-Path $toolchain.FullName 'bin\java.exe') }
	}
	return (Join-Path $jdk.FullName 'bin\java.exe')
}

function Start-Client {
	if (Test-Lab) { return $true }
	$launcher = Join-Path $lab 'start-client.cmd'
	$neoVm = Join-Path $root 'build\moddev\clientWarmRunVmArgs.txt'
	$neoProg = Join-Path $root 'build\moddev\clientWarmRunProgramArgs.txt'
	$cpArgs = Join-Path $lab 'neoforge-cp.args'
	if ((Test-Path $neoVm) -and (Test-Path $neoProg) -and (Test-Path $cpArgs) -and (Build-Agent)) {
		# ModDevGradle's own launch recipe: java + @vmargs + -cp @argfile + @programargs, with
		# the agent added. No Gradle in the loop, so a restart is a JVM start, not a build.
		$game = Get-GameJava
		$agentJar = Join-Path $lab 'agent.jar'
		$modClasses = Join-Path $root 'build\classes\java\main'
		$modResources = Join-Path $root 'build\resources\main'
		# In a .bat a literal percent must be doubled.
		$modFolders = "prismlab%%%%$modClasses;prismlab%%%%$modResources"
		# The same classpath in plain form, for HotUi when BootstrapLauncher trims java.class.path.
		$raw = ((Get-Content $cpArgs -Raw).Trim() -replace '^-cp\s*"?', '') -replace '"?$', ''
		$entries = ($raw -replace '\\\\', '\') -split ';' | Where-Object { $_ -and (Test-Path $_) }
		Set-Content (Join-Path $lab 'hot-cp.txt') ($entries -join ';') -NoNewline
		Set-Content $launcher ("@echo off`r`nset JAVA_HOME=$((Split-Path -Parent (Split-Path -Parent $game)))`r`ncd /d `"$root\run`"`r`n`"$game`" @`"$neoVm`" @`"$cpArgs`" -javaagent:`"$agentJar`" -Dprismlab.agent.port=$agentPort -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant -Dfml.modFolders=`"$modFolders`" net.neoforged.devlaunch.Main @`"$neoProg`" > `"$clientLog`" 2> `"$clientErr`"`r`nexit")
		Write-Host 'Starting the NeoForge 1.21.1 warm client from ModDevGradle''s recipe, with -javaagent...' -ForegroundColor DarkGray
	}
	else {
		Set-Content $launcher "@echo off`r`nset JAVA_HOME=$($jdk.FullName)`r`ncd /d `"$root`"`r`ngradlew.bat runClientWarm --console=plain > `"$clientLog`" 2> `"$clientErr`"`r`nexit"
		Write-Host 'Starting the NeoForge 1.21.1 warm client through Gradle (no launch recipe saved)...' -ForegroundColor DarkGray
	}
	Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'start', '""', '/b', $launcher) -WorkingDirectory $root -WindowStyle Hidden
	$deadline = (Get-Date).AddSeconds(600)
	while ((Get-Date) -lt $deadline) {
		if (Test-Lab) { return $true }
		Start-Sleep -Milliseconds 250
	}
	Write-Host "Warm client did not answer on port $clientPort. See $clientLog" -ForegroundColor Red
	return $false
}

function Stop-Client {
	if (Test-Lab) {
		try { Send-Line -Port $clientPort -Text 'quit' -TimeoutSeconds 10 | Out-Null } catch { }
		Start-Sleep -Milliseconds 2000
	}
	Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'start-client\.cmd' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	# The game process itself, matched on this node's own project path only.
	Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
		Where-Object { $_.CommandLine -match 'modding-accelerator\\versions\\neoforge-1\.21\.1' } |
		ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-Storm {
	param([string]$Command)
	$shotDir = Join-Path $root 'build\run\warm\shots'
	$started = Get-Date
	$line = Send-Line -Port $clientPort -Text $Command
	$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 2)
	$result = $line | ConvertFrom-Json
	$summary = if ($result.ok) { $result.lines } else { $result.error }
	$tag = if ($result.ok) { 'PASS' } else { 'FAIL' }
	$color = if ($result.ok) { 'Green' } else { 'Red' }
	Write-Host ("{0} {1,6}s  {2}" -f $tag, $seconds, $summary) -ForegroundColor $color
	$shots = @()
	# Only this run's screenshots, so the list is the answer for this command.
	if (Test-Path $shotDir) {
		$shots = Get-ChildItem $shotDir -Filter *.png | Where-Object { $_.LastWriteTime -ge $started.AddSeconds(-1) } |
			Sort-Object Name | ForEach-Object { $_.FullName }
	}
	foreach ($shot in $shots) { Write-Host ("        {0}" -f $shot) -ForegroundColor DarkGray }
	return [bool]$result.ok
}

switch ($Verb) {
	'start' {
		$started = Get-Date
		if (Start-Client) { Write-Host ("PASS {0,6}s  warm client up on {1}" -f [math]::Round(((Get-Date) - $started).TotalSeconds, 1), $clientPort) -ForegroundColor Green }
		else { exit 1 }
	}
	'status' {
		if (Test-Lab) { Write-Host "up on $clientPort" -ForegroundColor Green } else { Write-Host 'down' -ForegroundColor Yellow; exit 1 }
	}
	'stop' { Stop-Client }
	default {
		if (-not (Start-Client)) { exit 1 }
		$command = switch ($Verb) {
			'node' { 'scenario place=prismlab:prism_node|wait=40|assert=charge|settle|capture=neoforge-node' }
			'ui' { 'ui' }
			'scenario' { "scenario $Steps" }
		}
		if (-not (Invoke-Storm -Command $command)) { exit 1 }
	}
}
