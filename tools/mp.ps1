# Lab processes and the multiplayer tier.
# Loaded by modaccel.ps1; every function reads $root and the lab paths from there.

$mpPort = 27389
$serverPort = 25565
$mpRunDir = Join-Path $root 'build\run\mp'
$serverRunDir = Join-Path $root 'build\run\serverLab'

$script:stageClock = [System.Diagnostics.Stopwatch]::StartNew()

# Every long step says what it is doing and how long it has been doing it, so a command that
# prints nothing is never ambiguous.
function Write-Stage {
	param([string]$Text, [string]$Color = 'Cyan')
	Write-Host ("[{0,6:N1}s] {1}" -f $script:stageClock.Elapsed.TotalSeconds, $Text) -ForegroundColor $Color
}

function Reset-Stage {
	$script:stageClock.Restart()
}

function Test-Port {
	param([int]$Port)
	try { $c = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $Port); $c.Close(); return $true } catch { return $false }
}

function Get-PortOwner {
	param([int]$Port)
	try {
		$conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
		if (-not $conn) { return $null }
		$proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
		if (-not $proc) { return $null }
		return [pscustomobject]@{
			Pid = $proc.Id
			Name = $proc.ProcessName
			Since = $proc.StartTime
			Age = (Get-Date) - $proc.StartTime
			CommandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue).CommandLine
		}
	}
	catch { return $null }
}

function Format-Age {
	param($Span)
	if (-not $Span) { return '' }
	if ($Span.TotalHours -ge 1) { return '{0:N0}h{1:N0}m' -f $Span.TotalHours, $Span.Minutes }
	if ($Span.TotalMinutes -ge 1) { return '{0:N0}m{1:N0}s' -f $Span.TotalMinutes, $Span.Seconds }
	return '{0:N0}s' -f $Span.TotalSeconds
}

function Show-LabStatus {
	Write-Host "lab        $root" -ForegroundColor DarkGray
	$rows = @(
		@{ Name = 'fast lane'; Port = (Get-DaemonPort); Kind = 'daemon' }
		@{ Name = 'warm client'; Port = $clientPort; Kind = 'client' }
		@{ Name = 'mp client'; Port = $mpPort; Kind = 'client' }
		@{ Name = 'lab server'; Port = $serverPort; Kind = 'server' }
	)
	foreach ($row in $rows) {
		$owner = if ($row.Port -gt 0) { Get-PortOwner -Port $row.Port } else { $null }
		if ($owner) {
			$what = if ($owner.CommandLine -match 'runServerLab') { 'dedicated server' }
				elseif ($owner.CommandLine -match 'modlab\.run=mp') { 'JBR client (mp)' }
				elseif ($owner.CommandLine -match 'runClientWarm') { 'JBR client (warm)' }
				elseif ($row.Kind -eq 'daemon') { 'fast-lane JVM' }
				else { $owner.Name }
			Write-Host ("{0,-11} up   port {1,-5} pid {2,-6} {3,-18} {4}" -f $row.Name, $row.Port, $owner.Pid, (Format-Age $owner.Age), $what) -ForegroundColor Green
		}
		elseif ($row.Port -gt 0) {
			Write-Host ("{0,-11} down port {1}" -f $row.Name, $row.Port) -ForegroundColor DarkGray
		}
		else {
			Write-Host ("{0,-11} down" -f $row.Name) -ForegroundColor DarkGray
		}
	}
	# Nodes keep their own ports in versions\<node>\warm.ps1; show the ones that are up.
	foreach ($node in Get-ChildItem (Join-Path $root 'versions') -Directory -ErrorAction SilentlyContinue) {
		$driver = Join-Path $node.FullName 'warm.ps1'
		if (-not (Test-Path $driver)) { continue }
		$match = [regex]::Match((Get-Content $driver -Raw), '\$clientPort\s*=\s*(\d+)')
		if (-not $match.Success) { continue }
		$port = [int]$match.Groups[1].Value
		$owner = Get-PortOwner -Port $port
		if ($owner) {
			Write-Host ("{0,-11} up   port {1,-5} pid {2,-6} {3,-18} {4}" -f $node.Name, $port, $owner.Pid, (Format-Age $owner.Age), 'JBR client (node)') -ForegroundColor Green
		}
	}
	$others = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
		$_.CommandLine -match 'runServerLab|runClientWarm|runClientMp|modlab\.run=mp'
	}
	Write-Host ("java lab processes: {0}" -f @($others).Count) -ForegroundColor DarkGray
}

function Test-LabServer {
	Test-Port -Port $serverPort
}

function Test-MpClient {
	param([int]$Port = $mpPort)
	if (-not (Test-Port -Port $Port)) { return $false }
	try {
		$c = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
		$stream = $c.GetStream()
		$w = [System.IO.StreamWriter]::new($stream)
		$r = [System.IO.StreamReader]::new($stream)
		$w.WriteLine('root'); $w.Flush()
		$line = $r.ReadLine()
		$c.Close()
		return ($line -and ($line | ConvertFrom-Json).root -eq $root)
	}
	catch { return $false }
}

function Start-LabServer {
	if (Test-LabServer) {
		$owner = Get-PortOwner -Port $serverPort
		Write-Stage ("lab server already up on {0} (pid {1}, {2})" -f $serverPort, $owner.Pid, (Format-Age $owner.Age)) 'DarkGray'
		return $true
	}
	New-Item -ItemType Directory -Force $serverRunDir | Out-Null
	$props = Join-Path $serverRunDir 'server.properties'
	if (-not (Test-Path $props)) {
		@'
online-mode=false
white-list=false
enforce-whitelist=false
level-name=labmp
level-type=minecraft:flat
gamemode=creative
difficulty=peaceful
spawn-protection=0
server-port=25565
max-players=4
view-distance=6
simulation-distance=6
sync-chunk-writes=false
'@ | Set-Content $props
	}
	Set-Content (Join-Path $serverRunDir 'eula.txt') 'eula=true'
	Write-Stage 'lab server: starting (dedicated, flat world, kept alive by a stdin pipe)'
	$launcher = Join-Path $lab 'start-server.cmd'
	$serverLog = Join-Path $lab 'server.log'
	# A server stops when its stdin closes, and a detached process has no console: feed it a
	# ping so the console reader never sees EOF. That pipe is why the server stays up and why
	# gradle's BUILD SUCCESSFUL line does not mean the process ended.
	Set-Content $launcher "@echo off`r`nset JAVA_HOME=$env:JAVA_HOME`r`ncd /d `"$root`"`r`nping -t 127.0.0.1 | `"$(Join-Path $root 'gradlew.bat')`" runServerLab --console=plain > `"$serverLog`" 2>`"$(Join-Path $lab 'server.err')`"`r`n"
	Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'start', '""', '/b', $launcher) -WorkingDirectory $root -WindowStyle Hidden
	$deadline = (Get-Date).AddSeconds(180)
	$nextReport = (Get-Date).AddSeconds(10)
	while ((Get-Date) -lt $deadline) {
		if (Test-LabServer) {
			$owner = Get-PortOwner -Port $serverPort
			Write-Stage ("lab server ready in {0:N1}s on port {1} (pid {2})" -f $script:stageClock.Elapsed.TotalSeconds, $serverPort, $owner.Pid) 'Green'
			return $true
		}
		if ((Get-Date) -gt $nextReport) {
			Write-Stage ("lab server: still starting after {0:N0}s (log {1})" -f $script:stageClock.Elapsed.TotalSeconds, $serverLog) 'Yellow'
			$nextReport = (Get-Date).AddSeconds(10)
		}
		Start-Sleep -Milliseconds 500
	}
	Write-Stage ("lab server did not open port {0} in 180s; log {1}" -f $serverPort, $serverLog) 'Red'
	return $false
}

function Start-MpClient {
	param([int]$Port = $mpPort, [string]$Config = 'clientMp', [string]$Tag = 'mp')
	if (Test-MpClient -Port $Port) {
		$owner = Get-PortOwner -Port $Port
		Write-Stage ("$Tag client already up on {0} (pid {1}, {2})" -f $Port, $owner.Pid, (Format-Age $owner.Age)) 'DarkGray'
		return $true
	}
	Build-Agent
	$runDir = Join-Path $root "build\run\$Tag"
	New-Item -ItemType Directory -Force $runDir | Out-Null
	$jbr = Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'jbr-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
	$clientHome = if ($jbr) { $jbr.FullName } else { $env:JAVA_HOME }
	if (Test-Port -Port $Port) {
		$stale = Get-PortOwner -Port $Port
		Write-Stage ("port {0} is already held by pid {1} that does not answer this repo. Run modaccel stop, or close that client." -f $Port, $stale.Pid) 'Red'
		return $false
	}
	Write-Stage ("$Tag client: starting on port {0}, will join 127.0.0.1:{1}" -f $Port, $serverPort)
	$task = "run" + $Config.Substring(0, 1).ToUpper() + $Config.Substring(1)
	$launcher = Join-Path $lab "start-$Tag.cmd"
	$clientLog = Join-Path $lab "$Tag.log"
	Set-Content $launcher "@echo off`r`nset JAVA_HOME=$clientHome`r`ncd /d `"$root`"`r`n`"$(Join-Path $root 'gradlew.bat')`" $task --console=plain > `"$clientLog`" 2>`"$(Join-Path $lab "$Tag.err")`"`r`nexit"
	Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'start', '""', '/b', $launcher) -WorkingDirectory $root -WindowStyle Hidden
	$deadline = (Get-Date).AddSeconds(240)
	$nextReport = (Get-Date).AddSeconds(10)
	while ((Get-Date) -lt $deadline) {
		if (Test-MpClient -Port $Port) {
			$owner = Get-PortOwner -Port $Port
			# Hide the window the same way the warm client does, so a check never steals focus.
			if ($owner) {
				for ($i = 0; $i -lt 40; $i++) {
					$win = Get-Process -Id $owner.Pid -ErrorAction SilentlyContinue
					if ($win -and $win.MainWindowHandle -ne 0) { [ModLabWin]::ShowWindow($win.MainWindowHandle, 0) | Out-Null; break }
					Start-Sleep -Milliseconds 250
				}
			}
			Write-Stage ("$Tag client ready in {0:N1}s (pid {1})" -f $script:stageClock.Elapsed.TotalSeconds, $owner.Pid) 'Green'
			return $true
		}
		if ((Get-Date) -gt $nextReport) {
			Write-Stage ("$Tag client: still starting after {0:N0}s (log {1})" -f $script:stageClock.Elapsed.TotalSeconds, $clientLog) 'Yellow'
			$nextReport = (Get-Date).AddSeconds(10)
		}
		Start-Sleep -Milliseconds 500
	}
	Write-Stage ("$Tag client did not open port {0} in 240s; log {1}" -f $Port, $clientLog) 'Red'
	return $false
}

function Stop-Mp {
	Get-CimInstance Win32_Process | Where-Object {
		$_.CommandLine -match 'runServerLab|runClientMp|modlab\.run=mp|ping -t 127\.0\.0\.1'
	} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}


