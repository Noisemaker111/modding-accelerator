# Port reports built from the compiler's own errors.
# The binary diff (tools\SymbolDiff.java) can only see compiled classes, so it is blind exactly
# while a build is red. This reads javac's output instead and adds what the jar knows.

function Get-JarClassList {
	param([string]$Jar)
	Add-Type -AssemblyName System.IO.Compression.FileSystem
	$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
	try {
		$zip.Entries |
			Where-Object { $_.FullName -like '*.class' -and $_.FullName -notmatch '\$' } |
			ForEach-Object { $_.FullName -replace '/', '.' -replace '\.class$', '' }
	}
	finally { $zip.Dispose() }
}

function Get-EditDistance {
	param([string]$A, [string]$B, [int]$Limit = 6)
	if ([math]::Abs($A.Length - $B.Length) -gt $Limit) { return $Limit + 1 }
	$previous = 0..$B.Length
	for ($i = 1; $i -le $A.Length; $i++) {
		$current = @($i) + (New-Object int[] $B.Length)
		for ($j = 1; $j -le $B.Length; $j++) {
			$cost = if ($A[$i - 1] -eq $B[$j - 1]) { 0 } else { 1 }
			$current[$j] = [math]::Min([math]::Min($current[$j - 1] + 1, $previous[$j] + 1), $previous[$j - 1] + $cost)
		}
		$previous = $current
	}
	return $previous[$B.Length]
}

function Get-NearName {
	param([string]$Name, [string[]]$Pool, [int]$Limit = 3)
	$n = $Name.ToLowerInvariant()
	$scored = foreach ($class in $Pool) {
		$simple = ($class -split '\.')[-1].ToLowerInvariant()
		$score = 0
		if ($simple -eq $n) { $score = 100 }
		elseif ($simple.StartsWith($n) -or $n.StartsWith($simple)) { $score = 60 }
		elseif ($simple.Contains($n)) { $score = 40 }
		else {
			$distance = Get-EditDistance -A $n -B $simple -Limit 4
			if ($distance -le 4) { $score = 30 - ($distance * 5) }
		}
		if ($score -gt 0) { [pscustomobject]@{ Name = $class; Score = $score } }
	}
	$scored | Sort-Object Score -Descending | Select-Object -First $Limit
}

function Read-JavacErrors {
	param([string]$Log)
	$lines = Get-Content $Log
	$errors = New-Object System.Collections.Generic.List[object]
	$seen = [System.Collections.Generic.HashSet[string]]::new()
	for ($i = 0; $i -lt $lines.Count; $i++) {
		$match = [regex]::Match($lines[$i], '^\s*(?<file>[A-Za-z]:[^:]+\.java):(?<line>\d+): error: (?<message>.+)$')
		if (-not $match.Success) {
			# Kotlin DSL and Kotlin sources report like this; include them so a Kotlin repo is not blank.
			$match = [regex]::Match($lines[$i], '^e: file:///(?<file>[^:]+):(?<line>\d+):\d+ (?<message>.+)$')
			if (-not $match.Success) { continue }
		}
		$symbol = ''
		$location = ''
		$column = if ($i + 2 -lt $lines.Count) { $lines[$i + 2].IndexOf('^') } else { -1 }
		if ($column -ge $match.Groups['file'].Index) { $column -= $match.Groups['file'].Index }
		for ($j = $i + 1; $j -lt [math]::Min($i + 8, $lines.Count); $j++) {
			if ($lines[$j] -match '^\s+symbol:\s+(.+)$') { $symbol = $matches[1].Trim() }
			elseif ($lines[$j] -match '^\s+location:\s+(.+)$') { $location = $matches[1].Trim() }
			elseif ($lines[$j] -match '^\s*[A-Za-z]:.*\.java:\d+: error:') { break }
		}
		$key = '{0}:{1}:{2}:{3}:{4}' -f $match.Groups['file'].Value, $match.Groups['line'].Value, $column, $match.Groups['message'].Value, $symbol
		if (-not $seen.Add($key)) { continue }
		$errors.Add([pscustomobject]@{
			File     = $match.Groups['file'].Value
			Line     = [int]$match.Groups['line'].Value
			Column   = $column
			Message  = $match.Groups['message'].Value
			Symbol   = $symbol
			Location = $location
		})
	}
	return $errors
}

function Split-Symbol {
	param([string]$Symbol)
	if (-not $Symbol) { return [pscustomobject]@{ Kind = 'unknown'; Name = ''; Owner = '' } }
	$match = [regex]::Match($Symbol, '^(?<kind>class|interface|enum|method|variable|constructor)\s+(?<name>[A-Za-z_$][\w$]*)')
	if ($match.Success) {
		$kind = $match.Groups['kind'].Value
		$name = $match.Groups['name'].Value
		return [pscustomobject]@{ Kind = $kind; Name = $name; Owner = '' }
	}
	return [pscustomobject]@{ Kind = 'unknown'; Name = $Symbol; Owner = '' }
}

function Get-ApiRemovals {
	# The jar diff (port -Api) knows what stopped existing between two versions. Use the newest
	# index for the target so an error is labelled with a fact, not a guess.
	param([string]$Root, [string]$Version)
	if (-not $Version) { return $null }
	$here = if ($Root) { $Root } else { Get-Location }
	$file = Get-ChildItem (Join-Path $here 'knowledge') -Filter "api-*-to-$Version.json" -ErrorAction SilentlyContinue |
		Sort-Object Name -Descending | Select-Object -First 1
	if (-not $file) { return $null }
	$json = Get-Content $file.FullName -Raw | ConvertFrom-Json
	$bySimple = @{}
	foreach ($entry in $json.classesRemoved) {
		$full = if ($entry -is [string]) { $entry } else { $entry.class }
		if (-not $full) { continue }
		$simple = ($full -split '/')[-1]
		if (-not $bySimple.ContainsKey($simple)) { $bySimple[$simple] = @() }
		$bySimple[$simple] += $full
	}
	$moved = @{}
	foreach ($entry in $json.classesMoved) {
		$simple = if ($entry.simple) { $entry.simple } else { (($entry.class ?? '') -split '/')[-1] }
		if ($simple) { $moved[$simple] = $entry }
	}
	$replaced = @{}
	foreach ($link in $json.replacements) {
		if ($link.kind -ne 'class') { continue }
		$oldSimple = ($link.from -split '/')[-1]
		if ($oldSimple -and -not $replaced.ContainsKey($oldSimple)) { $replaced[$oldSimple] = $link }
	}
	return [pscustomobject]@{ File = $file.FullName; Removed = $bySimple; Moved = $moved; Replaced = $replaced; Counts = $json.counts }
}

function New-PortReportFromLog {
	param(
		[string]$Log,
		[string]$Jar,
		[string]$Out,
		[string]$KnowledgeFile,
		[string]$Root,
		[string]$To = ''
	)
	if (-not (Test-Path $Log)) { throw "no log at $Log" }
	$errors = Read-JavacErrors -Log $Log
	$classes = @()
	if ($Jar -and (Test-Path $Jar)) { $classes = @(Get-JarClassList -Jar $Jar) }
	$knowledge = @()
	if ($KnowledgeFile -and (Test-Path $KnowledgeFile)) { $knowledge = Get-Content $KnowledgeFile -Raw | ConvertFrom-Json }
	$tokenPattern = '[A-Za-z_][A-Za-z0-9_]{2,}'

	$items = foreach ($error in $errors) {
		$symbol = Split-Symbol -Symbol $error.Symbol
		$owner = if ($error.Location -match 'class\s+([\w.$]+)') { $matches[1] } else { '' }
		$ownerExists = $owner -and ($classes -contains $owner)
		$near = @()
		if ($symbol.Kind -in @('class', 'interface', 'enum') -and $classes.Count -gt 0) {
			$near = @(Get-NearName -Name $symbol.Name -Pool $classes)
		}
		$notes = @()
		foreach ($entry in $knowledge) {
			if (-not $entry.from -or -not $entry.to) { continue }
			$before = @([regex]::Matches([string]$entry.from, $tokenPattern) | ForEach-Object { $_.Value })
			if ($symbol.Name -and ($before -contains $symbol.Name)) {
				$notes += "$($entry.jump): $($entry.note)"
			}
		}
		# A symbol that no longer exists and has no near match cannot be renamed mechanically:
		# 1.21.5 removed BakedModel, ThrownPotion, CoreShaders and RenderSystem.setShader outright.
		$exactExists = $symbol.Name -and ($classes | Where-Object { ($_ -split '\.')[-1] -eq $symbol.Name })
		$structural =
			($symbol.Kind -in @('class', 'interface', 'enum') -and -not $exactExists -and $near.Count -eq 0) -or
			($error.Message -match 'does not override|is not abstract|incompatible types|cannot be converted')
		[pscustomobject]@{
			File        = $error.File
			Line        = $error.Line
			Message     = $error.Message
			Symbol      = $error.Symbol
			Kind        = $symbol.Kind
			Name        = $symbol.Name
			Owner       = $owner
			OwnerExists = $ownerExists
			Near        = $near
			Notes       = ($notes | Select-Object -Unique)
			Structural  = [bool]$structural
		}
	}
	$items = @($items)

	# Cross-reference the jar diff (port -Api) so a removed symbol is stated as a fact.
	$api = $null
	$target = $To
	if (-not $target -and $Jar) {
		$found = [regex]::Match((Split-Path $Jar -Leaf), '(\d+\.\d+(?:\.\d+)?)')
		if ($found.Success) { $target = $found.Groups[1].Value }
	}
	if ($target) { $api = Get-ApiRemovals -Root $Root -Version $target }
	# Recipes for changes no jar diff can name (semantic rewrites), matched on the error message.
	$recipes = @()
	$recipeFile = Join-Path $Root 'knowledge\structural.json'
	if (Test-Path $recipeFile) { $recipes = Get-Content $recipeFile -Raw | ConvertFrom-Json }
	if ($recipes.Count -gt 0) {
		foreach ($item in $items) {
			if (-not $item.Structural) { continue }
			$text = "$($item.Message) $($item.Symbol)"
			foreach ($recipe in $recipes) {
				if ($text -match $recipe.match) {
					$item | Add-Member -NotePropertyName Recipe -NotePropertyValue $recipe -Force
					break
				}
			}
		}
	}
	if ($api) {
		foreach ($item in $items) {
			$simple = $item.Name
			if (-not $simple) { continue }
			if ($api.Replaced.ContainsKey($simple)) {
				$link = $api.Replaced[$simple]
				$item | Add-Member -NotePropertyName ApiNote -NotePropertyValue ("removed in $target, replaced by " + ($link.to -replace '/', '.') + " (" + $link.evidence + ")") -Force
			}
			elseif ($api.Removed.ContainsKey($simple)) {
				$item | Add-Member -NotePropertyName ApiNote -NotePropertyValue ("removed in $target, confirmed by the jar diff: " + (($api.Removed[$simple]) -join ', ')) -Force
			}
			elseif ($api.Moved.ContainsKey($simple)) {
				$item | Add-Member -NotePropertyName ApiNote -NotePropertyValue ("moved in $target to " + $api.Moved[$simple].to) -Force
			}
		}
	}

	if ($items.Count -eq 0) {
		# No source errors: the build died in its script, a dependency or a toolchain. Say that
		# instead of printing a report with nothing in it.
		$evidence = @()
		foreach ($line in (Get-Content $Log)) {
			if ($line -match 'What went wrong:|Could not resolve|FAILURE: Build|A problem occurred|Unsupported class file|requires Gradle|Plugin \[id|Could not find method|Could not create task') {
				$evidence += ('  ' + $line.Trim())
				if ($evidence.Count -ge 15) { break }
			}
		}
		$lines = @(
			'# Port report (from a failed build)'
			''
			"Log: $Log"
			"Generated: $(Get-Date -Format s)"
			''
			'## The build never reached the compiler'
			''
			'No source errors were reported, so this is the build script, a dependency pin or the'
			'toolchain - not your mod code. First matching lines:'
			''
			'```'
			$evidence
			'```'
			''
			'## Handoff'
			''
			'1. Fix the build so it reaches compilation (dependency version, Gradle/Loom/NeoGradle pin, JDK).'
			'2. Run the build again; when it fails in source, `-Log` will group those errors by file.'
			'3. When it compiles, `.\modaccel port -New <jar>` sweeps references against the target jar.'
			''
		)
		$lines | Set-Content $Out
		return [pscustomobject]@{ Errors = 0; Files = 0; Report = $Out; Stage = 'build' }
	}

	$byFile = $items | Group-Object File | Sort-Object Count -Descending
	$lines = @(
		'# Port report (from a failed build)'
		''
		"Log: $Log"
		"Target jar: $(if ($Jar) { $Jar } else { '(none given)' })"
		"Generated: $(Get-Date -Format s)"
		"Errors: $($items.Count) in $($byFile.Count) file(s) - $(@($items | Where-Object { -not $_.Structural }).Count) mechanical, $(@($items | Where-Object { $_.Structural }).Count) structural"
		''
		'## Fix in this order'
		''
	)
	foreach ($group in $byFile) {
		$relative = if ($Root -and $group.Name.StartsWith($Root)) { $group.Name.Substring($Root.Length).TrimStart('\', '/') } else { $group.Name }
		$lines += "### $relative  ($($group.Count) error(s))"
		foreach ($item in ($group.Group | Sort-Object Line)) {
			$what = if ($item.Kind -ne 'unknown' -and $item.Name) { "$($item.Kind) $($item.Name)" } elseif ($item.Symbol) { $item.Symbol } else { $item.Message }
			$lines += "- line $($item.Line): $what"
			if ($item.Near.Count -gt 0) {
				$best = ($item.Near | ForEach-Object { $_.Name }) -join ', '
				$lines += "  - in the target jar, nearest: $best"
			}
			elseif ($item.Owner -and $item.OwnerExists -and $item.Kind -eq 'method') {
				$lines += "  - $($item.Owner) still exists: the method is gone or changed, not renamed with it"
			}
			if ($item.Structural) {
				$lines += '  - structural: this name does not exist in the target jar at all, so no rename fixes it; find what replaced it'
			}
			if ($item.ApiNote) { $lines += "  - jar diff: $($item.ApiNote)" }
			if ($item.Recipe) { $lines += "  - recipe [$($item.Recipe.id)]: $($item.Recipe.steps[0])" }
			if ($item.Notes.Count -gt 0) {
				foreach ($note in $item.Notes) { $lines += "  - knowledge: $note" }
			}
		}
		$lines += ''
	}
	$lines += '## Handoff'
	$lines += ''
	$lines += 'Work file by file, then build again. When the build compiles, run'
	$lines += '`.\modaccel port -New <jar>` to sweep the references that only appear once classes exist.'
	$lines += ''
	$lines += 'Only trust a count from a clean build: an incremental build reports errors only for the files'
	$lines += 'it rebuilt, and javac still prints a 100-errors summary even when it listed every line.'
	$lines += ''
	$lines | Set-Content $Out
	return [pscustomobject]@{ Errors = $items.Count; Files = $byFile.Count; Report = $Out }
}

# --- automatic fixes ---------------------------------------------------------
# A known port cause costs one round: read the errors, apply the rules that the
# error text selects, rebuild, repeat. The rules live in knowledge\fixes.json so a
# new fact is a data edit, not a new branch in this file.

function Get-LogRepoRoot {
	# The repo a log came from: the first reported source file with a gradlew.bat above it.
	param([string[]]$Paths)
	foreach ($path in ($Paths | Select-Object -Unique)) {
		if (-not $path) { continue }
		$dir = Split-Path -Parent $path
		while ($dir -and (Split-Path -Parent $dir) -ne $dir) {
			if (Test-Path (Join-Path $dir 'gradlew.bat')) { return $dir }
			if (Test-Path (Join-Path $dir 'gradlew')) { return $dir }
			$dir = Split-Path -Parent $dir
		}
		if ($dir -and (Test-Path (Join-Path $dir 'gradlew.bat'))) { return $dir }
	}
	return ''
}

function Get-RepoRelative {
	param([string]$File, [string]$FromRoot)
	if (-not $File -or -not $FromRoot) { return '' }
	$full = [System.IO.Path]::GetFullPath($File)
	if (-not $full.StartsWith($FromRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return '' }
	return $full.Substring($FromRoot.Length).TrimStart('\', '/')
}

function Add-MissingImports {
	# Insert after the last import (or the package line) so a rule can name a registry
	# type without the caller hand-adding imports to every affected file.
	param([string]$Text, [string[]]$Imports)
	foreach ($import in $Imports) {
		if ($Text -match ('(?m)^\s*import\s+' + [regex]::Escape($import) + '\s*;')) { continue }
		$line = [Environment]::NewLine + "import $import;"
		$existing = [regex]::Matches($Text, '(?m)^import\s+[^\r\n]+;[ \t]*$')
		if ($existing.Count -gt 0) {
			$last = $existing[$existing.Count - 1]
			$Text = $Text.Insert($last.Index + $last.Length, $line)
		}
		else {
			$package = [regex]::Match($Text, '(?m)^package\s+[^\r\n]+;[ \t]*$')
			if ($package.Success) { $Text = $Text.Insert($package.Index + $package.Length, $line) }
		}
	}
	return $Text
}

function Get-PortFixJdk {
	# The build runs on the JDK its own wrapper needs, not the one this shell happens to have:
	# Gradle 8.5-8.14 refuses class file major 69 (JDK 25) and Gradle 9 refuses 21.
	param([string]$Repo, [string]$Harness)
	$want = 25
	$props = Join-Path $Repo 'gradle\wrapper\gradle-wrapper.properties'
	if (Test-Path $props) {
		$found = [regex]::Match((Get-Content $props -Raw), 'gradle-(\d+)\.(\d+)')
		if ($found.Success) {
			$major = [int]$found.Groups[1].Value
			$minor = [int]$found.Groups[2].Value
			$want = if ($major -ge 9) { 25 } elseif ($major -eq 8 -and $minor -ge 5) { 21 } else { 17 }
		}
	}
	$candidates = @(
		@(Get-ChildItem (Join-Path $Harness '.tools') -Directory -Filter "jdk-$want*" -ErrorAction SilentlyContinue) +
		@(Get-ChildItem (Join-Path $env:USERPROFILE 'tools') -Directory -Filter "jdk-$want*" -ErrorAction SilentlyContinue)
	) | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Sort-Object Name -Descending | Select-Object -First 1
	if ($candidates) { return $candidates.FullName }
	return ''
}

# The generated rename map for this repo's version jump: Minecraft before (the last commit's
# gradle.properties) and after (the working copy), in the names the repo compiles against.
# Built from published mappings by tools/VersionMap.java, so no version needs hand-written data.
# Fetches the real ports of this jump from the repos in knowledge/port-corpus.json (their A and B
# branches compared on GitHub) and learns rewrites from the ones that use the same names. The repo
# being ported is left out, so it never learns from its own answer.
function Update-LearnedRewrites {
	param([string]$From, [string]$To, [string]$Names, [string]$Harness, [string]$Map, [string]$Java, [string]$Out, [string]$Repo)
	$corpusFile = Join-Path $Harness 'knowledge\port-corpus.json'
	if (-not (Test-Path $corpusFile)) { return }
	$cache = Join-Path $env:USERPROFILE '.modaccel\corpus'
	New-Item -ItemType Directory -Force $cache | Out-Null
	$self = (& git -C $Repo remote get-url origin 2>$null | Out-String).Trim()
	$gh = Get-Command gh -ErrorAction SilentlyContinue
	$api = {
		param($path, [switch]$Diff)
		if ($gh) {
			if ($Diff) { & gh api -H 'Accept: application/vnd.github.v3.diff' $path 2>$null | Out-String }
			else { & gh api $path 2>$null | Out-String }
		}
		else {
			$headers = @{ 'User-Agent' = 'modaccel' }
			if ($Diff) { $headers.Accept = 'application/vnd.github.v3.diff' }
			try { (Invoke-WebRequest "https://api.github.com/$path" -Headers $headers -UseBasicParsing).Content } catch { '' }
		}
	}
	$diffs = @()
	foreach ($entry in (Get-Content $corpusFile -Raw | ConvertFrom-Json).repos) {
		if ($self -and $self -match [regex]::Escape($entry.repo)) { continue }
		$a = $entry.branch -replace '\{v\}', $From
		$b = $entry.branch -replace '\{v\}', $To
		$file = Join-Path $cache ("{0}-{1}-{2}.diff" -f ($entry.repo -replace '/', '_'), $From, $To)
		if (-not (Test-Path $file)) {
			# Same names as the repo being ported? A yarn rewrite is wrong in a Mojang-names repo.
			$props = & $api "repos/$($entry.repo)/contents/gradle.properties?ref=$([uri]::EscapeDataString($b))"
			if (-not $props) { continue }
			$text = try { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($props | ConvertFrom-Json).content)) } catch { '' }
			$theirs = if ($text -match '(?m)^\s*yarn_(mappings|version)\s*=') { 'yarn' } else { 'mojmap' }
			if ($theirs -ne $Names) { continue }
			$body = & $api "repos/$($entry.repo)/compare/$([uri]::EscapeDataString($a))...$([uri]::EscapeDataString($b))" -Diff
			if (-not $body -or $body.Length -lt 100) { continue }
			Set-Content -Path $file -Value $body -NoNewline
		}
		$diffs += $file
	}
	if ($diffs.Count -eq 0) {
		Write-Host "learned rewrites: no corpus repo has both $From and $To branches in $Names names" -ForegroundColor DarkGray
		return
	}
	New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
	$learnArgs = @((Join-Path $Harness 'tools\VersionMap.java'), '--map', $Map) + @($diffs | ForEach-Object { '--learn', $_ }) + @('--out', $Out)
	$said = & $Java @learnArgs 2>&1 | Select-Object -First 1
	Write-Host "learned rewrites: $said" -ForegroundColor DarkGray
}

function Get-PortVersionMap {
	param([string]$Repo, [string]$Harness, [string]$From)
	$props = Join-Path $Repo 'gradle.properties'
	if (-not (Test-Path $props)) { return $null }
	$keys = 'minecraft_version|mc_version|minecraftVersion|minecraft'
	$read = { param($text) $m = [regex]::Match($text, "(?m)^\s*(?:$keys)\s*=\s*([\w.\-]+)"); if ($m.Success) { $m.Groups[1].Value } }
	$text = Get-Content $props -Raw
	$to = & $read $text
	if (-not $From) {
		$old = & git -C $Repo show HEAD:gradle.properties 2>$null | Out-String
		if ($old) { $From = & $read $old }
	}
	if (-not $to -or -not $From -or $From -eq $to) {
		Write-Host "version map: no Minecraft version change found (from '$From' to '$to'); rules only" -ForegroundColor DarkGray
		return $null
	}
	$scripts = @(Get-ChildItem $Repo -Recurse -Depth 2 -Include 'build.gradle', 'build.gradle.kts' -ErrorAction SilentlyContinue | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
	$names = if ($text -match '(?m)^\s*yarn_mappings\s*=' -or $scripts -match 'net\.fabricmc:yarn') { 'yarn' } else { 'mojmap' }
	$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
	$jdk25 = Get-ChildItem (Join-Path $Harness '.tools') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($jdk25) { $java = Join-Path $jdk25.FullName 'bin\java.exe' }
	$out = Join-Path $env:USERPROFILE ".modaccel\mappings\map-$names-$From-to-$to.json"
	if (-not (Test-Path $out)) {
		$said = & $java (Join-Path $Harness 'tools\VersionMap.java') $From $to --names $names --out $out 2>&1 | Out-String
		if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) {
			Write-Host "version map: $($said.Trim())" -ForegroundColor Yellow
			return $null
		}
	}
	# Rewrites learned from other mods' real ports of the same jump (VersionMap.java --learn).
	# The excluded corpus repo depends on the target. Never share its learned answer with another
	# target, or reuse rules produced by an older learner after its safety checks change.
	$learned = Join-Path $Repo ".modaccel\learned\$names-$From-to-$to.json"
	$inputs = @((Join-Path $Harness 'tools\VersionMap.java'), (Join-Path $Harness 'knowledge\port-corpus.json'))
	$stale = -not (Test-Path $learned)
	if (-not $stale) {
		$written = (Get-Item $learned).LastWriteTimeUtc
		$stale = @($inputs | Where-Object { (Get-Item $_).LastWriteTimeUtc -gt $written }).Count -gt 0
	}
	if ($stale) {
		Update-LearnedRewrites -From $From -To $to -Names $names -Harness $Harness -Map $out -Java $java -Out $learned -Repo $Repo
	}
	$learnedNote = if (Test-Path $learned) { ", $(@(Get-Content $learned).Count) learned rewrite(s)" } else { '' }
	Write-Host "version map: Minecraft $From -> $to ($names names$learnedNote)" -ForegroundColor DarkGray
	return [pscustomobject]@{ Path = $out; Java = $java; Tool = (Join-Path $Harness 'tools\VersionMap.java'); Learned = $(if (Test-Path $learned) { $learned } else { $null }) }
}

function Get-PortFixSelection {
	# Rules whose match fires on this round's errors. A rule with no match is a data bug,
	# so say so instead of silently skipping it.
	param([object[]]$Rules, [object[]]$Errors)
	$out = foreach ($rule in $Rules) {
		if (-not $rule.match) { Write-Host "fix rules: '$($rule.id)' has no match regex" -ForegroundColor Yellow; continue }
		$hits = @($Errors | Where-Object { "$($_.Message) $($_.Symbol) $($_.File)" -match $rule.match })
		if ($hits.Count -gt 0) { [pscustomobject]@{ Rule = $rule; Hits = $hits } }
	}
	return @($out)
}

function Invoke-PortFix {
	param(
		[string]$Log,
		[string]$Repo,
		[string]$Task = 'compileJava',
		[int]$Max = 5,
		[switch]$DryRun,
		[string]$FixesFile,
		[string]$LogDir,
		[string]$Harness,
		[string]$From
	)
	if (-not (Test-Path $Log)) { throw "no log at $Log" }
	if (-not (Test-Path $FixesFile)) { throw "no fix rules at $FixesFile" }
	$rules = @((Get-Content $FixesFile -Raw | ConvertFrom-Json).rules)
	if ($rules.Count -eq 0) { throw "$FixesFile has no rules" }

	$errors = @(Read-JavacErrors -Log $Log)
	$logRoot = Get-LogRepoRoot -Paths @($errors | ForEach-Object { $_.File })
	if (-not $Repo) { $Repo = if ($logRoot) { $logRoot } else { (Get-Location).Path } }
	if (-not (Test-Path $Repo)) { throw "no repo at $Repo" }
	$Repo = (Resolve-Path $Repo).Path
	$buildRoot = if ($logRoot) { $logRoot } else { $Repo }

	$started = Get-Date
	$before = $errors.Count
	$totalReplacements = 0
	$rounds = 0
	Write-Host ("port -Fix: {0} error(s) in {1}; repo {2}; task {3}; max {4} round(s)" -f $before, $Log, $Repo, $Task, $Max) -ForegroundColor DarkGray

	if ($before -eq 0) {
		# Nothing to fix. Distinguish "this log is already green" from "the build died before the
		# compiler", because only one of those is a result.
		$text = Get-Content $Log -Raw
		if ($text -match 'BUILD SUCCESSFUL') {
			Write-Host 'no source errors: this log is a green build, nothing to fix' -ForegroundColor Green
			return [pscustomobject]@{ Rounds = 0; Replacements = 0; ErrorsBefore = 0; ErrorsAfter = 0; Seconds = 0; Clean = $true; Verified = $true }
		}
		Write-Host 'no source errors and no BUILD SUCCESSFUL: this build died in its script, a dependency or the toolchain, so no rule can fix it' -ForegroundColor Red
		return [pscustomobject]@{ Rounds = 0; Replacements = 0; ErrorsBefore = 0; ErrorsAfter = 0; Seconds = 0; Clean = $false; Verified = $false }
	}

	$envJdk = $env:JAVA_HOME
	$envPath = $env:PATH
	$jdk = Get-PortFixJdk -Repo $Repo -Harness $Harness
	if ($jdk) { $env:JAVA_HOME = $jdk; $env:PATH = "$jdk\bin;$env:PATH" }
	else { Write-Host 'no JDK found for this wrapper; using this shell''s JAVA_HOME' -ForegroundColor Yellow }

	try {
		$ranBuild = $false
		$lastCode = 0
		# The map tool ships next to this script; -Harness is the repo being built, which may not have it.
		$versionMap = Get-PortVersionMap -Repo $Repo -Harness (Split-Path $PSScriptRoot -Parent) -From $From
		$currentLog = $Log
		for ($round = 1; $round -le $Max; $round++) {
			if ($errors.Count -eq 0 -and $ranBuild) { break }
			$roundClock = [Diagnostics.Stopwatch]::StartNew()
			$selection = if ($errors.Count -gt 0) { @(Get-PortFixSelection -Rules $rules -Errors $errors) } else { @() }
			if ($errors.Count -gt 0 -and $selection.Count -eq 0 -and -not $versionMap) {
				Write-Host ("round {0}: no rule matches these errors ({1}s); stopping" -f $round, [math]::Round($roundClock.Elapsed.TotalSeconds, 1)) -ForegroundColor Yellow
				break
			}
			$applied = @()
			$replacements = 0
			foreach ($entry in $selection) {
				$rule = $entry.Rule
				if ($rule.scope -eq 'log') {
					$files = @($entry.Hits | ForEach-Object {
							$relative = Get-RepoRelative -File $_.File -FromRoot $buildRoot
							if ($relative) { Join-Path $Repo $relative }
						} | Select-Object -Unique | Where-Object { Test-Path $_ })
				}
				else {
					# Every source tree in the repo, not just src/: a multi-module project keeps its
					# code in common/src, client/src and friends.
					$files = @([System.IO.Directory]::EnumerateFiles($Repo, '*.java', [System.IO.SearchOption]::AllDirectories) |
							Where-Object { $_ -notmatch '\\(build|\.gradle|\.git|run)\\' })
				}
				if ($files.Count -eq 0) { continue }
				$count = 0
				foreach ($file in $files) {
					$text = [System.IO.File]::ReadAllText($file)
					if ($rule.when -and $text -notmatch $rule.when) { continue }
					$updated = $text
					$hits = 0
					if ($rule.apply.literal) {
						$from = [string]$rule.apply.literal.from
						$hits = ([regex]::Matches($updated, [regex]::Escape($from))).Count
						if ($hits -gt 0) { $updated = $updated.Replace($from, [string]$rule.apply.literal.to) }
					}
					elseif ($rule.apply.find) {
						$hits = ([regex]::Matches($updated, [string]$rule.apply.find)).Count
						if ($hits -gt 0) { $updated = [regex]::Replace($updated, [string]$rule.apply.find, [string]$rule.apply.replace) }
					}
					if ($hits -eq 0) { continue }
					if ($rule.apply.ensureImports) {
						$updated = Add-MissingImports -Text $updated -Imports @($rule.apply.ensureImports)
					}
					if ($updated -ne $text) {
						$count += $hits
						if (-not $DryRun) { [System.IO.File]::WriteAllText($file, $updated) }
					}
				}
				if ($count -gt 0) {
					$applied += ("{0} x{1}" -f $rule.id, $count)
					$replacements += $count
				}
			}
			# Then the generated map: moved classes everywhere, renamed members where javac pointed.
			# A rule that already rewrote a call leaves no old name on that line, so the two never fight.
			if ($versionMap -and $errors.Count -gt 0) {
				$mapArgs = @($versionMap.Tool, '--map', $versionMap.Path, '--fix', $currentLog, '--repo', $Repo)
				if ($versionMap.Learned) { $mapArgs += @('--rules', $versionMap.Learned) }
				if ($DryRun) { $mapArgs += '--dry-run' }
				$said = & $versionMap.Java @mapArgs 2>&1 | Out-String
				$found = [regex]::Match($said, 'map-fix: (\d+) replacement')
				if ($found.Success -and [int]$found.Groups[1].Value -gt 0) {
					$applied += ("version-map x{0}" -f $found.Groups[1].Value)
					$replacements += [int]$found.Groups[1].Value
				}
				elseif (-not $found.Success) { Write-Host "version map: $($said.Trim())" -ForegroundColor Yellow }
			}
			$totalReplacements += $replacements
			if ($replacements -eq 0 -and $ranBuild) {
				Write-Host ("round {0}: nothing left to change ({1}s); stopping" -f $round, [math]::Round($roundClock.Elapsed.TotalSeconds, 1)) -ForegroundColor Yellow
				break
			}
			if ($replacements -eq 0 -and ($selection.Count -gt 0 -or $versionMap)) {
				Write-Host ("round {0}: no applicable source change; checking the current build against the supplied log" -f $round) -ForegroundColor DarkGray
			}
			$rounds = $round
			if ($DryRun) {
				Write-Host ("round {0} (dry run): {1} | {2} replacement(s), 0s, {3} error(s) left - not rebuilt" -f $round, ($applied -join ', '), $replacements, $errors.Count) -ForegroundColor Yellow
				break
			}

			$buildLog = Join-Path $LogDir ("port-fix-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
			$taskArgs = @($Task -split '\s+' | Where-Object { $_ }) + @('--console=plain')
			# Gradle takes the project directory from the working directory, not from the wrapper's
			# own location, so build inside the repo the log came from.
			Push-Location $Repo
			try { & (Join-Path $Repo 'gradlew.bat') @taskArgs *> $buildLog; $code = $LASTEXITCODE }
			finally { Pop-Location }
			$ranBuild = $true
			$lastCode = $code
			$errors = @(Read-JavacErrors -Log $buildLog)
			# New diagnostics name the current checkout, even when the supplied log came from a copy.
			$buildRoot = $Repo
			$currentLog = $buildLog
			$seconds = [math]::Round($roundClock.Elapsed.TotalSeconds, 1)
			$verdict = if ($code -eq 0) { 'green' } else { 'failed' }
			Write-Host ("round {0}: {1} | {2} replacement(s), {3}s, {4} error(s) left ({5})" -f $round, ($applied -join ', '), $replacements, $seconds, $errors.Count, $verdict) -ForegroundColor $(if ($errors.Count -eq 0 -and $code -eq 0) { 'Green' } else { 'Yellow' })
			Write-Host ("        log: {0}" -f $buildLog) -ForegroundColor DarkGray
			if ($errors.Count -eq 0) { break }
		}
	}
	finally {
		$env:JAVA_HOME = $envJdk
		$env:PATH = $envPath
	}

	if ($errors.Count -eq 0 -and $lastCode -ne 0) {
		# A build that fails with no source error died in its script, a dependency or the toolchain.
		# That is not a pass, and no rule can fix it.
		Write-Host 'the last build failed without reporting a source error: see the log above (build script, dependency or toolchain)' -ForegroundColor Red
	}
	if ($errors.Count -gt 0) {
		# Name what no rule covers, so the next human/agent edit is aimed rather than guessed.
		$left = @($errors | ForEach-Object {
				$what = if ($_.Symbol) { $_.Symbol } else { $_.Message }
				$what
			} | Group-Object | Sort-Object Count -Descending | Select-Object -First 5)
		Write-Host 'not covered by any rule:' -ForegroundColor Yellow
		foreach ($group in $left) { Write-Host ("  {0} x{1}" -f $group.Name, $group.Count) -ForegroundColor DarkGray }
	}
	$clean = ($errors.Count -eq 0 -and $lastCode -eq 0 -and $ranBuild)
	$total = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
	Write-Host ("port -Fix: {0} round(s), {1} replacement(s), {2} error(s) left, {3}s total" -f $rounds, $totalReplacements, $errors.Count, $total) -ForegroundColor $(if ($clean) { 'Green' } else { 'Yellow' })
	return [pscustomobject]@{
		Rounds       = $rounds
		Replacements = $totalReplacements
		ErrorsBefore = $before
		ErrorsAfter  = $errors.Count
		Seconds      = $total
		Clean        = $clean
		Verified     = ($ranBuild -and $lastCode -eq 0)
	}
}



