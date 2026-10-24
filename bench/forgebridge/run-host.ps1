# Start a Forge 1.20.1 dev client from a captured launch line, with this bridge
# added as a jar in the host's mods folder. Nothing in the host is edited.
#
#   .\run-host.ps1                      # default: bench\mods\minecolonies + forge-launch.txt
#   .\run-host.ps1 -HostDir <repo> -LaunchFile <txt> -Port 27699
#
# Prints the client PID and the seconds until MODLABBRIDGE-READY, then returns.
# Stop it with: .\ask.ps1 quit
param(
    [string]$HostDir = 'C:\Users\Jk101\Projects\modding-accelerator\bench\mods\minecolonies',
    [string]$LaunchFile = 'C:\Users\Jk101\Projects\modding-accelerator\bench\mods\forge-launch.txt',
    [string]$Jar = (Join-Path $PSScriptRoot 'build\libs\modlabbridge-forge-1.0.0.jar'),
    [int]$Port = 27699,
    [string]$Shots = (Join-Path $PSScriptRoot 'shots'),
    [string]$Size = '',
    [int]$ReadySeconds = 240
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Jar)) { throw "no jar at $Jar - run .\build.ps1 first" }
$hostMods = Join-Path $HostDir 'mods'
New-Item -ItemType Directory -Force -Path $hostMods, $Shots | Out-Null
Copy-Item -LiteralPath $Jar -Destination $hostMods -Force

# The java line in the capture is the one that starts with a drive path.
$line = Get-Content -LiteralPath $LaunchFile | Where-Object { $_ -match '^[A-Za-z]:\\.*java\.exe ' } | Select-Object -First 1
if (-not $line) { throw "no java.exe launch line in $LaunchFile" }

$cut = $line.IndexOf('.exe ') + 4
$java = $line.Substring(0, $cut)
$rest = $line.Substring($cut)
$props = "-Dmodlabbridge.port=$Port -Dmodlabbridge.shots=$Shots -Dmodlab.root=$HostDir"
if ($Size) { $props = "$props -Dmodlabbridge.size=$Size" }
$commandLine = "$java $props $rest"

$log = Join-Path $PSScriptRoot 'client.log'
$err = Join-Path $PSScriptRoot 'client.err'
Remove-Item -LiteralPath $log, $err -ErrorAction SilentlyContinue
$started = Get-Date
$proc = Start-Process -FilePath $java -ArgumentList $commandLine.Substring($java.Length + 1) `
    -WorkingDirectory $HostDir -RedirectStandardOutput $log -RedirectStandardError $err `
    -PassThru -NoNewWindow
$proc.Id | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'client.pid')
"started pid=$($proc.Id) log=$log"

$deadline = $started.AddSeconds($ReadySeconds)
while ((Get-Date) -lt $deadline) {
    $text = if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Raw } else { '' }
    if ($text -match 'MODLABBRIDGE-READY') {
        "{0:N1}s to READY on port {1}" -f ((Get-Date) - $started).TotalSeconds, $Port
        exit 0
    }
    if ($proc.HasExited) { throw "client exited with $($proc.ExitCode) before READY - see $err" }
    Start-Sleep -Milliseconds 500
}
throw "no MODLABBRIDGE-READY within $ReadySeconds s - see $log"
