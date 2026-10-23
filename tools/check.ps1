# Toolchain gate: can this repo build at the target version at all?
# Answers before any code work: which JDK the wrapper needs, which loader this repo is, the
# version pins, and whether the target Minecraft / loader / API artifacts exist.

function Get-BuildText {
	param([string]$Repo)
	$files = @(Get-ChildItem $Repo -Recurse -Depth 2 -Include 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'stonecutter.gradle.kts', 'gradle.properties', '*.gradle' -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -notmatch '\\\.gradle\\|\\build\\|\\versions\\' })
	($files | ForEach-Object { Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue }) -join "`n"
}

function Get-WrapperVersion {
	param([string]$Repo)
	$props = Join-Path $Repo 'gradle\wrapper\gradle-wrapper.properties'
	if (-not (Test-Path $props)) { return $null }
	$match = [regex]::Match((Get-Content $props -Raw), 'gradle-(\d+)\.(\d+)(?:\.(\d+))?-(?:bin|all)')
	if (-not $match.Success) { return $null }
	[pscustomobject]@{
		Major = [int]$match.Groups[1].Value
		Minor = [int]$match.Groups[2].Value
		Patch = if ($match.Groups[3].Success) { [int]$match.Groups[3].Value } else { 0 }
		Text  = '{0}.{1}.{2}' -f $match.Groups[1].Value, $match.Groups[2].Value, $(if ($match.Groups[3].Success) { $match.Groups[3].Value } else { '0' })
	}
}

function Get-Loader {
	param([string]$Repo, [string]$BuildText)
	if ($BuildText -match 'architectury') { return 'architectury (fabric + neoforge)' }
	$fabricJson = Get-ChildItem $Repo -Recurse -Depth 6 -Filter 'fabric.mod.json' -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle\\' } | Select-Object -First 1
	if ($fabricJson) { return 'fabric' }
	$neo = Get-ChildItem $Repo -Recurse -Depth 4 -Filter 'neoforge.mods.toml' -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($neo) { return 'neoforge' }
	if ($BuildText -match 'net\.neoforged\.(moddev|gradle)|neogradle') { return 'neoforge' }
	if ($BuildText -match 'net\.minecraftforge|forgegradle|\bfg\.') { return 'forge' }
	if ($BuildText -match 'fabric-loom') { return 'fabric' }
	$forge = Get-ChildItem $Repo -Recurse -Depth 4 -Filter 'mods.toml' -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($forge) { return 'forge' }
	return 'unknown'
}

function Get-VersionPins {
	param([string]$Repo, [string]$BuildText)
	$pins = @{}
	$rootProps = Join-Path $Repo 'gradle.properties'
	if (Test-Path $rootProps) {
		foreach ($line in Get-Content $rootProps) {
			if ($line -match '^\s*([\w.-]+)\s*=\s*(.+?)\s*$') { $pins[$matches[1]] = $matches[2] }
		}
	}
	# Stonecutter keeps the real versions in the active node.
	$stonecutter = Join-Path $Repo 'stonecutter.gradle.kts'
	if (Test-Path $stonecutter) {
		$active = [regex]::Match((Get-Content $stonecutter -Raw), 'active\s+"([^"]+)"').Groups[1].Value
		if ($active) {
			$nodeProps = Join-Path $Repo "versions\$active\gradle.properties"
			if (Test-Path $nodeProps) {
				foreach ($line in Get-Content $nodeProps) {
					if ($line -match '^\s*([\w.-]+)\s*=\s*(.+?)\s*$') { $pins[$matches[1]] = $matches[2] }
				}
			}
			$pins['stonecutter.active'] = $active
		}
	}
	# ForgeGradle / NeoGradle repos often keep the Minecraft version in a build script instead.
	if (-not ($pins['minecraft_version'] -or $pins['minecraftVersion'] -or $pins['exactMinecraftVersion'])) {
		$found = [regex]::Match($BuildText, '(?:exactMinecraftVersion|minecraftVersion|minecraft_version)\s*[=:]\s*[''"]?(\d+\.\d+(?:\.\d+)?)')
		if ($found.Success) { $pins['minecraft_version'] = $found.Groups[1].Value }
	}
	$pins
}

function Get-JdkForGradle {
	param($Wrapper)
	if (-not $Wrapper) { return 25 }
	# Gradle 8.x cannot load class file major 69 (JDK 25); 8.5-8.14 run on 21, older 8.x on 17.
	if ($Wrapper.Major -ge 9) { return 25 }
	if ($Wrapper.Major -eq 8 -and $Wrapper.Minor -ge 5) { return 21 }
	return 17
}

function Find-Jdk {
	param([int]$Version)
	$roots = @(
		(Join-Path $root '.tools'),
		(Join-Path $env:USERPROFILE 'tools'),
		(Join-Path $root 'bench\mods')
	)
	foreach ($base in $roots) {
		if (-not (Test-Path $base)) { continue }
		$found = Get-ChildItem $base -Directory -Filter "jdk-$Version*" -ErrorAction SilentlyContinue |
			Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
			Sort-Object Name -Descending | Select-Object -First 1
		if ($found) { return $found.FullName }
	}
	return $null
}

function Test-FabricTarget {
	param([string]$Minecraft)
	try {
		$loaders = $null
		try {
			$loaders = Invoke-RestMethod "https://meta.fabricmc.net/v2/versions/loader/$Minecraft" -TimeoutSec 20
		}
		catch {
			# A 404 here means the version is not in Fabric's meta (a snapshot codename or a custom
			# version like this project's 26.3), not that the repo is broken.
			if ($_.Exception.Response.StatusCode.value__ -eq 404) {
				return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft is not in Fabric meta (snapshot or custom version); loaders cannot be confirmed" }
			}
			throw
		}
		if (-not $loaders -or @($loaders).Count -eq 0) {
			return [pscustomobject]@{ Blocker = "target $Minecraft : no Fabric loader build exists"; Warning = $null }
		}
		try {
			$api = Invoke-RestMethod ("https://api.modrinth.com/v2/version?project_id=fabric-api&game_versions=%5B%22{0}%22%5D&loaders=%5B%22fabric%22%5D" -f $Minecraft) -TimeoutSec 20
			if (-not $api -or @($api).Count -eq 0) {
				return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft : Fabric API has no published build (fine if the repo pins one)" }
			}
		}
		catch {
			return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft : Modrinth check failed, Fabric API unconfirmed" }
		}
		return [pscustomobject]@{ Blocker = $null; Warning = $null }
	}
	catch { return [pscustomobject]@{ Blocker = $null; Warning = "target check failed: $($_.Exception.Message)" } }
}

function Test-GradleConfigures {
	# The only trustworthy answer: run Gradle's own configuration with the JDK we picked.
	# `help` resolves the wrapper, the plugins and the settings, then stops before compiling.
	param([string]$Repo, [string]$Jdk, [int]$TimeoutSeconds = 240)
	$launcher = Join-Path $Repo 'gradlew.bat'
	if (-not (Test-Path $launcher)) { return [pscustomobject]@{ Ran = $false; Ok = $false; Seconds = 0; Reason = 'no gradlew.bat' } }
	$logDir = Join-Path $Repo '.modaccel'
	New-Item -ItemType Directory -Force -Path $logDir | Out-Null
	$log = Join-Path $logDir 'toolchain-check.log'
	$started = Get-Date
	$oldHome = $env:JAVA_HOME
	$env:JAVA_HOME = $Jdk
	Push-Location $Repo
	try {
		$proc = Start-Process -FilePath $launcher -ArgumentList 'help', '--console=plain' -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
		if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
			try { $proc.Kill($true) } catch { }
			Pop-Location
			return [pscustomobject]@{ Ran = $true; Ok = $false; Seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1); Reason = "Gradle did not finish configuration in ${TimeoutSeconds}s" }
		}
		$code = $proc.ExitCode
	}
	finally {
		Pop-Location
		$env:JAVA_HOME = $oldHome
	}
	$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
	if ($code -eq 0) { return [pscustomobject]@{ Ran = $true; Ok = $true; Seconds = $seconds; Reason = '' } }
	$text = @(Get-Content $log -ErrorAction SilentlyContinue)
	if (Test-Path "$log.err") { $text += @(Get-Content "$log.err" -ErrorAction SilentlyContinue) }
	$reason = 'Gradle failed while configuring (see .modaccel\toolchain-check.log)'
	$index = -1
	for ($i = 0; $i -lt $text.Count; $i++) { if ($text[$i] -match 'What went wrong') { $index = $i; break } }
	if ($index -ge 0) {
		$quoted = @($text[($index + 1)..([math]::Min($index + 8, $text.Count - 1))] | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
		$reason = ($quoted | Select-Object -First 3) -join ' / '
	}
	return [pscustomobject]@{ Ran = $true; Ok = $false; Seconds = $seconds; Reason = $reason }
}

function Test-GradleDependencies {
	# Configuration succeeding does not mean the mods resolve. Mavens go down (measured: TerraformersMC
	# answered 521 and three repos died on it), so ask Gradle to resolve the mod configuration too.
	param([string]$Repo, [string]$Jdk, [int]$TimeoutSeconds = 240, [string]$Prefix = '')
	$launcher = Join-Path $Repo 'gradlew.bat'
	if (-not (Test-Path $launcher)) { return [pscustomobject]@{ Ran = $false; Ok = $false; Reason = 'no gradlew.bat' } }
	$logDir = Join-Path $Repo '.modaccel'
	New-Item -ItemType Directory -Force -Path $logDir | Out-Null
	$log = Join-Path $logDir 'toolchain-dependencies.log'
	$oldHome = $env:JAVA_HOME
	$env:JAVA_HOME = $Jdk
	Push-Location $Repo
	try {
		$proc = Start-Process -FilePath $launcher -ArgumentList 'dependencies', '--configuration', "${Prefix}modImplementation", '--console=plain' -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
		if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
			try { $proc.Kill($true) } catch { }
			Pop-Location
			return [pscustomobject]@{ Ran = $true; Ok = $false; Reason = "dependency resolution did not finish in ${TimeoutSeconds}s" }
		}
		$code = $proc.ExitCode
	}
	finally {
		Pop-Location
		$env:JAVA_HOME = $oldHome
	}
	$text = @(Get-Content $log -ErrorAction SilentlyContinue)
	if (Test-Path "$log.err") { $text += @(Get-Content "$log.err" -ErrorAction SilentlyContinue) }
	if ($text -match "configuration '\S*modImplementation' not found|configuration .* not found in configuration container") {
		return [pscustomobject]@{ Ran = $false; Ok = $true; Reason = 'no modImplementation configuration here; dependency proof skipped' }
	}
	$failed = @($text | Select-String -Pattern 'FAILED|Could not resolve ' | Select-Object -First 6 | ForEach-Object { $_.Line.Trim() })
	if ($code -ne 0 -or $failed.Count -gt 0) {
		$reason = if ($failed.Count -gt 0) { ($failed | Select-Object -First 2) -join ' / ' } else { 'dependencies task failed; see .modaccel\toolchain-dependencies.log' }
		return [pscustomobject]@{ Ran = $true; Ok = $false; Reason = $reason }
	}
	return [pscustomobject]@{ Ran = $true; Ok = $true; Reason = '' }
}

function Test-NeoForgeTarget {
	# NeoForge versions follow the game: Minecraft 1.21.4 -> NeoForge 21.4.x. Ask their maven.
	param([string]$Minecraft)
	if ($Minecraft -notmatch '^1\.(\d+)(?:\.(\d+))?$') {
		return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft is not a release version; NeoForge version unconfirmed" }
	}
	$prefix = '{0}.{1}.' -f $matches[1], $(if ($matches[2]) { $matches[2] } else { '0' })
	try {
		[xml]$meta = (Invoke-WebRequest 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml' -UseBasicParsing -TimeoutSec 25).Content
		$versions = @($meta.metadata.versioning.versions.version)
		$match = $versions | Where-Object { $_ -like "$prefix*" } | Select-Object -Last 1
		if (-not $match) {
			return [pscustomobject]@{ Blocker = "target $Minecraft : no NeoForge build for it (looked for $prefix*)"; Warning = $null }
		}
		return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft : NeoForge $match exists" }
	}
	catch {
		return [pscustomobject]@{ Blocker = $null; Warning = "target $Minecraft : NeoForge maven unreachable, version unconfirmed" }
	}
}

function Get-JavaSourceText {
	# Java source with comments and literals blanked, so a class name inside a log string or a
	# TODO does not count as a reference.
	param([string]$Path)
	$text = Get-Content $Path -Raw -ErrorAction SilentlyContinue
	if (-not $text) { return '' }
	$text = [regex]::Replace($text, '/\*.*?\*/', ' ', 'Singleline')
	$text = [regex]::Replace($text, '//[^\n]*', ' ')
	$text = [regex]::Replace($text, '"""(?:.|\n)*?"""', '""', 'Singleline')
	$text = [regex]::Replace($text, '"(?:\\.|[^"\\\n])*"', '""')
	$text = [regex]::Replace($text, "'(?:\\.|[^'\\\n])*'", "''")
	$text
}

function Get-TargetClassIndex {
	# "Does this import still exist at the target?" is answered by the target jar, not by a list of
	# deletions: a class that was renamed is absent from the jar and absent from classesRemoved, so
	# only the index can see it. fabric-loom\<version>\minecraft-merged.jar is the obfuscated
	# download (a.class), so only a jar holding a known named class is used; loom writes those under
	# minecraftMaven. Costs about 3s on a cold scan and nothing on a warm one.
	param([string]$Repo, [string]$Version)
	$roots = @(
		(Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'),
		(Join-Path $Repo '.gradle\loom-cache')
	)
	$jars = @(foreach ($base in $roots) {
		if (-not (Test-Path $base)) { continue }
		Get-ChildItem $base -Recurse -Filter 'minecraft-merged*.jar' -ErrorAction SilentlyContinue |
			Where-Object {
				$_.FullName -notmatch 'intermediary|-sources' -and
				($_.FullName -match "\\$([regex]::Escape($Version))\\" -or $_.Name -like "minecraft-merged-$Version*")
			}
	})
	if ($jars.Count -eq 0) { return $null }
	Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
	foreach ($jar in $jars) {
		try { $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName) } catch { continue }
		try { $entries = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
		if ($entries -notcontains 'net/minecraft/world/item/Item.class') { continue }
		$classes = New-Object 'System.Collections.Generic.HashSet[string]'
		$packages = New-Object 'System.Collections.Generic.HashSet[string]'
		$roots = New-Object 'System.Collections.Generic.HashSet[string]'
		# Ordinal on purpose: `COD` (an EntityType constant) is not `Cod` (a class).
		$simples = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
		foreach ($entry in $entries) {
			if (-not $entry.EndsWith('.class')) { continue }
			$dot = ($entry.Substring(0, $entry.Length - 6)) -replace '/', '.'
			[void]$classes.Add($dot)
			# Source writes Outer.Inner; the jar stores Outer$Inner. Both are the same class.
			if ($dot.Contains('$')) { [void]$classes.Add($dot.Replace('$', '.')) }
			$cut = $dot.LastIndexOf('.')
			$simpleName = if ($cut -ge 0) { $dot.Substring($cut + 1) } else { $dot }
			if (-not $simples.ContainsKey($simpleName)) { $simples[$simpleName] = New-Object 'System.Collections.Generic.HashSet[string]' }
			[void]$simples[$simpleName].Add($dot)
			if ($cut -gt 0) { [void]$packages.Add($dot.Substring(0, $cut)) }
		}
		# The jar holds Minecraft and blaze3d, not every compile dependency (DataFixerUpper is a
		# separate library), so a namespace the jar does not contain cannot be judged from it.
		foreach ($package in $packages) {
			$parts = $package -split '\.'
			[void]$roots.Add((($parts | Select-Object -First ([math]::Min(3, $parts.Count))) -join '.'))
		}
		return [pscustomobject]@{ Jar = $jar.Name; JarPath = $jar.FullName; Classes = $classes; Packages = $packages; Simples = $simples; Roots = $roots }
	}
	return $null
}

function Get-ApiClassLinks {
	# from -> to for classes that left their old name. The per-version diff only knows what it saw
	# between its own two jars, so a three-version jump loses renames that happened in between:
	# version-changes.json holds those (UseAnim -> ItemUseAnimation was renamed in 1.21.4 and is
	# invisible to a 1.21.4 -> 1.21.5 diff). A link is only trusted when the target jar really has
	# the new class, which is checked at use time.
	param($Index, [string]$Knowledge)
	$links = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
	foreach ($entry in @($Index.classesMoved)) {
		if (-not $entry.to) { continue }
		$links[(($entry.class -replace '/', '.') -replace '\$', '.')] = [pscustomobject]@{
			To = (($entry.to -replace '/', '.') -replace '\$', '.'); Evidence = "moved ($($entry.match))"
		}
	}
	foreach ($entry in @($Index.replacements)) {
		if ($entry.kind -ne 'class') { continue }
		$links[(($entry.from -replace '/', '.') -replace '\$', '.')] = [pscustomobject]@{
			To = (($entry.to -replace '/', '.') -replace '\$', '.'); Evidence = $entry.evidence
		}
	}
	$file = Join-Path $Knowledge 'version-changes.json'
	if (Test-Path $file) {
		foreach ($entry in @(Get-Content $file -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json)) {
			# Only a clean class-to-class pair is a rename; the prose entries ("A / B", "(x) -> y")
			# are advice for a human, not a mapping.
			$from = "$($entry.from)".Trim()
			$to = "$($entry.to)".Trim()
			if ($from -notmatch '^(?:[a-z][\w$]*\.)+[A-Z][\w$]*$' -or $to -notmatch '^(?:[a-z][\w$]*\.)+[A-Z][\w$]*$') { continue }
			if ($links.ContainsKey($from)) { continue }
			$links[$from] = [pscustomobject]@{ To = $to; Evidence = "version-changes $($entry.jump)" }
		}
	}
	$links
}

function Get-ClassFactsFromBytes {
	# The declared method and field names of one class file, and its superclass. The tables are read
	# rather than the strings: a class's constant pool also holds every name it *calls*, so
	# MobEffects would look like it still had MOVEMENT_SPEED (it named EntityAttributes.MOVEMENT_SPEED),
	# and a byte search for `drop` would match `dropAll`.
	param([byte[]]$Bytes)
	$count = ([int]$Bytes[8] -shl 8) -bor $Bytes[9]
	$utf8 = @{}
	$classRef = @{}
	$at = 10
	for ($n = 1; $n -lt $count; $n++) {
		$tag = $Bytes[$at]
		if ($tag -eq 1) {
			$len = ([int]$Bytes[$at + 1] -shl 8) -bor $Bytes[$at + 2]
			$utf8[$n] = [System.Text.Encoding]::UTF8.GetString($Bytes, $at + 3, $len)
			$at += 3 + $len
		}
		elseif ($tag -eq 7) { $classRef[$n] = ([int]$Bytes[$at + 1] -shl 8) -bor $Bytes[$at + 2]; $at += 3 }
		elseif ($tag -eq 8 -or $tag -eq 16 -or $tag -eq 19 -or $tag -eq 20) { $at += 3 }
		elseif ($tag -eq 15) { $at += 4 }
		elseif ($tag -eq 5 -or $tag -eq 6) { $at += 9; $n++ }
		elseif ($tag -ge 3 -and $tag -le 18) { $at += 5 }
		else { return $null }
	}
	# access_flags, this_class, super_class
	$super = $null
	$superIdx = ([int]$Bytes[$at + 4] -shl 8) -bor $Bytes[$at + 5]
	if ($superIdx -gt 0 -and $classRef.ContainsKey($superIdx)) {
		$nameIdx = $classRef[$superIdx]
		if ($utf8.ContainsKey($nameIdx)) { $super = $utf8[$nameIdx] }
	}
	$at += 6
	$interfaces = ([int]$Bytes[$at] -shl 8) -bor $Bytes[$at + 1]
	$at += 2 + 2 * $interfaces
	$methods = New-Object 'System.Collections.Generic.HashSet[string]'
	$fields = New-Object 'System.Collections.Generic.HashSet[string]'
	foreach ($table in @('fields', 'methods')) {
		$size = ([int]$Bytes[$at] -shl 8) -bor $Bytes[$at + 1]
		$at += 2
		for ($n = 0; $n -lt $size; $n++) {
			$nameIdx = ([int]$Bytes[$at + 2] -shl 8) -bor $Bytes[$at + 3]
			if ($utf8.ContainsKey($nameIdx)) {
				if ($table -eq 'methods') { [void]$methods.Add($utf8[$nameIdx]) } else { [void]$fields.Add($utf8[$nameIdx]) }
			}
			$attrs = ([int]$Bytes[$at + 6] -shl 8) -bor $Bytes[$at + 7]
			$at += 8
			for ($a = 0; $a -lt $attrs; $a++) {
				# attribute_length is four bytes, most of them zero for Code, but reading only two
				# misaligns every following member.
				$len = ([int]$Bytes[$at + 2] -shl 24) -bor ([int]$Bytes[$at + 3] -shl 16) -bor ([int]$Bytes[$at + 4] -shl 8) -bor $Bytes[$at + 5]
				$at += 6 + $len
			}
		}
	}
	[pscustomobject]@{ Methods = $methods; Fields = $fields; Super = $super }
}

function Test-TargetMemberGone {
	# True when neither the class nor anything above it declares this name at the target. An override
	# that was deleted (Player.stopFallFlying, still on LivingEntity) is not a break, and the walk is
	# the only way to know that without a symbol index.
	param([string]$Owner, [string]$Member, $Zip, $Cache)
	$at = $Owner
	for ($hop = 0; $hop -lt 12 -and $at; $hop++) {
		$entryName = $at -replace '\.', '/'
		$entry = $Zip.GetEntry("$entryName.class")
		if (-not $entry) {
			# Source writes Outer.Inner; the jar stores Outer$Inner.
			$slash = $entryName.LastIndexOf('/')
			$dot = $entryName.LastIndexOf('.')
			if ($dot -gt $slash) {
				$entryName = $entryName.Substring(0, $dot) + '$' + $entryName.Substring($dot + 1)
				$entry = $Zip.GetEntry("$entryName.class")
			}
		}
		if (-not $entry) { return $true }
		if (-not $Cache.ContainsKey($entryName)) {
			$stream = $entry.Open()
			$buffer = New-Object System.IO.MemoryStream
			$stream.CopyTo($buffer)
			$stream.Dispose()
			$Cache[$entryName] = Get-ClassFactsFromBytes -Bytes $buffer.ToArray()
			$buffer.Dispose()
		}
		$facts = $Cache[$entryName]
		if (-not $facts) { return $true }
		if ($facts.Methods.Contains($Member) -or $facts.Fields.Contains($Member)) { return $false }
		$at = if ($facts.Super) { $facts.Super -replace '/', '.' } else { $null }
	}
	$true
}

function Resolve-SourceClass {
	# Returns $null when the class is in the target jar, otherwise what happened to it. Follows a
	# short chain because one knowledge file may point at a name a later jump moved again.
	param([string]$Name, $Index, $Links)
	$at = $Name
	for ($hop = 0; $hop -lt 4; $hop++) {
		if ($Index.Classes.Contains($at)) {
			if ($hop -eq 0) { return $null }
			break
		}
		if (-not $Links.ContainsKey($at)) { return [pscustomobject]@{ Kind = 'removed'; New = $null } }
		$at = $Links[$at].To
	}
	if (-not $Index.Classes.Contains($at)) { return [pscustomobject]@{ Kind = 'removed'; New = $null } }
	$fromSimple = ($Name -split '\.')[-1]
	$toSimple = ($at -split '\.')[-1]
	$kind = if ($fromSimple -ne $toSimple) { 'renamed' } else { 'moved' }
	[pscustomobject]@{ Kind = $kind; New = $at }
}

function Test-IndexCovers {
	# True when the jar has classes in this reference's namespace, so its absence means something.
	# `com.mojang.serialization.Codec` lives in DataFixerUpper, not in the merged jar, and calling that
	# removed would be a false alarm.
	param([string]$Name, $Index)
	if ($Index.Classes.Contains($Name)) { return $true }
	$parts = $Name -split '\.'
	$root = ($parts | Select-Object -First ([math]::Min(3, $parts.Count))) -join '.'
	$Index.Roots.Contains($root)
}

function Add-ApiBreakHit {
	# One row per class, and the strongest explanation wins: renamed beats moved beats removed.
	param($Hits, [string]$Name, [string]$Kind, [string]$New)
	$rank = @{ removed = 0; moved = 1; renamed = 2 }
	if ($Hits.ContainsKey($Name) -and $rank[$Kind] -le $rank[$Hits[$Name].Kind]) { return }
	$Hits[$Name] = [pscustomobject]@{
		Name   = $Name
		Simple = ($Name -split '\.')[-1]
		Kind   = $Kind
		New    = $New
	}
}

function Get-SourceMemberUses {
	# Which removed members the source actually uses, as opposed to which classes that lost members
	# are imported. The receiver has to resolve to a type first: a static import (`import static
	# a.b.C.m;` then `m(`), a class-qualified use (`C.m`, C coming from an import), a fully qualified
	# name (`a.b.C.m`), or a typed local or field (`C c = ...; c.m(`). A member access whose receiver
	# cannot be typed is counted as skipped, never guessed: `name`, `id` and `drop` belong to
	# hundreds of classes at once. A name that still exists on the target class or a superclass is
	# not a break (removed overrides and changed signatures are what the diff records there).
	param($Files, $Index, $Zip)
	$owners = New-Object 'System.Collections.Generic.HashSet[string]'
	$names = New-Object 'System.Collections.Generic.HashSet[string]'
	$byKey = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
	$simpleOwners = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
	$membersOf = @{}
	foreach ($entry in @($Index.membersRemoved)) {
		if (-not $entry.class) { continue }
		$owner = ($entry.class -replace '/', '.') -replace '\$', '.'
		$name = "$($entry.name)"
		# Constructors and lambda/synthetic names never appear in source.
		if ($name -notmatch '^[A-Za-z_$][\w$]*$') { continue }
		[void]$owners.Add($owner)
		[void]$names.Add($name)
		$key = "$owner#$name"
		if (-not $byKey.ContainsKey($key)) { $byKey[$key] = $entry }
		if (-not $membersOf.ContainsKey($owner)) { $membersOf[$owner] = New-Object 'System.Collections.Generic.HashSet[string]' }
		[void]$membersOf[$owner].Add($name)
		$simple = ($owner -split '\.')[-1]
		if (-not $simpleOwners.ContainsKey($simple)) { $simpleOwners[$simple] = New-Object 'System.Collections.Generic.HashSet[string]' }
		[void]$simpleOwners[$simple].Add($owner)
	}
	# Member replacement links: `from` is owner.member in binary form, `to` names the new member.
	$links = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
	foreach ($entry in @($Index.replacements)) {
		if ($entry.kind -ne 'method' -and $entry.kind -ne 'field') { continue }
		$from = "$($entry.from)"
		$cut = $from.LastIndexOf('.')
		if ($cut -lt 0) { continue }
		$to = "$($entry.to)"
		$newName = if ($to.LastIndexOf('.') -ge 0) { $to.Substring($to.LastIndexOf('.') + 1) } else { $to }
		$links["$(($from.Substring(0, $cut) -replace '/', '.') -replace '\$', '.')#$($from.Substring($cut + 1))"] = $newName
	}

	$accessRe = [regex]'(?<![\w$.])([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)\s*\.\s*([A-Za-z_$][\w$]*)'
	$bareRe = [regex]'(?<![\w$.])([A-Za-z_$][\w$]*)\s*\('
	$localRe = [regex]'\b([A-Z][\w$]*)\s+([a-z_$][\w$]*)\s*(?=[=;,):])'
	$importRe = [regex]'(?m)^\s*import\s+(static\s+)?([A-Za-z_$][\w$.]*(?:\.\*)?)\s*;'
	$declRe = [regex]'\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)'

	$found = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
	$skipped = 0
	$resolved = 0
	$factCache = @{}
	foreach ($file in $Files) {
		$text = Get-JavaSourceText -Path $file.FullName
		if (-not $text) { continue }
		$imports = @{}
		$staticMembers = @{}
		foreach ($m in $importRe.Matches($text)) {
			$fqn = $m.Groups[2].Value
			if ($m.Groups[1].Success) {
				if ($fqn -match '\.\*$') { continue }
				$cut = $fqn.LastIndexOf('.')
				if ($cut -lt 0) { continue }
				$staticMembers[$fqn.Substring($cut + 1)] = $fqn.Substring(0, $cut)
				continue
			}
			if ($fqn -match '\.\*$') { continue }
			$imports[($fqn -split '\.')[-1]] = $fqn
		}
		$declared = @{}
		foreach ($m in $declRe.Matches($text)) { $declared[$m.Groups[1].Value] = $true }
		# Local variables, parameters and fields all read as `Type name` followed by = ; , ) or :.
		$varTypes = @{}
		foreach ($m in $localRe.Matches($text)) {
			$type = $m.Groups[1].Value
			if ($declared.ContainsKey($type)) { $varTypes[$m.Groups[2].Value] = ''; continue }
			if ($imports.ContainsKey($type)) { $varTypes[$m.Groups[2].Value] = $imports[$type]; continue }
			if ($simpleOwners.ContainsKey($type)) { $varTypes[$m.Groups[2].Value] = '?'; continue }
			$varTypes[$m.Groups[2].Value] = ''
		}
		# Names whose removal this file could plausibly hit: the member is gone from a class the file
		# already names (imported or typed). A `.name` on some unrelated object is not a blind spot,
		# and counting it as one would bury the real ones.
		$near = New-Object 'System.Collections.Generic.HashSet[string]'
		$seenSimple = @{}
		foreach ($simple in $imports.Keys) { $seenSimple[$simple] = $true }
		foreach ($type in $varTypes.Values) {
			if (-not $type -or $type -eq '?') { continue }
			$seenSimple[($type -split '\.')[-1]] = $true
		}
		foreach ($simple in $seenSimple.Keys) {
			if (-not $simpleOwners.ContainsKey($simple)) { continue }
			foreach ($owner in $simpleOwners[$simple]) {
				foreach ($name in $membersOf[$owner]) { [void]$near.Add($name) }
			}
		}
		# The raw file, for a real line number: blanking comments shifts character positions.
		$raw = $null

		foreach ($m in $accessRe.Matches($text)) {
			$member = $m.Groups[2].Value
			if (-not $names.Contains($member)) { continue }
			$chain = $m.Groups[1].Value -replace '\s', ''
			$owner = $null
			$ambiguous = $false
			if ($chain.Contains('.')) {
				# `a.b.C.x` and `Outer.Inner.x`: the longest prefix that is a known owner wins.
				$at = $chain
				while ($true) {
					if ($owners.Contains($at)) { $owner = $at; break }
					$cut = $at.IndexOf('.')
					if ($cut -lt 0) { break }
					$at = $at.Substring($cut + 1)
				}
				if (-not $owner) {
					$head = $chain.Substring(0, $chain.IndexOf('.'))
					if ($imports.ContainsKey($head)) {
						$candidate = $imports[$head] + $chain.Substring($chain.IndexOf('.'))
						if ($owners.Contains($candidate)) { $owner = $candidate }
					}
				}
			}
			elseif ($varTypes.ContainsKey($chain)) {
				$type = $varTypes[$chain]
				if ($type -eq '?') { $ambiguous = $true } elseif ($type) { if ($owners.Contains($type)) { $owner = $type } }
			}
			elseif ($imports.ContainsKey($chain)) {
				$fqn = $imports[$chain]
				if ($owners.Contains($fqn)) { $owner = $fqn } else { $ambiguous = $true }
			}
			if (-not $owner) {
				# An unresolved receiver is only a skip when this file names a class that lost the
				# member: another class with that name would have been found if the type were visible.
				if ($near.Contains($member)) { $skipped++ }
				continue
			}
			$resolved++
			if ($ambiguous) { continue }
			$key = "$owner#$member"
			if (-not $byKey.ContainsKey($key)) { continue }
			if ($found.ContainsKey($key)) { $found[$key].Count++; continue }
			if (-not (Test-TargetMemberGone -Owner $owner -Member $member -Zip $Zip -Cache $factCache)) { continue }
			if (-not $raw) { $raw = Get-Content $file.FullName -Raw }
			$hit = [regex]::Match($raw, '(?<![\w$])' + [regex]::Escape($chain) + '\s*\.\s*' + [regex]::Escape($member) + '(?![\w$])')
			if (-not $hit.Success) { $hit = [regex]::Match($raw, '\.\s*' + [regex]::Escape($member) + '(?![\w$])') }
			$line = if ($hit.Success) { ($raw.Substring(0, $hit.Index) -split "`n").Count } else { 0 }
			$found[$key] = [pscustomobject]@{
				Owner   = $owner
				Member  = $member
				File    = $file.Name
				Line    = $line
				New     = $links[$key]
				Count   = 1
			}
		}
		# `import static a.b.C.m;` makes a bare `m(` a use of C's member.
		foreach ($m in $bareRe.Matches($text)) {
			$name = $m.Groups[1].Value
			if (-not $staticMembers.ContainsKey($name)) { continue }
			$owner = $staticMembers[$name]
			if (-not $owners.Contains($owner)) { continue }
			$key = "$owner#$name"
			if (-not $byKey.ContainsKey($key)) { continue }
			if ($found.ContainsKey($key)) { $found[$key].Count++; continue }
			if (-not (Test-TargetMemberGone -Owner $owner -Member $name -Zip $Zip -Cache $factCache)) { continue }
			if (-not $raw) { $raw = Get-Content $file.FullName -Raw }
			$hit = [regex]::Match($raw, '(?<![\w$.])' + [regex]::Escape($name) + '\s*\(')
			$line = if ($hit.Success) { ($raw.Substring(0, $hit.Index) -split "`n").Count } else { 0 }
			$found[$key] = [pscustomobject]@{
				Owner  = $owner
				Member = $name
				File   = $file.Name
				Line   = $line
				New    = $links[$key]
				Count  = 1
			}
		}
	}

	$uses = 0
	foreach ($hit in $found.Values) { $uses += $hit.Count }
	[pscustomobject]@{
		Uses      = $uses
		Members   = @($found.Values | Sort-Object Owner, Member)
		MembersN  = $found.Count
		Skipped   = $skipped
		Resolved  = $resolved
	}
}

function Get-SourceApiBreakage {
	# Reads src/**/*.java and asks which referenced classes the target version no longer has.
	# Resolution order: the target jar, then the diff's class links, then "removed". Imports (plain,
	# static and wildcard), fully qualified uses and bare names that no import explains are all
	# resolved, so a renamed class (UseAnim -> ItemUseAnimation) is named instead of silently
	# passing. Member use inside a class that still exists is a separate pass (Get-SourceMemberUses),
	# because it needs the receiver's type per access rather than the file's imports.
	param([string]$Repo, [string]$To, [string]$Knowledge)
	$indexFile = Get-ChildItem (Join-Path $Knowledge "api-*-to-$To.json") -ErrorAction SilentlyContinue | Select-Object -First 1
	if (-not $indexFile) { return $null }
	$index = Get-Content $indexFile.FullName -Raw | ConvertFrom-Json

	$removedList = @()
	foreach ($entry in $index.classesRemoved) {
		$source = $entry.simple -replace '\$', '.'
		$removedList += [pscustomobject]@{
			Dot     = (($entry.class -replace '/', '.') -replace '\$', '.')
			Source  = $source
			Outer   = ($source -split '\.')[0]
			Simple  = $entry.simple
			Package = ($entry.package -replace '/', '.')
		}
	}
	$memberClasses = @{}
	foreach ($entry in $index.membersRemoved) {
		if ($entry.class) { $memberClasses[(($entry.class -replace '/', '.') -replace '\$', '.')] = $true }
	}

	$hits = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
	$memberTouched = @{}
	$targetIndex = Get-TargetClassIndex -Repo $Repo -Version $To
	$links = Get-ApiClassLinks -Index $index -Knowledge $Knowledge
	# Names the diff calls removed, keyed by their full old name: evidence for wildcard imports, which
	# do not say which package a bare name came from.
	$removedDots = New-Object 'System.Collections.Generic.HashSet[string]'
	foreach ($r in $removedList) { [void]$removedDots.Add($r.Dot) }
	# Only the two namespaces the merged jar actually contains can be resolved against it; a mod's
	# own `artifacts.foo.Bar` is never in the jar and must not be reported as missing.
	$vanilla = '^(?:net\.minecraft|com\.mojang)\.'
	# Multi-loader repos keep sources under common/src, fabric/src, neoforge/src, so look for any
	# src tree rather than only the repo root.
	$files = @(Get-ChildItem $Repo -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -match '\\src\\' -and $_.FullName -notmatch '\\build\\|\\\.gradle\\|\\generated\\|\\\.modaccel\\|\\run\\' })
	if ($files.Count -eq 0) { return $null }
	foreach ($file in $files) {
		$text = Get-JavaSourceText -Path $file.FullName
		if (-not $text) { continue }
		$imports = @()
		foreach ($m in [regex]::Matches($text, '(?m)^\s*import\s+(static\s+)?([A-Za-z_$][\w$.]*(?:\.\*)?)\s*;')) {
			$fqn = $m.Groups[2].Value
			# A static import names a member; the class to resolve is the one before it, whatever the
			# member's case (EAT is an enum constant, not a nested class).
			if ($m.Groups[1].Success -and $fqn -notmatch '\.\*$' -and $fqn.Contains('.')) {
				$fqn = $fqn.Substring(0, $fqn.LastIndexOf('.'))
			}
			$imports += $fqn
		}
		$declared = @{}
		foreach ($m in [regex]::Matches($text, '\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)')) { $declared[$m.Groups[1].Value] = $true }
		$idents = @{}
		foreach ($m in [regex]::Matches($text, '[A-Za-z_$][\w$]*')) { $idents[$m.Value] = $true }
		$explicit = @{}
		foreach ($fqn in $imports) {
			if ($fqn -notmatch '\.\*$') { $explicit[($fqn -split '\.')[-1]] = $true }
		}
		# A wildcard import makes a bare name unreadable by text, so those files are not counted on
		# bare names at all (measured: `import java.util.*` plus `new Random()` read as vanilla Random).
		$wildcards = @($imports | Where-Object { $_ -match '\.\*$' })

		# 1. Imports, resolved against the target jar. This is the case that used to say nothing: a
		#    removed class whose replacement is a rename resolves to no class at all, so every file
		#    importing it compiled fine as far as the old scan was concerned.
		if ($targetIndex) {
			foreach ($fqn in $imports) {
				if ($fqn -match '\.\*$') {
					$package = $fqn.Substring(0, $fqn.Length - 2)
					if ($package -notmatch $vanilla) { continue }
					if (-not (Test-IndexCovers -Name $package -Index $targetIndex)) { continue }
					# `import static a.b.C.*` names a class, `import a.b.*` names a package.
					if ($targetIndex.Classes.Contains($package) -or $targetIndex.Packages.Contains($package)) { continue }
					Add-ApiBreakHit -Hits $hits -Name $fqn -Kind 'removed' -New $null
					continue
				}
				if ($fqn -notmatch $vanilla) { continue }
				if (-not (Test-IndexCovers -Name $fqn -Index $targetIndex)) { continue }
				$resolved = Resolve-SourceClass -Name $fqn -Index $targetIndex -Links $links
				if ($resolved) { Add-ApiBreakHit -Hits $hits -Name $fqn -Kind $resolved.Kind -New $resolved.New }
			}

			# 2. A wildcard import brings in names the file does not list, so a name the diff knows was
			#    removed or moved from that package is counted when the file uses it. A name the target
			#    jar merely has elsewhere is NOT counted: several wildcard imports can supply it, and
			#    guessing which one is how a scan starts lying.
			$wildPackages = @($imports | Where-Object { $_ -match '\.\*$' } | ForEach-Object { $_.Substring(0, $_.Length - 2) } | Where-Object { $_ -match $vanilla })
			foreach ($package in $wildPackages) {
				if (-not (Test-IndexCovers -Name $package -Index $targetIndex)) { continue }
				foreach ($name in $idents.Keys) {
					if ($name -cnotmatch '^[A-Z]') { continue }
					if ($declared.ContainsKey($name) -or $explicit.ContainsKey($name)) { continue }
					$candidate = "$package.$name"
					$resolved = Resolve-SourceClass -Name $candidate -Index $targetIndex -Links $links
					if (-not $resolved) { continue }
					if (-not ($links.ContainsKey($candidate) -or $removedDots.Contains($candidate))) { continue }
					Add-ApiBreakHit -Hits $hits -Name $candidate -Kind $resolved.Kind -New $resolved.New
				}
			}

			# 3. Fully qualified uses (`net.minecraft.world.item.UseAnim.EAT`) need no import, so the
			#    import pass cannot see them. The class is the run of names up to the last lowercase
			#    segment, e.g. ...world.item.UseAnim, and anything after it is a member.
			$checked = @{}
			foreach ($m in [regex]::Matches($text, '(?<![\w$.])(?:[a-z_][\w$]*\.){2,}[A-Z][\w$]*(?:\.[A-Z][\w$]*)*')) {
				$fqn = $m.Value
				if ($fqn -notmatch $vanilla) { continue }
				$tokens = $fqn -split '\.'
				$cut = 0
				while ($cut -lt $tokens.Count -and $tokens[$cut] -cnotmatch '^[A-Z]') { $cut++ }
				if ($cut -eq 0 -or $cut -ge $tokens.Count) { continue }
				$class = ($tokens[0..$cut]) -join '.'
				if ($checked.ContainsKey($class)) { continue }
				$checked[$class] = $true
				if (-not (Test-IndexCovers -Name $class -Index $targetIndex)) { continue }
				$resolved = Resolve-SourceClass -Name $class -Index $targetIndex -Links $links
				if ($resolved) { Add-ApiBreakHit -Hits $hits -Name $class -Kind $resolved.Kind -New $resolved.New }
			}
		}

		# 4. Bare names the diff lists as removed, when no import in this file resolves them elsewhere.
		foreach ($r in $removedList) {
			if (-not $idents.ContainsKey($r.Outer)) { continue }
			$bare = '(?<![\w$.])' + [regex]::Escape($r.Source) + '(?![\w$])'
			$qualified = '(?<![\w$])' + [regex]::Escape($r.Dot) + '(?![\w$])'
			if ($r.Source -match '\.') {
				# An inner class is written Outer.Inner in source, never imported on its own.
				if ($text -cmatch $bare -or $text -cmatch $qualified) { Add-ApiBreakHit -Hits $hits -Name $r.Dot -Kind 'removed' -New $null }
				continue
			}
			if ($declared.ContainsKey($r.Source)) { continue }
			if ($text -cmatch $qualified) { Add-ApiBreakHit -Hits $hits -Name $r.Dot -Kind 'removed' -New $null; continue }
			if ($text -cnotmatch $bare) { continue }
			# The same simple name imported from somewhere else resolves to that class, not this one,
			# and a wildcard import leaves it unresolved: neither is counted on the bare name alone.
			$shadowed = $false
			$seen = $false
			foreach ($fqn in $imports) {
				if ($fqn -eq $r.Dot) { $seen = $true; break }
				if ($fqn -match ('\.' + [regex]::Escape($r.Source) + '$')) { $shadowed = $true }
			}
			foreach ($package in $wildcards) {
				if ($package -ne ($r.Package + '.*')) { $shadowed = $true }
			}
			if ($seen -or -not $shadowed) { Add-ApiBreakHit -Hits $hits -Name $r.Dot -Kind 'removed' -New $null }
		}

		foreach ($fqn in $imports) {
			if ($memberClasses.ContainsKey($fqn)) { $memberTouched[$fqn] = $true }
		}
	}

	$removed = @($hits.Values | Where-Object { $_.Kind -eq 'removed' })
	$renamed = @($hits.Values | Where-Object { $_.Kind -eq 'renamed' })
	$moved = @($hits.Values | Where-Object { $_.Kind -eq 'moved' })
	# Member use inside a class that still exists needs a receiver type per access, so it is its own
	# pass: the target jar settles whether a name is really gone, and everything unresolvable is
	# counted rather than guessed.
	$memberUse = $null
	if ($targetIndex -and $targetIndex.JarPath -and (Test-Path $targetIndex.JarPath)) {
		Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
		$zip = $null
		try { $zip = [System.IO.Compression.ZipFile]::OpenRead($targetIndex.JarPath) } catch { $zip = $null }
		if ($zip) {
			try { $memberUse = Get-SourceMemberUses -Files $files -Index $index -Zip $zip }
			finally { $zip.Dispose() }
		}
	}
	$touchedRemovals = 0
	foreach ($entry in @($index.membersRemoved)) {
		if (-not $entry.class) { continue }
		if ($memberTouched.ContainsKey((($entry.class -replace '/', '.') -replace '\$', '.'))) { $touchedRemovals++ }
	}

	[pscustomobject]@{
		Index          = $indexFile.Name
		TargetJar      = if ($targetIndex) { $targetIndex.Jar } else { '' }
		Note           = if ($targetIndex) { '' } else { "no $To class index in the loom caches, so only the diff was used and renames cannot be seen" }
		Files          = $files.Count
		RemovedCount   = $removed.Count
		RemovedNames   = @($removed | ForEach-Object { $_.Simple })
		RenamedCount   = $renamed.Count
		RenamedNames   = @($renamed | ForEach-Object { "$($_.Simple) -> $(($_.New -split '\.')[-1])" })
		MovedCount     = $moved.Count
		MovedNames     = @($moved | ForEach-Object { "$($_.Simple) -> $($_.New)" })
		Moved          = $moved
		MemberClasses  = @($memberTouched.Keys)
		MemberRemovals = $touchedRemovals
		MemberUses     = if ($memberUse) { $memberUse.Uses } else { 0 }
		MemberNames    = if ($memberUse) { $memberUse.MembersN } else { 0 }
		MemberHits     = if ($memberUse) { @($memberUse.Members) } else { @() }
		MemberSkipped  = if ($memberUse) { $memberUse.Skipped } else { 0 }
		MemberResolved = if ($memberUse) { $memberUse.Resolved } else { 0 }
		MemberChecked  = [bool]$memberUse
	}
}

function Invoke-ToolchainCheck {
	param([string]$Repo, [string]$To)
	if (-not (Test-Path $Repo)) { throw "no repo at $Repo" }
	$repoPath = (Resolve-Path $Repo).Path
	$buildText = Get-BuildText -Repo $repoPath
	$wrapper = Get-WrapperVersion -Repo $repoPath
	$loader = Get-Loader -Repo $repoPath -BuildText $buildText
	$pins = Get-VersionPins -Repo $repoPath -BuildText $buildText
	$needJdk = Get-JdkForGradle -Wrapper $wrapper
	$jdk = Find-Jdk -Version $needJdk

	$blockers = New-Object System.Collections.Generic.List[string]
	$warnings = New-Object System.Collections.Generic.List[string]

	if (-not $wrapper) { $warnings.Add('no Gradle wrapper: cannot tell which JDK it needs') }
	if (-not $jdk) { $blockers.Add("needs JDK $needJdk and none is installed (checked .tools, %USERPROFILE%\tools, bench\mods)") }

	# A NeoGradle that declares a newer Gradle API than the wrapper provides fails at configuration
	# time, before any mod code is considered. Measured on MineColonies: userdev 7.0.192 needs 8.13.
	$neoGradle = [regex]::Match($buildText, 'net\.neoforged\.gradle:userdev[:"'']?\s*([\d.]+)').Groups[1].Value
	if ($neoGradle -and $wrapper -and $wrapper.Major -eq 8 -and $wrapper.Minor -lt 13 -and ([version]$neoGradle -ge [version]'7.0.192')) {
		$blockers.Add("NeoGradle $neoGradle requires Gradle 8.13+, wrapper is $($wrapper.Text)")
	}
	if ($loader -eq 'forge') {
		$warnings.Add('ForgeGradle: no fast lane, no installer and no hot reload for this loader')
	}
	if ($buildText -match "apply from:\s*['`"]https?://([^/'`"]+)") {
		$warnings.Add("build logic is fetched from $($matches[1]); plugin and Gradle constraints cannot be read locally")
	}

	if ($To) {
		if ($loader -eq 'fabric') {
			$problem = Test-FabricTarget -Minecraft $To
			if ($problem.Blocker) { $blockers.Add($problem.Blocker) }
			if ($problem.Warning) { $warnings.Add($problem.Warning) }
		}
		elseif ($loader -eq 'neoforge') {
			$neo = Test-NeoForgeTarget -Minecraft $To
			if ($neo.Blocker) { $blockers.Add($neo.Blocker) }
			if ($neo.Warning) { $warnings.Add($neo.Warning) }
		}
		elseif ($loader -eq 'forge') {
			$warnings.Add("target $To : no Forge lane in this harness; only diff and -Log apply")
		}
	}

	# The target's own classes answer what disappeared: imports, static imports, wildcards and fully
	# qualified uses are resolved against the target jar, and the diff's class links name a rename or
	# a move. Warning only: the source may be on an older branch, and this is a text scan, not a
	# compiler.
	$breaks = $null
	if ($To) { $breaks = Get-SourceApiBreakage -Repo $repoPath -To $To -Knowledge (Join-Path $root 'knowledge') }

	# Static reading is a guess; `gradlew help` with the chosen JDK is the proof. It resolves the
	# wrapper, the plugins and the settings, and stops before compiling any mod code.
	$prove = $null
	$deps = $null
	if ($jdk -and $wrapper) {
		Write-Host 'proving   gradlew help with that JDK...' -ForegroundColor DarkGray
		$prove = Test-GradleConfigures -Repo $repoPath -Jdk $jdk
		if ($prove.Ran -and -not $prove.Ok) { $blockers.Add("Gradle cannot configure: $($prove.Reason)") }
		elseif ($prove.Ok) {
			$prefix = if ($pins['stonecutter.active']) { ":$($pins['stonecutter.active']):" } else { '' }
			$deps = Test-GradleDependencies -Repo $repoPath -Jdk $jdk -Prefix $prefix
			if ($deps.Ran -and -not $deps.Ok) { $blockers.Add("mod dependencies do not resolve: $($deps.Reason)") }
		}
	}

	$verdict = if ($blockers.Count -gt 0) { 'BLOCKED' }
		elseif ($To -and ($loader -eq 'forge' -or $loader -eq 'unknown')) { 'OUT OF SCOPE' }
		else { 'READY' }
	$minecraft = if ($pins['minecraft_version']) { $pins['minecraft_version'] } elseif ($pins['exactMinecraftVersion']) { $pins['exactMinecraftVersion'] } elseif ($pins['minecraftVersion']) { $pins['minecraftVersion'] } else { 'unknown' }

	Write-Host "repo      $repoPath"
	Write-Host "wrapper   $(if ($wrapper) { "Gradle $($wrapper.Text)" } else { 'none' })"
	Write-Host "loader    $loader"
	Write-Host "pins      minecraft=$minecraft$(if ($pins['stonecutter.active']) { " node=$($pins['stonecutter.active'])" })"
	Write-Host "jdk       needs $needJdk -> $(if ($jdk) { $jdk } else { 'MISSING' })"
	if ($To) { Write-Host "target    $To" }
	Write-Host "proves    $(if (-not $prove) { 'skipped (no JDK)' } elseif ($prove.Ok) { "gradlew help PASS in $($prove.Seconds)s" } elseif ($prove.Ran) { "gradlew help FAIL in $($prove.Seconds)s" } else { $prove.Reason })"
	if ($deps) { Write-Host "deps      $(if (-not $deps.Ran) { $deps.Reason } elseif ($deps.Ok) { 'modImplementation resolves' } else { "modImplementation FAILED: $($deps.Reason)" })" }
	if ($breaks) {
		$names = (@($breaks.RemovedNames | Sort-Object) | Select-Object -First 10) -join ', '
		$line = "breaks    $($breaks.RemovedCount) class reference(s) gone in ${To} with no replacement"
		if ($names) { $line += ": $names" }
		if ($breaks.RemovedCount -gt 10) { $line += ", ..." }
		Write-Host $line -ForegroundColor $(if ($breaks.RemovedCount -gt 0) { 'Yellow' } else { 'Gray' })
		if ($breaks.RenamedCount -gt 0) {
			$renamedLine = "renamed   $($breaks.RenamedCount) class reference(s) renamed in ${To}: " +
				((@($breaks.RenamedNames | Sort-Object) | Select-Object -First 10) -join ', ')
			if ($breaks.RenamedCount -gt 10) { $renamedLine += ', ...' }
			Write-Host $renamedLine -ForegroundColor Yellow
		}
		if ($breaks.MovedCount -gt 0) {
			$movedLine = "moved     $($breaks.MovedCount) class reference(s) moved package in ${To}: " +
				((@($breaks.MovedNames | Sort-Object) | Select-Object -First 10) -join ', ')
			if ($breaks.MovedCount -gt 10) { $movedLine += ', ...' }
			Write-Host $movedLine -ForegroundColor Yellow
		}
		if ($breaks.Note) { Write-Host "scan      $($breaks.Note)" -ForegroundColor Gray }
		$memberNote = if ($breaks.MemberUses -gt 0) {
			$examples = @($breaks.MemberHits | Select-Object -First 10 | ForEach-Object {
				$shown = "$(($_.Owner -split '\.')[-1]).$($_.Member)"
				if ($_.New) { $shown += " -> $($_.New)" }
				if ($_.File) { $shown += " ($($_.File):$($_.Line))" }
				if ($_.Count -gt 1) { $shown += " x$($_.Count)" }
				$shown
			})
			$line = "$($breaks.MemberUses) use(s) of $($breaks.MemberNames) removed member(s) in ${To}: " + ($examples -join ', ')
			if ($breaks.MemberNames -gt 10) { $line += ', ...' }
			$line
		}
		elseif (-not $breaks.MemberChecked -and $breaks.MemberClasses.Count -gt 0) {
			"cannot be checked: no $To class index to name the members; the $($breaks.MemberClasses.Count) class(es) used here lost $($breaks.MemberRemovals) member(s)"
		}
		elseif ($breaks.MemberClasses.Count -gt 0) {
			"0 use(s) of the member(s) removed from the $($breaks.MemberClasses.Count) class(es) used here in $To"
		}
		else { 'no used class lost members in the diff, so nothing to check' }
		Write-Host "members   $memberNote" -ForegroundColor $(if ($breaks.MemberUses -gt 0) { 'Yellow' } else { 'Gray' })
		if ($breaks.MemberChecked -and $breaks.MemberResolved + $breaks.MemberSkipped -gt 0) {
			$rate = [math]::Round(100 * $breaks.MemberSkipped / ($breaks.MemberResolved + $breaks.MemberSkipped))
			Write-Host "skipped   $($breaks.MemberSkipped) of $($breaks.MemberSkipped + $breaks.MemberResolved) member access(es) whose name was removed somewhere ($rate%): receiver is a call result, a generic or a wildcard import, so no type to check" -ForegroundColor Gray
		}
	}
	foreach ($warning in $warnings) { Write-Host "warning   $warning" -ForegroundColor Yellow }
	foreach ($blocker in $blockers) { Write-Host "blocker   $blocker" -ForegroundColor Red }
	# Known walls, so the same outage or version floor does not have to be re-diagnosed each time.
	$knownFile = Join-Path $root 'knowledge\toolchain.json'
	if (Test-Path $knownFile) {
		$known = Get-Content $knownFile -Raw | ConvertFrom-Json
		foreach ($blocker in $blockers) {
			foreach ($entry in $known) {
				if ($blocker -match [regex]::Escape($entry.match)) { Write-Host "known     $($entry.note)" -ForegroundColor Cyan }
			}
		}
	}
	Write-Host "verdict   $verdict" -ForegroundColor $(if ($verdict -eq 'READY') { 'Green' } elseif ($verdict -eq 'OUT OF SCOPE') { 'Yellow' } else { 'Red' })
	return [pscustomobject]@{ Verdict = $verdict; Blockers = @($blockers); Jdk = $jdk; Loader = $loader; Wrapper = if ($wrapper) { $wrapper.Text } else { '' } }
}

