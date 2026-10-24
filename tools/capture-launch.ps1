#requires -Version 7.0
<#
  capture-launch.ps1 - turn a running dev client into a replayable launch file.

  ForgeGradle (Forge 1.20.1) prints no launch recipe: the whole command line exists only on the
  running process. This copies it into a Java @argfile the harness can replay without Gradle.

  Usage (start the client first, in another window):
    .\gradlew runClient
    .\tools\capture-launch.ps1 -Match forgeclientuserdev -Out .modaccel\forge.args

  The first argument of the match must be the launch target, e.g. forgeclientuserdev, devlaunch.
#>
param(
	[Parameter(Mandatory = $true)][string]$Match,
	[string]$Out = '.modaccel\forge.args',
	[int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$found = $null
while ((Get-Date) -lt $deadline) {
	$found = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
		Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Match) } |
		Select-Object -First 1
	if ($found) { break }
	Start-Sleep -Seconds 2
}

if (-not $found) {
	Write-Host "capture: no java process containing '$Match' within ${TimeoutSeconds}s." -ForegroundColor Red
	Write-Host 'capture: start the client first (for example .\gradlew runClient), then run this again.' -ForegroundColor DarkGray
	exit 1
}

$line = $found.CommandLine
$exeEnd = $line.IndexOf('.exe')
if ($exeEnd -lt 0) { $exeEnd = 0 } else { $exeEnd += 4 }
$args = $line.Substring($exeEnd).Trim()
# Java @argfiles treat a backslash as an escape character, so Windows paths go in doubled.
$escaped = $args -replace '\\', '\\\\'

$outPath = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path (Get-Location) $Out }
New-Item -ItemType Directory -Force (Split-Path $outPath) | Out-Null
Set-Content $outPath $escaped -NoNewline

Write-Host ("capture: wrote {0} ({1:N0} chars) from pid {2}" -f $outPath, $escaped.Length, $found.ProcessId) -ForegroundColor Green
Write-Host 'capture: the harness now launches this client without Gradle; re-capture after a dependency change.' -ForegroundColor DarkGray
