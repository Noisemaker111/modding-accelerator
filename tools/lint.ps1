#requires -Version 7.0
<#
  lint.ps1 - registration lint. Catches the mistakes a 15 second server boot would find
  (or worse, only a player would find) in a fraction of a second.

  Called by lab.ps1 as part of the fast lane. Exit 1 on findings.
#>
param([string]$Root = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))

$ErrorActionPreference = 'Stop'
$problems = New-Object System.Collections.Generic.List[string]

# --- mixin targets resolve against the mapped game jar -----------------------
# A @Shadow/@Inject name that no longer exists compiles, applies and runs: Mixin logs a warning
# and silently skips the member, so a renamed field or method in the game only shows up when
# something reads it in game. This asks Mixin's question directly, against the same jar the build
# compiles against: does a member with that name (and for methods, that descriptor) exist?
# tools\MixinAudit.java is compiled once into .modaccel\mixin and costs about 0.4s per run.
$mixinSummary = $null
$auditSource = Join-Path $PSScriptRoot 'MixinAudit.java'
$auditClasses = Join-Path $Root '.modaccel\mixin\classes'
$auditClass = Join-Path $auditClasses 'MixinAudit.class'
$auditCp = Join-Path $Root '.modaccel\classpath.txt'

$javaExe = $null
$jdk25 = @(Get-ChildItem (Join-Path $Root '.tools') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue |
	Sort-Object Name -Descending)
if ($jdk25.Count -gt 0) { $javaExe = Join-Path $jdk25[0].FullName 'bin\java.exe' }
if (-not $javaExe -and $env:JAVA_HOME) { $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if ($javaExe -and -not (Test-Path $javaExe)) { $javaExe = $null }

if ((Test-Path $auditSource) -and $javaExe) {
	$javacExe = Join-Path (Split-Path $javaExe -Parent) 'javac.exe'
	$stale = -not (Test-Path $auditClass) -or
		((Get-Item $auditSource).LastWriteTimeUtc -gt (Get-Item $auditClass).LastWriteTimeUtc)
	if ($stale -and (Test-Path $javacExe)) {
		New-Item -ItemType Directory -Force $auditClasses | Out-Null
		& $javacExe -d $auditClasses $auditSource 2>&1 | Out-Null
	}
	if (Test-Path $auditClass) {
		$auditArgs = @('-XX:TieredStopAtLevel=1', '-Xshare:auto', '-cp', $auditClasses, 'MixinAudit', '--root', $Root)
		if (Test-Path $auditCp) { $auditArgs += @('--classpath', $auditCp) }
		# A NeoForge node has no loom classpath file and its game classes live in the dev artifact.
		$merged = Get-ChildItem (Join-Path $Root 'build\moddev\artifacts') -Filter '*-merged.jar' -ErrorAction SilentlyContinue |
			Select-Object -First 1
		if ($merged -and -not (Test-Path $auditCp)) { $auditArgs += @('--jar', $merged.FullName) }
		foreach ($line in @(& $javaExe @auditArgs 2>&1)) {
			$text = ([string]$line).Trim()
			if (-not $text) { continue }
			if ($text -match '^# (.+)$') { $mixinSummary = $Matches[1] }
			elseif ($text -match '^note: (.+)$') { Write-Host "lint: not checked - $($Matches[1])" -ForegroundColor DarkGray }
			else { $problems.Add($text) }
		}
	}
	if (-not $mixinSummary) { $mixinSummary = 'audit could not run (no mapped jar or no JDK 25)' }
}

$modJsonPath = Join-Path $Root 'src/main/resources/fabric.mod.json'
$modsToml = @(
	(Join-Path $Root 'src/main/resources/META-INF/mods.toml'),
	(Join-Path $Root 'src/main/resources/META-INF/neoforge.mods.toml')
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not (Test-Path $modJsonPath)) {
	# Forge/NeoForge repos register through mods.toml and their own event bus; this lint's rules
	# (fabric.mod.json entrypoint + assets/data folders) do not apply, so say so instead of failing.
	# The mixin audit above does apply, and its findings still fail the run.
	if ($problems.Count -gt 0) {
		foreach ($p in $problems) { Write-Output "lint: $p" }
		exit 1
	}
	if ($modsToml) {
		Write-Host "lint: Forge/NeoForge repo (mods.toml) - registry lint does not apply; mixins: $mixinSummary"
		exit 0
	}
	Write-Host 'lint: no fabric.mod.json or mods.toml - not a mod repo, skipped'
	exit 0
}
$modJson = Get-Content $modJsonPath -Raw | ConvertFrom-Json
$modId = $modJson.id
$assets = Join-Path $Root "src/main/resources/assets/$modId"
$data = Join-Path $Root "src/main/resources/data/$modId"

# --- collect registered ids from source -------------------------------------

$sources = Get-ChildItem (Join-Path $Root 'src/main/java') -Recurse -Filter *.java -ErrorAction SilentlyContinue
$registrations = @{}
foreach ($file in $sources) {
	$text = Get-Content $file.FullName -Raw
	foreach ($m in [regex]::Matches($text, 'Registry\.register\(\s*BuiltInRegistries\.(\w+),')) {
		$registry = $m.Groups[1].Value
		if (-not $registrations.ContainsKey($registry)) { $registrations[$registry] = @{} }
	}
	foreach ($m in [regex]::Matches($text, 'ResourceKey\.create\(Registries\.(\w+),\s*\w+\.id\("([^"]+)"\)\)')) {
		$registry = $m.Groups[1].Value
		$id = $m.Groups[2].Value
		if (-not $registrations.ContainsKey($registry)) { $registrations[$registry] = @{} }
		if ($registrations[$registry].ContainsKey($id)) {
			$problems.Add("duplicate $registry id '$id' (also in $($registrations[$registry][$id]))")
		}
		else {
			$registrations[$registry][$id] = $file.Name
		}
	}
	if ($text -match 'MOD_ID\s*=\s*"([^"]+)"' -and $Matches[1] -ne $modId) {
		$problems.Add("MOD_ID '$($Matches[1])' does not match fabric.mod.json id '$modId'")
	}
}

# --- entrypoints and mixins exist -------------------------------------------

$javaFiles = @{}
foreach ($file in (Get-ChildItem (Join-Path $Root 'src') -Recurse -Filter *.java)) {
	$javaFiles[$file.BaseName] = $true
}
foreach ($stage in $modJson.entrypoints.PSObject.Properties) {
	foreach ($entry in $stage.Value) {
		$simple = ($entry -split '\.')[-1]
		if (-not $javaFiles.ContainsKey($simple)) { $problems.Add("entrypoint $($stage.Name) points at missing class $entry") }
	}
}
foreach ($mixin in $modJson.mixins) {
	$name = if ($mixin -is [string]) { $mixin } else { $mixin.config }
	$found = Get-ChildItem (Join-Path $Root 'src') -Recurse -Filter $name -File -ErrorAction SilentlyContinue | Select-Object -First 1
	if (-not $found) { $problems.Add("mixin config $name is referenced but missing") }
}

# --- every block and item has the files a player needs -----------------------

function Test-Asset([string]$path, [string]$owner) {
	if (-not (Test-Path $path)) { $problems.Add("$owner has no $([System.IO.Path]::GetFileName($path))") }
}

$lang = @{}
$langFile = Join-Path $assets 'lang/en_us.json'
if (Test-Path $langFile) {
	$lang = Get-Content $langFile -Raw | ConvertFrom-Json | ForEach-Object { $_.PSObject.Properties } | ForEach-Object { @{ $_.Name = $_.Value } } | ForEach-Object { $_ }
}
$langText = if (Test-Path $langFile) { Get-Content $langFile -Raw } else { '' }

if ($registrations.ContainsKey('BLOCK')) {
	foreach ($id in $registrations['BLOCK'].Keys) {
		Test-Asset (Join-Path $assets "blockstates/$id.json") "block $id"
		Test-Asset (Join-Path $data "loot_table/blocks/$id.json") "block $id"
		Test-Asset (Join-Path $assets "models/item/$id.json") "block $id (item model)"
		if ($langText -notmatch [regex]::Escape("block.$modId.$id")) { $problems.Add("block $id has no lang entry block.$modId.$id") }
	}
}
if ($registrations.ContainsKey('ITEM')) {
	foreach ($id in $registrations['ITEM'].Keys) {
		if ($registrations.ContainsKey('BLOCK') -and $registrations['BLOCK'].ContainsKey($id)) { continue }
		Test-Asset (Join-Path $assets "items/$id.json") "item $id"
		if ($langText -notmatch [regex]::Escape("item.$modId.$id")) { $problems.Add("item $id has no lang entry item.$modId.$id") }
	}
}

# --- textures referenced by models exist ------------------------------------

foreach ($model in (Get-ChildItem (Join-Path $assets 'models') -Recurse -Filter *.json -ErrorAction SilentlyContinue)) {
	$json = Get-Content $model.FullName -Raw | ConvertFrom-Json
	if (-not $json.textures) { continue }
	foreach ($prop in $json.textures.PSObject.Properties) {
		$value = $prop.Value
		if ($value -notmatch '^([a-z0-9_.-]+):(.+)$') { continue }
		$ns = $Matches[1]
		$path = $Matches[2]
		if ($path.StartsWith('#')) { continue }
		$nsRoot = Join-Path $Root "src/main/resources/assets/$ns"
		if (-not (Test-Path $nsRoot)) { continue }
		if (-not (Test-Path (Join-Path $nsRoot "textures/$path.png"))) {
			$problems.Add("model $($model.Name) points at missing texture $ns`:$path")
		}
	}
}

if ($problems.Count -gt 0) {
	foreach ($p in $problems) { Write-Output "lint: $p" }
	exit 1
}
$registered = ($registrations.Values | ForEach-Object { $_.Count } | Measure-Object -Sum).Sum
Write-Output "lint: $registered registrations, all files present$(if ($mixinSummary) { "; mixins: $mixinSummary" })"
exit 0



