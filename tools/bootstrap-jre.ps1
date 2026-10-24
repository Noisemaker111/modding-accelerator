# Downloads a Java runtime once when no Java is installed, then the launcher reuses it.
# Windows x64 builds from Adoptium, verified against the published SHA-256 before use.
# A packaged modaccel.jar needs a JRE; a source checkout needs a JDK (pass "jdk").
$ErrorActionPreference = 'Stop'
$image = if ($args.Count -gt 0 -and $args[0] -eq 'jdk') { 'jdk' } else { 'jre' }
$version = '21'
$root = Join-Path $env:LOCALAPPDATA "modaccel\$image"
$current = Join-Path $root 'current'
if (Test-Path (Join-Path $current 'bin\java.exe')) { exit 0 }
New-Item -ItemType Directory -Force -Path $root | Out-Null
$api = "https://api.adoptium.net/v3/assets/latest/$version/hotspot?architecture=x64&image_type=$image&os=windows&vendor=eclipse"
$asset = Invoke-RestMethod -Uri $api -TimeoutSec 60
$package = $asset[0].binary.package
if (-not $package.link) { throw "Adoptium published no Windows x64 Java $image" }
$megabytes = [math]::Round($package.size / 1MB)
Write-Output "modaccel: Java was not found; downloading a Java $version $image ($megabytes MB) once into $root"
$zip = Join-Path $root 'runtime.zip'
Invoke-WebRequest -Uri $package.link -OutFile $zip -TimeoutSec 900 -UseBasicParsing
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $stream = [System.IO.File]::OpenRead($zip)
    try { $hash = ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose() }
} finally { $sha.Dispose() }
if ($hash -ne $package.checksum.ToLowerInvariant()) {
    Remove-Item $zip -Force
    throw 'Downloaded Java runtime failed its SHA-256 check'
}
$extract = Join-Path $root 'extract'
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $extract)
$inner = Get-ChildItem $extract -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
if (-not $inner) { throw 'Downloaded Java runtime has no bin\java.exe' }
if (Test-Path $current) { Remove-Item $current -Recurse -Force }
Move-Item $inner.FullName $current
Remove-Item $zip -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
if (-not (Test-Path (Join-Path $current 'bin\java.exe'))) { throw 'Java runtime extraction did not produce bin\java.exe' }
Write-Output "modaccel: Java $version $image ready at $current"
