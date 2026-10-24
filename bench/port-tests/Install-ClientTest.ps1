# Install the reusable AppleSkin client test into a disposable port fixture.
param([Parameter(Mandatory)][string]$Repo)
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path -LiteralPath $Repo).Path
$build = Join-Path $Repo 'build.gradle'
$javaDir = Join-Path $Repo 'src/gametest/java/squeek/appleskin/client'
$resources = Join-Path $Repo 'src/gametest/resources'
if (-not (Test-Path (Join-Path $Repo 'java/squeek/appleskin/AppleSkin.java'))) {
	throw 'This test requires an AppleSkin port fixture.'
}
$text = [IO.File]::ReadAllText($build)
$marker = '// Port verification: isolated Fabric client gametest, not part of the shipped mod.'
if (-not $text.Contains($marker)) {
	if ($text -match '\bconfigureTests\b') { throw 'This fixture already owns a test configuration; wire PortClientTest into it.' }
	$block = @'

// Port verification: isolated Fabric client gametest, not part of the shipped mod.
fabricApi {
 configureTests {
  createSourceSet = true
  modId = "appleskin-porttest"
  enableGameTests = false
  enableClientGameTests = true
  eula = true
 }
}
'@
	[IO.File]::WriteAllText($build, $text + "`n" + $block + "`n")
}
New-Item -ItemType Directory -Force $javaDir, $resources | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PortClientTest.java') -Destination $javaDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fabric.mod.json') -Destination $resources
Write-Host 'Installed client test. Run modaccel port -Fix -Log <original failing log> -Task runClientGameTest.'
