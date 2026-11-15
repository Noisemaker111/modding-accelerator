# Windows only: the legacy launch agent must leave system properties intact and let Gradle run (tools/ci/lanes.py core).
$ErrorActionPreference = 'Stop'
$prepared = & java -jar build/distributions/modaccel.jar legacy-agent --json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $prepared.ok) { throw 'Could not build the legacy launch agent' }
$env:JAVA_TOOL_OPTIONS = '"-javaagent:' + $prepared.result.agent + '=legacy" -Dmodaccel.background=true -Dmodaccel.quiet=true'
& java tools/tests/LegacyAgentPropertiesTest.java
if ($LASTEXITCODE -ne 0) { throw 'Legacy launch agent corrupted system properties' }
& java -jar build/distributions/modaccel.jar fast --refresh --json
if ($LASTEXITCODE -ne 0) { throw 'Gradle failed with the legacy launch agent enabled' }
