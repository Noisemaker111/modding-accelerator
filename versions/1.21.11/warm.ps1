#requires -Version 7.0
<#
  Warm-client driver for the Minecraft 1.21.11 node.

  Same socket protocol as the parent's modaccel.ps1 warm client, its own ports:
  client 27499, agent 27498. Runs from versions\1.21.11.

    .\warm.ps1 start                 start the client and wait for the socket
    .\warm.ps1 node                  place the node, wait 40 ticks, assert charge, settle, screenshot
    .\warm.ps1 scenario "<steps>"    run any 'a|b|c' step list
    .\warm.ps1 status | stop
#>
param(
	[Parameter(Position = 0)]
	[ValidateSet('start', 'node', 'scenario', 'ui', 'status', 'stop')]
	[string]$Verb = 'node',
	[string]$Steps = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$lab = Join-Path $root '.modaccel'
$parent = (Resolve-Path (Join-Path $root '..\..')).Path
$clientPort = 27499
$agentPort = 27498
$clientLog = Join-Path $lab 'client.log'
$clientErr = Join-Path $lab 'client.err'
$pidFile = Join-Path $lab 'client.pid'
New-Item -ItemType Directory -Force $lab | Out-Null

$jdk = Get-ChildItem (Join-Path $parent '.tools') -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | Select-Object -First 1
$jbr = Get-ChildItem (Join-Path $parent '.tools') -Directory -Filter 'jbr-*' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { Write-Host "No JDK in $parent\.tools" -ForegroundColor Red; exit 2 }
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$env:PATH"

function Send-Line {
	param([int]$Port, [string]$Text, [int]$TimeoutSeconds = 180)
	$client = [System.Net.Sockets.TcpClient]::new()
	$client.ReceiveTimeout = $TimeoutSeconds * 1000
	$client.Connect('127.0.0.1', $Port)
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

function Build-Agent {
	$classes = Join-Path $lab 'agent-classes'
	$jar = Join-Path $lab 'agent.jar'
	$manifest = Join-Path $lab 'agent-manifest.txt'
	$src = Join-Path $parent 'tools\LabAgent.java'
	if ((Test-Path $jar) -and ((Get-Item $jar).LastWriteTime -gt (Get-Item $src).LastWriteTime)) { return }
	New-Item -ItemType Directory -Force $classes | Out-Null
	& "$env:JAVA_HOME\bin\javac.exe" -d $classes $src 2>&1 | Out-Null
	Set-Content $manifest "Premain-Class: LabAgent`nAgent-Class: LabAgent`nCan-Redefine-Classes: true`nCan-Retransform-Classes: true"
	& "$env:JAVA_HOME\bin\jar.exe" --create --file $jar --manifest $manifest -C $classes . 2>&1 | Out-Null
}

function Test-ClientProcess {
	$found = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
		Where-Object { $_.CommandLine -match 'modding-accelerator\\versions\\1\.21\.11' -and $_.CommandLine -match 'devlaunchinjector' }
	return [bool]$found
}

function Start-Client {
	if (Test-Lab) { return $true }
	# One client per node: a client that is already booting must not get a twin, and two
	# clients racing for the same port leave the loser invisible to every check.
	if (Test-ClientProcess) {
		Write-Host 'A client for this node is already starting; waiting for its socket...' -ForegroundColor DarkGray
		$deadline = (Get-Date).AddSeconds(150)
		while ((Get-Date) -lt $deadline) {
			if (Test-Lab) { return $true }
			Start-Sleep -Milliseconds 500
		}
		# Still no socket: take the stuck client down so the new one is the only one.
		if (Test-ClientProcess) {
			Write-Host 'That client never answered; replacing it.' -ForegroundColor Yellow
			Stop-Client
		}
		if (Test-Lab) { return $true }
	}
	Build-Agent
	# JBR carries -XX:+AllowEnhancedClassRedefinition, which the run config asks for.
	$clientHome = if ($jbr) { $jbr.FullName } else { $env:JAVA_HOME }
	$launcher = Join-Path $lab 'start-client.cmd'
	Set-Content $launcher "@echo off`r`nset JAVA_HOME=$clientHome`r`ncd /d `"$root`"`r`ngradlew.bat runClientWarm --console=plain > `"$clientLog`" 2> `"$clientErr`"`r`nexit"
	# Loom's build fails once now and then right after a source change, and the launcher then
	# exits without a client. Retry that; a slow first build is waited out, not duplicated.
	for ($attempt = 1; $attempt -le 2; $attempt++) {
		Write-Host $(if ($attempt -eq 1) { 'Starting the 1.21.11 warm client...' } else { 'Launcher exited without a client; retrying once...' }) -ForegroundColor DarkGray
		Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'start', '""', '/b', $launcher) -WorkingDirectory $root -WindowStyle Hidden
		$started = Get-Date
		$deadline = $started.AddSeconds(300)
		$exited = $false
		while ((Get-Date) -lt $deadline) {
			if (Test-Lab) { return $true }
			if (((Get-Date) - $started).TotalSeconds -gt 25) {
				# Gradle failing takes seconds; a build that is still going is left alone.
				if (-not (Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'start-client\.cmd' })) {
					$exited = $true
					break
				}
			}
			Start-Sleep -Milliseconds 250
		}
		if (-not $exited) { break }
		Start-Sleep -Seconds 2
	}
	Write-Host "Warm client did not answer on port $clientPort. See $clientLog" -ForegroundColor Red
	return $false
}

function Stop-Client {
	Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'start-client\.cmd' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	if (Test-Lab) {
		try { Send-Line -Port $clientPort -Text 'quit' -TimeoutSeconds 10 | Out-Null } catch { }
		Start-Sleep -Milliseconds 1500
	}
	# The game process itself, matched on this node's own project path only. A client that
	# was in the world can take a few seconds to save and exit, and the next start must not
	# see it as a client that is still booting.
	for ($try = 0; $try -lt 30; $try++) {
		$clients = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
			Where-Object { $_.CommandLine -match 'modding-accelerator\\versions\\1\.21\.11' -and $_.CommandLine -match 'devlaunchinjector' }
		if (-not $clients) { break }
		$clients | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
		Start-Sleep -Milliseconds 500
	}
	if (Test-ClientProcess) { Write-Host 'The 1.21.11 client is still shutting down.' -ForegroundColor Yellow }
	if (Test-Path $pidFile) {
		$old = (Get-Content $pidFile -Raw).Trim()
		if ($old) { Stop-Process -Id ([int]$old) -Force -ErrorAction SilentlyContinue }
		Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
	}
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
			'node' { 'scenario place=prismlab:prism_node|wait=40|assert=charge|settle|capture=1.21.11-node' }
			'ui' { 'ui' }
			'scenario' { "scenario $Steps" }
		}
		if (-not (Invoke-Storm -Command $command)) { exit 1 }
	}
}
