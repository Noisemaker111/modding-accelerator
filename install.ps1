#requires -Version 7.0
<#
  install.ps1 - put Modding Accelerator into an existing Fabric mod repo.

  Usage (from the lab folder):
    .\install.ps1 -Target C:\path\to\your\mod

  It copies the harness, adds the Gradle wiring once (marked block), creates a game test
  source set with a starter test, and leaves your mod untouched. Re-running it is safe.
#>
param(
	[Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path $Target).Path

function Fail([string]$message) {
	Write-Host "install: $message" -ForegroundColor Red
	exit 1
}

function Copy-HarnessTools {
	# Every loader uses the same port commands. Ship their Java tools and knowledge together;
	# learned rules belong to the target's .modaccel cache and must never be copied between mods.
	$tools = Join-Path $root 'tools'
	$knowledge = Join-Path $root 'knowledge'
	New-Item -ItemType Directory -Force $tools, $knowledge | Out-Null
	foreach ($file in (Get-ChildItem (Join-Path $here 'tools') -File | Where-Object { $_.Extension -in '.ps1', '.java' })) {
		Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $tools $file.Name) -Force
	}
	foreach ($file in (Get-ChildItem (Join-Path $here 'knowledge') -File | Where-Object { $_.Extension -in '.json', '.md' })) {
		Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $knowledge $file.Name) -Force
	}
}

# NeoForge / ModDevGradle: the game jar, the mappings and the runs all come from the plugin, so the
# classpath to record is the main source set's runtime classpath and the warm client is one
# `runs { clientWarm { } }` entry. This is checked before the Forge branch: a NeoForge repo also
# carries src/main/resources/META-INF/neoforge.mods.toml, which the Forge branch would claim.
$neoBuild = $null
foreach ($candidate in @('build.gradle', 'build.gradle.kts')) {
	$candidate = Join-Path $root $candidate
	if ((Test-Path $candidate) -and ((Get-Content $candidate -Raw) -match 'net\.neoforged\.moddev')) { $neoBuild = $candidate; break }
}
if (-not $neoBuild) {
	# Some setups declare the plugin in the settings file; the wiring still goes in the build script.
	$declared = @('settings.gradle', 'settings.gradle.kts') | ForEach-Object { Join-Path $root $_ } |
		Where-Object { (Test-Path $_) -and ((Get-Content $_ -Raw) -match 'net\.neoforged\.moddev') } | Select-Object -First 1
	if ($declared) {
		$neoBuild = @('build.gradle', 'build.gradle.kts') | ForEach-Object { Join-Path $root $_ } |
			Where-Object { Test-Path $_ } | Select-Object -First 1
	}
}

if ($neoBuild) {
	$neoKotlin = $neoBuild.EndsWith('.kts')
	$neoName = Split-Path -Leaf $neoBuild
	$neoText = Get-Content $neoBuild -Raw
	if ($neoText -match 'Modding Accelerator \(NeoForge lane\)') {
		Write-Host 'install: NeoForge lane block already present, leaving it alone' -ForegroundColor DarkGray
	}
	else {
		$labClasspath = if ($neoKotlin) {
@'
tasks.register("labClasspath") {
	val cp = sourceSets["main"].runtimeClasspath
	val out = rootProject.file(".modaccel/classpath.txt")
	doLast {
		out.parentFile.mkdirs()
		out.writeText(cp.asPath)
	}
}
'@
		}
		else {
@'
tasks.register("labClasspath") {
	def cp = sourceSets.main.runtimeClasspath
	def out = rootProject.file(".modaccel/classpath.txt")
	doLast {
		out.parentFile.mkdirs()
		out.text = cp.asPath
	}
}
'@
		}
		# A repo that already runs its client has a clientWarm run; registering a second one of the
		# same name is a Gradle error, so the run block is added only when the file has none.
		$hasRun = $neoText -match '(?m)\bclientWarm\b'
		$runBlock = if ($hasRun) { '' }
		elseif ($neoKotlin) {
@'

neoForge {
	runs {
		create("clientWarm") {
			client()
			gameDirectory.set(file("run"))
			systemProperty("prismlab.lab.port", "27399")
			systemProperty("modlab.probe", "charge")
			jvmArgument("-XX:TieredStopAtLevel=1")
			disableIdeRun()
		}
	}
}
'@
		}
		else {
@'

neoForge {
	runs {
		clientWarm {
			client()
			gameDirectory = file('run')
			systemProperty 'prismlab.lab.port', '27399'
			systemProperty 'modlab.probe', 'charge'
			jvmArgument '-XX:TieredStopAtLevel=1'
			disableIdeRun()
		}
	}
}
'@
		}
		$block = @"

// Added by Modding Accelerator (NeoForge lane). Safe to delete as one block.
// ModDevGradle builds the game jar and the mappings, so a compile classpath is one property away.
// .modaccel/classpath.txt is the same list the client's own -cp has, which the warm client's
// one-time capture starts from.
$labClasspath$runBlock
// === end modding accelerator ===

"@
		Add-Content -Path $neoBuild -Value $block
		$what = if ($hasRun) { 'labClasspath' } else { 'labClasspath and the clientWarm run' }
		Write-Host "install: added the NeoForge lane block ($what) to $neoName" -ForegroundColor DarkGray
	}
	New-Item -ItemType Directory -Force (Join-Path $root 'tools') | Out-Null
	New-Item -ItemType Directory -Force (Join-Path $root '.modaccel') | Out-Null
	Copy-Item (Join-Path $here 'modaccel.ps1') $root -Force
	if (Test-Path (Join-Path $here 'modaccel.cmd')) { Copy-Item (Join-Path $here 'modaccel.cmd') $root -Force }
	Copy-HarnessTools
	Write-Host 'install: harness copied (modaccel.ps1, modaccel.cmd, tools/)'

	# 3. the bridge jar: screenshots and scripted clicks for a client whose own mod has no control
	#    socket. It is one more mod on the -Dfml.modFolders list, so this repo's sources, build
	#    files and launch scripts stay exactly as they are; modaccel appends the entry at launch.
	$bridgeDest = Join-Path $root '.modaccel\bridge.jar'
	$bridgeLib = Join-Path $here 'bench\bridge\build\libs'
	$bridgeSource = Get-ChildItem $bridgeLib -Filter 'modlabbridge-*.jar' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if (-not $bridgeSource -and (Test-Path (Join-Path $here 'bench\bridge\gradlew.bat'))) {
		Write-Host 'install: building the bridge jar once (about 15s)...' -ForegroundColor DarkGray
		& (Join-Path $here 'bench\bridge\gradlew.bat') jar --console=plain *> (Join-Path $root '.modaccel\bridge-build.log')
		$bridgeSource = Get-ChildItem $bridgeLib -Filter 'modlabbridge-*.jar' -ErrorAction SilentlyContinue |
			Sort-Object LastWriteTime -Descending | Select-Object -First 1
	}
	if ($bridgeSource) {
		Copy-Item $bridgeSource.FullName $bridgeDest -Force
		$shots = Join-Path $root 'build\run\warm\shots'
		$launch = @(
			"-Dfml.modFolders=`"<the entries this run already has>;modlabbridge%%$bridgeDest`"",
			'   (each %% above is written %%%% inside a .cmd)',
			'-Dmodlabbridge.port=27699',
			"   (modaccel keeps 27699 unless something holds it, then the next free one)",
			"-Dmodlabbridge.shots=$shots",
			"-Dmodlab.root=$root",
			'-Dprismlab.lab.port=',
			'   (only when this repo has no dev\modlab client of its own)'
		)
		Set-Content (Join-Path $root '.modaccel\bridge-launch.txt') ($launch -join "`r`n")
		Write-Host "install: bridge jar copied to .modaccel\bridge.jar ($($bridgeSource.Name))"
		Write-Host 'install: modaccel adds this to the warm client launch itself, no flags by hand:'
		$launch | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
	}
	else {
		Write-Host 'install: no bridge jar (bench\bridge and Gradle are both unavailable), so screenshots need a control mod in this repo.' -ForegroundColor Yellow
	}
	Write-Host ''
	Write-Host 'install: done. Next:' -ForegroundColor Green
	Write-Host '  1. .\gradlew labClasspath          (writes .modaccel\classpath.txt; first run fetches the game)'
	Write-Host '  2. .\modaccel fast                 compile + pure tests, no Gradle'
	Write-Host ''
	Write-Host 'install: the warm client runs from ModDevGradle''s recipe; one Gradle pass writes it (about 90s, once):' -ForegroundColor Green
	Write-Host '  a. .\gradlew prepareClientWarmRun   writes build\moddev\clientWarmRunVmArgs.txt and clientWarmRunProgramArgs.txt'
	Write-Host '     (.\modaccel ui does this pass itself when the recipe is missing, then launches)'
	Write-Host '  b. .\modaccel ui                    launches java from that recipe (no Gradle), adds the bridge and takes a screenshot'
	Write-Host '     The classpath it replays is .modaccel\classpath.txt (written in step 1); no hand capture is needed.'
	Write-Host '     Re-run .\gradlew prepareClientWarmRun after changing dependencies so the recipe matches the build.'
	exit 0
}
$modJsonPath = Join-Path $root 'src/main/resources/fabric.mod.json'

# Multi-project repo (common/ + fabric/, or polymer-core/ + polymer-common/ ...): the mod's sources
# live in subprojects, so the root has no mod file of its own. Each Java subproject writes its source
# folders and compile classpath; the root task merges them for the fast lane. When a repo builds
# both Fabric and (Neo)Forge, the Fabric side is the one recorded: one classpath, one game jar.
$settingsFile = @('settings.gradle', 'settings.gradle.kts') | ForEach-Object { Join-Path $root $_ } |
	Where-Object { Test-Path $_ } | Select-Object -First 1
$rootModFile = (Test-Path $modJsonPath) -or (Test-Path (Join-Path $root 'src/main/resources/META-INF/mods.toml')) -or
	(Test-Path (Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml'))
$includes = if ($settingsFile) { [regex]::Matches((Get-Content $settingsFile -Raw), '(?m)^\s*include\b').Count } else { 0 }
# Traverse-style repos keep fabric.mod.json at the root but every class in subprojects, so an empty
# root src/main/java counts as multi-project too.
$rootJava = @(Get-ChildItem (Join-Path $root 'src/main/java') -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue).Count
if ((-not $rootModFile -or $rootJava -eq 0) -and $includes -gt 0 -and -not (Test-Path (Join-Path $root 'stonecutter.gradle.kts'))) {
	$groovy = Join-Path $root 'build.gradle'
	if (-not (Test-Path $groovy)) { Fail 'multi-project repo without a Groovy root build.gradle; Kotlin-DSL roots are not wired yet' }
	if (Select-String -Path $groovy -Pattern 'Modding Accelerator \(multi-project lane\)' -Quiet) {
		Write-Host 'install: multi-project lane block already present' -ForegroundColor DarkGray
	}
	else {
		@'

// === Added by Modding Accelerator (multi-project lane). Safe to delete as one block. ===
// Every Java subproject writes its source folders and compile classpath; labClasspath merges
// them into .modaccel\classpath.txt and .modaccel\sources.txt for the fast lane.
def modaccelSkip = { p -> rootProject.findProject(':fabric') != null && p.name in ['neoforge', 'forge'] }
allprojects { p ->
    p.plugins.withId('java') {
        if (modaccelSkip(p)) return
        p.tasks.register('labClasspathPart') { t ->
            def sets = p.sourceSets.matching { it.name in ['main', 'client', 'test'] }
            def cp = p.files({ sets.collect { it.compileClasspath } })
            def src = p.files({ sets.collect { it.java.srcDirs } })
            def out = p.file('build/modaccel-part.txt')
            // Merge order, highest first: a subproject with an access widener compiles against its own
            // widened Minecraft jar, and one that depends on other subprojects (fabric on common)
            // resolves the dependency versions that actually ship.
            def widened = p.extensions.findByName('loom')?.accessWidenerPath?.isPresent() ?: false
            def rank = (widened ? 1000 : 0) +
                (p.configurations.findByName('compileClasspath')?.allDependencies?.withType(ProjectDependency)?.size() ?: 0)
            t.outputs.upToDateWhen { false }
            t.doLast {
                out.parentFile.mkdirs()
                out.text = "RANK ${rank}\n" +
                    'SRC\n' + src.files.findAll { it.isDirectory() }*.absolutePath.join('\n') +
                    '\nCP\n' + cp.files*.absolutePath.join('\n')
            }
        }
    }
}
tasks.register('labClasspath') { t ->
    t.dependsOn { allprojects.collect { it.tasks.findByName('labClasspathPart') }.findAll { it != null } }
    def parts = allprojects.findAll { !modaccelSkip(it) }.collect { it.file('build/modaccel-part.txt') }
    def repo = rootProject.projectDir.absolutePath
    def lab = rootProject.file('.modaccel')
    t.doLast {
        def src = new LinkedHashSet<String>()
        def cp = new LinkedHashSet<String>()
        // One class can sit in two subprojects' jars (two Fabric API versions, a widened and a plain
        // Minecraft); javac takes the first, so the highest-ranked subproject's classpath leads.
        def rankOf = { f -> (f.readLines()[0] - 'RANK ').toInteger() }
        parts.findAll { it.exists() }.sort { -rankOf(it) }.each { f ->
            def into = null
            f.readLines().drop(1).each { line ->
                if (line == 'SRC') { into = src } else if (line == 'CP') { into = cp } else if (line) { into << line }
            }
        }
        // A subproject's own build output is compiled from source here, so it stays off the classpath.
        def sep = File.separator
        cp.removeAll { it.startsWith(repo) && it.contains("${sep}build${sep}") }
        def own = rootProject.file('modaccel-tests/java')
        if (own.isDirectory()) { src << own.absolutePath }
        lab.mkdirs()
        new File(lab, 'classpath.txt').text = cp.findAll { new File(it).exists() }.join(File.pathSeparator)
        new File(lab, 'sources.txt').text = src.join('\n')
    }
}
// === end modding accelerator ===
'@ | Add-Content $groovy -Encoding utf8
		Write-Host 'install: added the multi-project lane block (labClasspath) to build.gradle'
	}
	New-Item -ItemType Directory -Force (Join-Path $root 'tools') | Out-Null
	New-Item -ItemType Directory -Force (Join-Path $root '.modaccel') | Out-Null
	Copy-Item (Join-Path $here 'modaccel.ps1') $root -Force
	if (Test-Path (Join-Path $here 'modaccel.cmd')) { Copy-Item (Join-Path $here 'modaccel.cmd') $root -Force }
	Copy-HarnessTools
	# The lane brings its own JUnit, so a starter pure test compiles even when no subproject has one.
	# It lives outside every Gradle source set, so the mod's own build never sees it.
	$pureDir = Join-Path $root 'modaccel-tests/java/dev/modlab'
	if (-not (Test-Path (Join-Path $pureDir 'StarterTest.java'))) {
		New-Item -ItemType Directory -Force $pureDir | Out-Null
		@'
package dev.modlab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The fast lane runs tests tagged pure: no Minecraft class, about a second. Replace with a real check. */
@Tag("pure")
public class StarterTest {
	@Test
	public void arithmetic() {
		assertEquals(4, 2 + 2, "starter test: replace this with a real check");
	}
}
'@ | Set-Content (Join-Path $pureDir 'StarterTest.java')
	}
	$ignore = Join-Path $root '.gitignore'
	$current = if (Test-Path $ignore) { Get-Content $ignore -Raw } else { '' }
	$add = @('.modaccel/', '.runs/', '.tools/', '.lab/') | Where-Object { $current -notmatch [regex]::Escape($_) }
	if ($add) { Add-Content $ignore ("`n# modding accelerator`n" + ($add -join "`n")) }
	Write-Host 'install: harness copied (modaccel.ps1, tools/), starter pure test in modaccel-tests/java/dev/modlab'
	Write-Host ''
	Write-Host 'install: done. Next:' -ForegroundColor Green
	Write-Host '  .\modaccel fast    first run asks Gradle once for every subproject''s sources and classpath, then compiles without Gradle'
	Write-Host 'Multi-project repos get the fast lane and port tooling; the warm client is single-project only for now.'
	exit 0
}

$modsToml = @(
	(Join-Path $root 'src/main/resources/META-INF/mods.toml'),
	(Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml')
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not (Test-Path $modJsonPath) -and $modsToml) {
	# Forge / NeoForge: no Fabric entrypoints, but the fast lane only needs a classpath.
	$groovy = Join-Path $root 'build.gradle'
	if (-not (Test-Path $groovy)) {
		Fail "Forge repo without a Groovy build.gradle; Kotlin-DSL Forge repos are not wired yet"
	}
	$marker = '// Added by Modding Accelerator (Forge lane)'
	if (-not (Select-String -Path $groovy -Pattern 'Modding Accelerator \(Forge lane\)' -Quiet)) {
		@"

$marker
// Safe to delete as one block. Writes the compile + test-runtime classpath the fast lane needs,
// and the source patterns this project excludes (another loader's sources).
tasks.register("labClasspath") {
    doLast {
        def out = file(".modaccel/classpath.txt")
        out.parentFile.mkdirs()
        out.text = (sourceSets.main.compileClasspath + sourceSets.test.runtimeClasspath).asPath
        def ex = file(".modaccel/excludes.txt")
        ex.text = sourceSets.main.java.excludes.join("\n")
    }
}
"@ | Add-Content $groovy -Encoding utf8
		Write-Host 'install: added the Forge lane block (labClasspath) to build.gradle'
	}
	else { Write-Host 'install: Forge lane block already present' }
	New-Item -ItemType Directory -Force (Join-Path $root 'tools') | Out-Null
	New-Item -ItemType Directory -Force (Join-Path $root '.modaccel') | Out-Null
	Copy-Item (Join-Path $here 'modaccel.ps1') $root -Force
	Copy-HarnessTools
	Write-Host 'install: harness copied (modaccel.ps1, tools/)'
	# The Forge twin of the bridge: a mod jar the client loads from its own game folder (the
	# ForgeGradle game dir is the repo root), so the first launch copies it into <repo>\mods.
	$forgeBridgeLib = Join-Path $here 'bench\forgebridge\build\libs'
	$forgeBridgeSource = Get-ChildItem $forgeBridgeLib -Filter 'modlabbridge-forge-*.jar' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if ($forgeBridgeSource) {
		Copy-Item $forgeBridgeSource.FullName (Join-Path $root '.modaccel\bridge-forge.jar') -Force
		Write-Host "install: Forge bridge jar copied to .modaccel\bridge-forge.jar ($($forgeBridgeSource.Name))"
	}
	else {
		Write-Host 'install: no Forge bridge jar yet. Build it once in the harness: bench\forgebridge\build.ps1 (needs an earlier gradlew classes here).' -ForegroundColor Yellow
	}
	$forgeCapture = Join-Path $root '.modaccel\forge.args'
	Write-Host ''
	Write-Host 'install: done. Next:'
	Write-Host '  1. .\gradlew labClasspath      (once, or let the first run do it)'
	Write-Host '     .\modaccel fast             compile + pure tests, no Gradle'
	if (Test-Path $forgeCapture) {
		Write-Host '  2. .\modaccel ui               replays the captured launch (no Gradle), bridge takes the screenshot'
	}
	else {
		Write-Host '  2. one-time capture of the launch line (ForgeGradle writes no recipe, so you need a running client):'
		Write-Host '       a. .\gradlew runClient      (leave it on the title screen)'
		Write-Host '       b. .\tools\capture-launch.ps1 -Match forgeclientuserdev -Out .modaccel\forge.args'
		Write-Host '       c. close that client, then .\modaccel ui (about 45s to a warm client, then a PNG)'
	}
	Write-Host ''
	Write-Host 'Fast lane, port tooling and .\modaccel ui work on Forge: the client comes from the captured'
	Write-Host 'launch line plus a bridge jar in mods\. No hot reload: an edit means stop and start again.'
	exit 0
}

if (-not (Test-Path $modJsonPath)) { Fail "no src/main/resources/fabric.mod.json in $root - is this a Fabric mod repo?" }
$stonecutter = Test-Path (Join-Path $root 'stonecutter.gradle.kts')
$gradlePath = Join-Path $root 'build.gradle'
$kotlinDsl = $false
if (Test-Path $gradlePath) {
	# single-project repo with a Groovy buildscript
}
elseif (Test-Path (Join-Path $root 'build.gradle.kts')) {
	$gradlePath = Join-Path $root 'build.gradle.kts'
	$kotlinDsl = $true
}
elseif ($stonecutter) {
	# Stonecutter repos keep one buildscript per node; there is nothing to append to at the root.
	$gradlePath = $null
	$kotlinDsl = $true
}
else { Fail "no build.gradle or build.gradle.kts in $root" }
if (-not (Test-Path (Join-Path $root 'gradlew.bat'))) { Fail "no gradlew.bat in $root" }

$modJson = Get-Content $modJsonPath -Raw | ConvertFrom-Json
$modId = $modJson.id
# Client-only mods have no main entrypoint; fall back to the client one, then to the mod id.
$entry = @($modJson.entrypoints.main) + @($modJson.entrypoints.client) |
	Where-Object { $_ -is [string] -and $_.Trim() } | Select-Object -First 1
if ($entry) {
	$parts = $entry -split '\.'
	$package = ($parts[0..([math]::Max(0, $parts.Length - 2))]) -join '.'
}
else { $package = "com.$modId" }

# A Stonecutter repo keeps its versions per node (versions/<node>/gradle.properties); the root file
# only holds what every node shares.
function Read-Props([string]$path) {
	$map = @{}
	if (Test-Path $path) {
		foreach ($line in Get-Content $path) {
			$kv = [regex]::Match($line, '^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$')
			if ($kv.Success) { $map[$kv.Groups[1].Value] = $kv.Groups[2].Value }
		}
	}
	return $map
}

$node = $null
if ($stonecutter) {
	$sc = Get-Content (Join-Path $root 'stonecutter.gradle.kts') -Raw
	$active = [regex]::Match($sc, '(?m)^\s*stonecutter\s+active\s+"([^"]+)"')
	if (-not $active.Success) { $active = [regex]::Match($sc, '(?m)^\s*vcsVersion\s*=\s*"([^"]+)"') }
	if (-not $active.Success) { Fail "Stonecutter repo without a 'stonecutter active \`"<version>\`"' line in stonecutter.gradle.kts" }
	$node = $active.Groups[1].Value
	if (-not (Test-Path (Join-Path $root "versions/$node/gradle.properties"))) {
		Fail "no versions/$node/gradle.properties - is '$node' a node this repo builds?"
	}
	# The node's buildscript is declared in settings.gradle.kts:
	#   versions("26.1", "26.2", "26.3").buildscript("build.unobfuscated.gradle.kts")
	$settingsPath = Join-Path $root 'settings.gradle.kts'
	$gradlePath = $null
	if (Test-Path $settingsPath) {
		foreach ($group in [regex]::Matches((Get-Content $settingsPath -Raw), 'versions?\(([^)]*)\)\.buildscript\("([^"]+)"\)')) {
			if ($group.Groups[1].Value -match ('"' + [regex]::Escape($node) + '"')) {
				$gradlePath = Join-Path $root $group.Groups[2].Value
				break
			}
		}
	}
	$kotlinDsl = $true
}

$all = Read-Props (Join-Path $root 'gradle.properties')
if ($node) {
	$nodeProps = Read-Props (Join-Path $root "versions/$node/gradle.properties")
	foreach ($key in $nodeProps.Keys) { $all[$key] = $nodeProps[$key] }
}
$mc = if ($all['minecraft_version']) { $all['minecraft_version'] } elseif ($node) { $node } else { 'unknown' }
$scriptText = if ($gradlePath -and (Test-Path $gradlePath)) { Get-Content $gradlePath -Raw } else { '' }
# Loom 1.18 replaced property()/vmArg()/ideConfigGenerated() with Gradle properties, and Gradle's
# Kotlin DSL now fails the build on those deprecations, so which block to emit depends on it.
$loomText = $scriptText
if ($stonecutter -and (Test-Path (Join-Path $root 'stonecutter.gradle.kts'))) {
	$loomText += "`n" + (Get-Content (Join-Path $root 'stonecutter.gradle.kts') -Raw)
}
if ($all['loom_version']) { $loomText = $all['loom_version'] }
$loomMajor = 0
$loomMinor = 0
$loomFound = [regex]::Match($loomText, 'fabric-loom[^"\r\n]*?(\d+)\.(\d+)')
if ($loomFound.Success) { $loomMajor = [int]$loomFound.Groups[1].Value; $loomMinor = [int]$loomFound.Groups[2].Value }
$jbr = Get-ChildItem (Join-Path $here '.tools') -Directory -Filter 'jbr-*' -ErrorAction SilentlyContinue |
	Sort-Object Name -Descending | Select-Object -First 1

Write-Host "install: mod '$modId' package $package (Minecraft $mc$(if ($node) { ", node $node" }))" -ForegroundColor DarkGray

# 1. harness files
foreach ($file in @('modaccel.ps1', 'modaccel.cmd')) {
	$dest = Join-Path $root $file
	if (Test-Path $dest) {
		Write-Host "install: $file already present, leaving it" -ForegroundColor DarkGray
		continue
	}
	Copy-Item (Join-Path $here $file) $dest
}
$tools = Join-Path $root 'tools'
New-Item -ItemType Directory -Force $tools | Out-Null
Copy-HarnessTools

# generic client bridge: the socket, screenshots and the block probe.
# It has to land in a source set the node actually compiles. A repo that splits its client code
# says so with splitEnvironmentSourceSets() or a source set called "client"; Stonecutter repos like
# Diorama keep client classes in src/main and build no client source set at all.
$splitClient = $scriptText -match 'splitEnvironmentSourceSets\s*\(' -or
	$scriptText -match 'sourceSets[^;]{0,200}?["'']client["'']' -or
	(Test-Path (Join-Path $root 'src/client/java')) -and
	@(Get-ChildItem (Join-Path $root 'src/client/java') -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -notmatch '\\dev\\modlab\\' }).Count -gt 0
$clientSource = if ($splitClient) { 'src/client/java' } else { 'src/main/java' }
# These sources are written against 26.x client classes (net.minecraft.client.Minecraft,
# net.minecraft.client.Screenshot, net.minecraft.client.input.MouseButtonInfo, ...), which only
# exist under the 26.x Mojang-mapped names. Copied into any other version they fail that repo's
# compile before the user has written a line, so the copy is gated on the version they fit.
$bridgeSupported = $mc -match '^26\.'
$bridgeEntrypoint = 'dev.modlab.client.LabEntry'
if ($bridgeSupported) {
	$bridge = Join-Path $root "$clientSource/dev/modlab"
	New-Item -ItemType Directory -Force $bridge | Out-Null
	Copy-Item (Join-Path $here 'src/client/java/dev/modlab/*') $bridge -Recurse -Force
	Write-Host "install: bridge classes in $clientSource/dev/modlab" -ForegroundColor DarkGray

	# one entrypoint so the bridge starts with the client
	# A library mod (Traverse) has no entrypoints object at all; create it before adding to it.
	if (-not $modJson.entrypoints) {
		$modJson | Add-Member -NotePropertyName entrypoints -NotePropertyValue ([pscustomobject]@{})
	}
	if (-not $modJson.entrypoints.client) { $modJson.entrypoints | Add-Member -NotePropertyName client -NotePropertyValue @() }
	if ($modJson.entrypoints.client -notcontains $bridgeEntrypoint) {
		$modJson.entrypoints.client = @($modJson.entrypoints.client) + $bridgeEntrypoint
	}
	$modJson | ConvertTo-Json -Depth 10 | Set-Content $modJsonPath
}
else {
	# An earlier install of the same repo on 26.x may have left the bridge behind; take it back out
	# so re-running the installer is still safe and the repo compiles.
	foreach ($old in @("$clientSource/dev/modlab/client", 'src/main/java/dev/modlab/client', 'src/client/java/dev/modlab/client')) {
		$oldPath = Join-Path $root $old
		if (-not (Test-Path $oldPath)) { continue }
		foreach ($name in @('HotUi.java', 'LabControl.java', 'LabEntry.java')) {
			$file = Join-Path $oldPath $name
			if (Test-Path $file) { Remove-Item $file -Force }
		}
		if (@(Get-ChildItem $oldPath -Force -ErrorAction SilentlyContinue).Count -eq 0) { Remove-Item $oldPath -Force }
		if (@(Get-ChildItem (Split-Path -Parent $oldPath) -Force -ErrorAction SilentlyContinue).Count -eq 0) {
			Remove-Item (Split-Path -Parent $oldPath) -Force
		}
		Write-Host "install: removed the 26.x bridge copy an earlier install left in $old" -ForegroundColor DarkGray
	}
	# The entrypoint would start a class that is not there any more, which makes the client fail.
	$bridgeEntrypoints = @()
	if ($modJson.entrypoints) { $bridgeEntrypoints = @($modJson.entrypoints.client | Where-Object { $_ }) }
	if ($bridgeEntrypoints -contains $bridgeEntrypoint) {
		$kept = @($bridgeEntrypoints | Where-Object { $_ -ne $bridgeEntrypoint })
		if ($kept.Count -eq 0) { $modJson.entrypoints.PSObject.Properties.Remove('client') }
		else { $modJson.entrypoints.client = $kept }
		if (@($modJson.entrypoints.PSObject.Properties).Count -eq 0) { $modJson.PSObject.Properties.Remove('entrypoints') }
		$modJson | ConvertTo-Json -Depth 10 | Set-Content $modJsonPath
		Write-Host "install: removed the $bridgeEntrypoint client entrypoint from fabric.mod.json" -ForegroundColor DarkGray
	}
	Write-Host ''
	Write-Host "install: Minecraft $mc is not 26.x, so the client bridge is NOT installed here: its sources call 26.x client classes and would not compile in this repo." -ForegroundColor Yellow
	Write-Host 'install: you still get the fast lane (.\modaccel fast: compile + pure tests) and the world tier (.\modaccel world: a real server).'
	Write-Host 'install: you do NOT get screenshots or scripted clicks. .\modaccel ui, node, look, suite and mp all need that bridge socket, so they will not work until this repo is on 26.x.'
	Write-Host ''
}

# 2. gradle wiring, once, in a marked block
$marker = '// === modding accelerator ==='
$gradle = if ($gradlePath) { Get-Content $gradlePath -Raw } else { '' }
# A repo that already has a gametest source set (Stonecutter repos usually do) must not have it
# created twice: fabric-api fails with "Cannot add a SourceSet with name 'gametest'".
$createGameTest = if (Test-Path (Join-Path $root 'src/gametest')) { 'false' } else { 'true' }
if ($gradle.Contains($marker)) {
	Write-Host "install: Gradle wiring already present, leaving it alone" -ForegroundColor DarkGray
}
elseif ($kotlinDsl) {
	$agentPath = (Join-Path $root '.modaccel/agent.jar') -replace '\\', '/'
	$rootSlash = $root -replace '\\', '/'
	# A repo that already configures fabric-api's test source set (Diorama does) keeps its own modId;
	# a second configureTests block would replace it.
	$gameTestBlock = if ($scriptText -match 'configureTests') { '' } else { @"

fabricApi {
	configureTests {
		createSourceSet = $createGameTest
		modId = "$modId-gametest"
		enableGameTests = true
		enableClientGameTests = false
		eula = true
		clearRunDirectory = false
	}
}
"@ }
	# `gradlew labClasspath` is asked for at the repo root. Gradle 9 refuses to resolve another
	# project's configuration, so a Stonecutter repo keeps the write in this node and the root task
	# only depends on it.
	$labClasspath = if ($node) {
		"if (project.path == `":$node`") {
	tasks.register(`"labClasspath`") {
		val cp = sourceSets[`"main`"].compileClasspath + (sourceSets.findByName(`"client`")?.compileClasspath ?: files()) + sourceSets[`"test`"].runtimeClasspath
		doLast {
			val out = rootProject.file(`".modaccel/classpath.txt`")
			out.parentFile.mkdirs()
			out.writeText(cp.asPath)
			// Source patterns this node excludes (another loader's sources); the fast lane must skip them.
			val ex = rootProject.file(`".modaccel/excludes.txt`")
			ex.writeText(sourceSets[`"main`"].java.excludes.joinToString(`"\n`") {
				it.replace(`"**/`", `"`").replace(`"/**`", `"`").replace(`"*`", `"`")
			})
		}
	}
	if (!rootProject.tasks.names.contains(`"labClasspath`")) {
		rootProject.tasks.register(`"labClasspath`") {
			dependsOn(`":${node}:labClasspath`")
		}
	}
}"
	}
	else {
		'tasks.register("labClasspath") {
	val cp = sourceSets["main"].compileClasspath + (sourceSets.findByName("client")?.compileClasspath ?: files()) + sourceSets["test"].runtimeClasspath
	doLast {
		val out = rootProject.file(".modaccel/classpath.txt")
		out.parentFile.mkdirs()
		out.writeText(cp.asPath)
		val ex = rootProject.file(".modaccel/excludes.txt")
		ex.writeText(sourceSets["main"].java.excludes.joinToString("\n") {
			it.replace("**/", "").replace("/**", "").replace("*", "")
		})
	}
}'
	}
	$modernLoom = $node -or $loomMajor -gt 1 -or ($loomMajor -eq 1 -and $loomMinor -ge 18)
	if ($modernLoom) {
		$block = @"

$marker
// Added by Modding Accelerator. Safe to delete as one block.
// Loom 1.18 API: run settings are Gradle properties. property(), vmArg() and ideConfigGenerated()
// are deprecated there, and Gradle's Kotlin DSL fails the build script on deprecated calls.
loom {
	runs {
		create("clientWarm") {
			client()
			// The game folder is the repo's build/run/warm even in a Stonecutter node
			// (versions/<node>), so the lab world, the shots and the root sit where modaccel looks.
			runDir = rootProject.file("build/run/warm").relativeTo(projectDir).path
			systemProperties.put("prismlab.lab.port", "27399")
			jvmArguments.addAll(
				"-javaagent:$agentPath",
				"-Dmodlab.root=$rootSlash",
				"-Dprismlab.agent.port=27398",
				"-XX:+AllowEnhancedClassRedefinition",
				"-XX:TieredStopAtLevel=1",
				"-Dmodlab.screen=",
				"-Dmodlab.name=ui",
				"-Dmodlab.click=",
				"-Dmodlab.block=",
				"-Dmodlab.probe=charge"
			)
			generateRunConfig.set(false)
		}
	}
}
$gameTestBlock

dependencies {
	testImplementation("net.fabricmc:fabric-loader-junit:`${project.property("loader_version")}")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
	outputs.upToDateWhen { false }
}

$labClasspath
// === end modding accelerator ===

"@
	}
	else {
		$block = @"

$marker
// Added by Modding Accelerator. Safe to delete as one block.
loom {
	runs {
		create("clientWarm") {
			client()
			property("prismlab.lab.port", "27399")
			vmArg("-javaagent:$agentPath")
			vmArg("-Dprismlab.agent.port=27398")
			vmArg("-XX:+AllowEnhancedClassRedefinition")
			vmArg("-XX:TieredStopAtLevel=1")
			vmArg("-Dmodlab.screen=")
			vmArg("-Dmodlab.name=ui")
			vmArg("-Dmodlab.click=")
			vmArg("-Dmodlab.block=")
			vmArg("-Dmodlab.probe=charge")
			ideConfigGenerated(false)
		}
	}
}
$gameTestBlock

dependencies {
	testImplementation("net.fabricmc:fabric-loader-junit:`${project.property("loader_version")}")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
	outputs.upToDateWhen { false }
}

$labClasspath
// === end modding accelerator ===

"@
	}
	if ($stonecutter -and -not ($gradlePath -and $scriptText -match 'loom')) {
		Write-Host "install: Stonecutter repo, but no Loom node build script was found. Paste this block into the active node's build script:" -ForegroundColor Yellow
		Write-Host $block
	}
	elseif ($stonecutter) {
		Add-Content -Path $gradlePath -Value $block
		Write-Host "install: added the Kotlin Gradle wiring block to the $node node's build script ($(Split-Path -Leaf $gradlePath))" -ForegroundColor DarkGray
	}
	else {
		Add-Content -Path $gradlePath -Value $block
		Write-Host "install: added the Kotlin Gradle wiring block to build.gradle.kts" -ForegroundColor DarkGray
	}
}
else {
	$agentPath = (Join-Path $root '.modaccel/agent.jar') -replace '\\', '/'
	$block = @"

$marker
// Added by Modding Accelerator. Safe to delete as one block.
loom {
	runs {
		clientWarm {
			client()
			property("prismlab.lab.port", "27399")
			vmArg "-javaagent:$agentPath"
			vmArg "-Dprismlab.agent.port=27398"
			vmArg "-XX:+AllowEnhancedClassRedefinition"

			vmArg "-XX:TieredStopAtLevel=1"
			ideConfigGenerated false
		}
	}
}

fabricApi {
	configureTests {
		createSourceSet = $createGameTest
		modId = "$modId-gametest"
		enableGameTests = true
		enableClientGameTests = false
		eula = true
		clearRunDirectory = false
	}
}

dependencies {
	testImplementation "net.fabricmc:fabric-loader-junit:`${project.loader_version}"
	testImplementation platform("org.junit:junit-bom:5.11.4")
	testImplementation "org.junit.jupiter:junit-jupiter"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

test {
	useJUnitPlatform()
	outputs.upToDateWhen { false }
	testLogging { events "passed", "failed", "skipped" }
}

// Writes the classpath once for the resident fast lane: every compile classpath (compile-only
// compat APIs such as EMI or REI live only there) plus the test runtime.
tasks.register("labClasspath") {
	def out = rootProject.file(".modaccel/classpath.txt")
	def cp = files({ sourceSets.matching { it.name in ['main', 'client', 'test'] }.collect { it.compileClasspath } }) +
		sourceSets["test"].runtimeClasspath
	doLast {
		out.parentFile.mkdirs()
		out.text = cp.asPath
	}
}
// === end modding accelerator ===

"@
	if ($stonecutter) { Write-Host "install: Stonecutter repo. Harness files are in place; paste this block into the active node's build script:" -ForegroundColor Yellow; Write-Host $block }
	else { Add-Content -Path $gradlePath -Value $block; Write-Host "install: added the Gradle wiring block to build.gradle" -ForegroundColor DarkGray }
}

# 3. game test source set with a starter test
$gameTest = Join-Path $root 'src/gametest'
New-Item -ItemType Directory -Force (Join-Path $gameTest 'java') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $gameTest 'resources') | Out-Null
# A repo that already ships game tests keeps them: its entrypoints and modId are its own.
$hasGameTests = @(Get-ChildItem (Join-Path $gameTest 'java') -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue).Count -gt 0
$testMod = Join-Path $gameTest 'resources/fabric.mod.json'
# The starter is written against 26.x (Mojang names, Fabric's 26.x GameTest API); on an older or
# Yarn-mapped mod it would not compile and would break the mod's own `gradlew build`.
$writeStarter = (-not $hasGameTests) -and $bridgeSupported
if (-not $hasGameTests -and -not $bridgeSupported) {
	Write-Host "install: Minecraft $mc is not 26.x, so no starter game test (it would not compile here); src/gametest is yours to fill" -ForegroundColor DarkGray
}
if ($writeStarter -and -not (Test-Path $testMod)) {
	@"
{
	"schemaVersion": 1,
	"id": "$modId-gametest",
	"version": "1.0.0",
	"name": "$modId game tests",
	"environment": "*",
	"entrypoints": {
		"fabric-gametest": [
			"$package.gametest.StarterGameTest"
		]
	},
	"depends": {
		"$modId": "*"
	}
}
"@ | Set-Content $testMod
}

$starter = Join-Path $gameTest "java/$($package -replace '\.', '/')/gametest/StarterGameTest.java"
if ($writeStarter) { New-Item -ItemType Directory -Force (Split-Path -Parent $starter) | Out-Null }
if ($writeStarter -and -not (Test-Path $starter)) {
	@"
package $package.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Replace with a real check: place your block, wait, assert what the player would see. */
public class StarterGameTest {
	@GameTest
	public void worldTicks(GameTestHelper context) {
		context.setBlock(0, 1, 0, net.minecraft.world.level.block.Blocks.GLOWSTONE);
		context.assertBlockPresent(net.minecraft.world.level.block.Blocks.GLOWSTONE, 0, 1, 0);
		context.succeed();
	}
}
"@ | Set-Content $starter
}

# 4. one real pure test, so a first `.\modaccel fast` answers "1 passed" instead of "0 passed".
#    The dependency block above already brings JUnit 5 and its launcher; a pure test touches no
#    Minecraft class. An existing tests folder is never touched.
$pureSrc = Join-Path $root 'src/test/java'
$pureDir = Join-Path $pureSrc 'dev/modlab'
$pureHasTests = @(Get-ChildItem $pureSrc -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue).Count -gt 0
# A Stonecutter repo that still has to paste the block by hand has no JUnit on its test classpath
# yet, and a starter test that cannot compile would turn "0 passed" into a red lane.
$testDeps = $false
foreach ($candidate in @($gradlePath, (Join-Path $root 'build.gradle'), (Join-Path $root 'build.gradle.kts'))) {
	if ($candidate -and (Test-Path $candidate) -and ((Get-Content $candidate -Raw) -match 'junit-jupiter')) { $testDeps = $true; break }
}
if ($pureHasTests) {
	Write-Host 'install: src/test/java already has tests, leaving them' -ForegroundColor DarkGray
}
elseif (-not $testDeps) {
	Write-Host 'install: no JUnit dependency in a build script yet, so no starter pure test (paste the wiring block first)' -ForegroundColor Yellow
}
else {
	New-Item -ItemType Directory -Force $pureDir | Out-Null
	@'
package dev.modlab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tag is what the fast lane selects: a pure test loads no Minecraft class, so this runs in
 * about a second without Gradle. Replace the body with a real check of your own logic.
 */
@Tag("pure")
public class StarterTest {
	@Test
	public void arithmetic() {
		assertEquals(4, 2 + 2, "starter test: replace this with a real check");
	}
}
'@ | Set-Content (Join-Path $pureDir 'StarterTest.java')
	Write-Host 'install: starter pure test at src/test/java/dev/modlab/StarterTest.java (tagged pure)'
}

# 4. the onboarding skill, so an agent in this repo finds the loop
$skillDir = Join-Path $root '.claude/skills/modding-accelerator'
New-Item -ItemType Directory -Force $skillDir | Out-Null
Copy-Item (Join-Path $here 'skills/modding-accelerator/SKILL.md') (Join-Path $skillDir 'SKILL.md') -Force

# 5. ignore runtime state
$ignore = Join-Path $root '.gitignore'
$needed = @('.modaccel/', '.runs/', '.tools/')
$current = if (Test-Path $ignore) { Get-Content $ignore -Raw } else { '' }
$add = $needed | Where-Object { $current -notmatch [regex]::Escape($_) }
if ($add) {
	Add-Content $ignore ("`n# modding accelerator`n" + ($add -join "`n"))
}

Write-Host ''
Write-Host "install: done. Next:" -ForegroundColor Green
Write-Host "  1. .\modaccel                 (first run downloads the JDKs, then lints + logic tests)"
Write-Host "  2. .\modaccel world           (proves your registrations load in a real server)"
if ($bridgeSupported) { Write-Host "  3. Read README.md section 'Using it for your own mod' for the UI and block tiers." }
else { Write-Host "  3. Read README.md section 'Using it for your own mod' - the UI and block tiers are 26.x only." }
Write-Host ''
if ($jbr) { Write-Host "Note: this machine already has .tools\jbr, so the DJK download is skipped here." -ForegroundColor DarkGray }
$wrapperProps = Join-Path $root 'gradle/wrapper/gradle-wrapper.properties'
if ((Test-Path $wrapperProps) -and ((Get-Content $wrapperProps -Raw) -match 'gradle-8\.')) {
	Write-Host "Note: Gradle 8.x cannot run on JDK 25 (class file major 69). modaccel switches to a JDK 21 when .tools or %USERPROFILE%\tools has one." -ForegroundColor Yellow
}

