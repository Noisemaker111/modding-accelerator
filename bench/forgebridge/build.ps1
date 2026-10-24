# Build bench/forgebridge with no Gradle and no network: javac against the
# already-mapped Forge 1.20.1 jar of a dev run that has been built once, then jar.
#
#   .\build.ps1                       # default: the minecolonies dev run and its JDK 17
#   .\build.ps1 -ClasspathFile <txt>  # any other run's build\classpath\runClient_minecraftClasspath.txt
param(
    [string]$ClasspathFile = 'C:\Users\Jk101\Projects\modding-accelerator\bench\mods\minecolonies\build\classpath\runClient_minecraftClasspath.txt',
    [string]$Jdk = 'C:\Users\Jk101\Projects\modding-accelerator\bench\mods\jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = '1.0.0'

if (-not (Test-Path -LiteralPath $ClasspathFile)) {
    throw "no classpath file at $ClasspathFile - build the host dev run once (gradlew classes) first"
}
if (-not (Test-Path -LiteralPath "$Jdk\bin\javac.exe")) {
    throw "no javac at $Jdk"
}

# The dev run's classpath file is one path per line; javac wants ';' between them.
$cp = (Get-Content -LiteralPath $ClasspathFile | Where-Object { $_.Trim() }) -join ';'
$classes = Join-Path $here 'build\classes'
$libs = Join-Path $here 'build\libs'
Remove-Item -Recurse -Force $classes, $libs -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $here 'src\main\java') | ForEach-Object FullName)
& "$Jdk\bin\javac.exe" -nowarn -proc:none --release 17 -cp $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

Copy-Item -Recurse -Force (Join-Path $here 'src\main\resources\*') $classes
$jar = Join-Path $libs "modlabbridge-forge-$version.jar"
& "$Jdk\bin\jar.exe" --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }

$item = Get-Item -LiteralPath $jar
"built $($item.FullName) ($($item.Length) bytes)"
