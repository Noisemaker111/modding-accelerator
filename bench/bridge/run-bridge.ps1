# Starts a NeoForge 1.21.1 client that belongs to the node's own mod (prismlab)
# with this bridge added through -Dfml.modFolders. The node's sources are never
# edited: the bridge is a jar on the FML mod folder list and nothing else.
#
# The node's ModDevGradle launch recipe (build/moddev/clientWarmRun*Args.txt plus
# the captured classpath) is replayed directly, so this is a JVM start, not a build.
param(
	[string]$Jar = (Join-Path $PSScriptRoot 'build\libs\modlabbridge-1.0.0.jar'),
	[int]$Port = 27699,
	[string]$Node = (Join-Path $PSScriptRoot '..\..\versions\neoforge-1.21.1'),
	[string]$GameDir = (Join-Path $PSScriptRoot 'run'),
	[string]$Shots = (Join-Path $PSScriptRoot 'shots'),
	[switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$Jar = (Resolve-Path $Jar).Path
$Node = (Resolve-Path $Node).Path
$cpArgs = Join-Path $Node '.modaccel\neoforge-cp.args'
$vmArgs = Join-Path $Node 'build\moddev\clientWarmRunVmArgs.txt'
$progArgs = Join-Path $Node 'build\moddev\clientWarmRunProgramArgs.txt'
foreach ($file in @($cpArgs, $vmArgs, $progArgs)) {
	if (-not (Test-Path $file)) { throw "missing launch recipe file: $file" }
}

# The game JVM is the Java 21 ModDevGradle provisioned, not this shell's JDK.
$neoArtifact = Get-ChildItem (Join-Path $Node 'build\moddev\artifacts') -Filter 'neoforge-*.jar' -ErrorAction SilentlyContinue |
	Where-Object { $_.Name -match '^neoforge-\d+\.' } | Select-Object -First 1
$neoVersion = if ($neoArtifact) { [regex]::Match($neoArtifact.Name, '^neoforge-(\d+)\.').Groups[1].Value } else { '21' }
$toolchain = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue |
	Where-Object { $_.Name -match "(^|[-_.])$neoVersion([-_.]|$)" -and (Test-Path (Join-Path $_.FullName 'bin\java.exe')) } |
	Sort-Object Name -Descending | Select-Object -First 1
if (-not $toolchain) { throw "no Java $neoVersion toolchain under $env:USERPROFILE\.gradle\jdks" }
$java = Join-Path $toolchain.FullName 'bin\java.exe'

$modClasses = Join-Path $Node 'build\classes\java\main'
$modResources = Join-Path $Node 'build\resources\main'
# modid%%path, ';' between entries. The host mod keeps its own entries; the bridge
# gets one more. prismlab.lab.port is blanked so the only control socket in this
# client is the bridge's.
$modFolders = "prismlab%%$modClasses;prismlab%%$modResources;modlabbridge%%$Jar"

New-Item -ItemType Directory -Force $GameDir, $Shots | Out-Null
$log = Join-Path $PSScriptRoot 'client.log'
$err = Join-Path $PSScriptRoot 'client.err'

$arguments = @(
	"@$vmArgs",
	"@$cpArgs",
	'-Dfile.encoding=UTF-8', '-Duser.country=US', '-Duser.language=en', '-Duser.variant',
	'-Dprismlab.lab.port=',
	"-Dmodlabbridge.port=$Port",
	"-Dmodlabbridge.shots=$Shots",
	"-Dmodlab.root=$Node",
	"-Dfml.modFolders=$modFolders",
	'net.neoforged.devlaunch.Main', "@$progArgs"
)

$proc = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $GameDir `
	-RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Minimized
$proc.Id | Set-Content (Join-Path $PSScriptRoot 'client.pid') -NoNewline
if (-not $Quiet) { Write-Host "started pid $($proc.Id), waiting for port $Port" -ForegroundColor DarkGray }

$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
	$tcp = New-Object System.Net.Sockets.TcpClient
	try {
		# The listener binds the loopback address the JVM picks (::1 here, as LabControl does),
		# so connect by name rather than hardcoding 127.0.0.1.
		$tcp.Connect('localhost', $Port)
		if ($tcp.Connected) { $tcp.Close(); Write-Host "bridge answering on $Port"; return }
	}
	catch { }
	finally { $tcp.Dispose() }
	if ($proc.HasExited) { throw "client exited (code $($proc.ExitCode)); see $log / $err" }
	Start-Sleep -Milliseconds 500
}
throw "bridge did not answer on $Port within 4 minutes; see $log / $err"
