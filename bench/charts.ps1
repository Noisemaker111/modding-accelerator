#requires -Version 7.0
# Builds bench\benchmarks.html: one self-contained page, no CDN, inline SVG.
# Numbers come from bench\data.json, which records what was measured and how many runs.
param(
	[string]$Data = (Join-Path $PSScriptRoot 'data.json'),
	[string]$Out = (Join-Path $PSScriptRoot 'benchmarks.html')
)

$ErrorActionPreference = 'Stop'
$d = Get-Content $Data -Raw | ConvertFrom-Json

function Format-Seconds {
	param([double]$Seconds)
	if ($Seconds -lt 1) { return ('{0:N0}ms' -f ($Seconds * 1000)) }
	if ($Seconds -lt 60) { return ('{0:N1}s' -f $Seconds) }
	if ($Seconds -lt 3600) { return ('{0:N1}min' -f ($Seconds / 60)) }
	return ('{0:N1}h' -f ($Seconds / 3600))
}

function New-Bars {
	param($Rows, [string]$Title, [string]$Note, [double]$Max = 0, [switch]$Log, [switch]$AsCount)
	function Show([double]$v) { if ($AsCount) { return ([string][int]$v) } return (Format-Seconds $v) }
	$w = 860; $label = 300; $bar = 430; $rowH = 34; $top = 46
	$values = @($Rows | ForEach-Object { [double]$_.seconds })
	if ($Max -le 0) { $Max = ($values | Measure-Object -Maximum).Maximum * 1.15 }
	$Min = 0.4
	$scale = {
		param([double]$v)
		if ($Log) {
			$lo = [math]::Log10($Min); $hi = [math]::Log10($Max)
			return ($bar * (([math]::Log10([math]::Max($v, $Min)) - $lo) / ($hi - $lo)))
		}
		return ($bar * ($v / $Max))
	}
	$sb = [System.Text.StringBuilder]::new()
	[void]$sb.AppendLine("<section class='card'>")
	[void]$sb.AppendLine("<h2>$Title</h2>")
	if ($Note) { [void]$sb.AppendLine("<p class='note'>$Note</p>") }
	$h = $top + ($Rows.Count * $rowH) + 24
	[void]$sb.AppendLine("<svg viewBox='0 0 $w $h' width='100%' role='img' aria-label='$Title'>")
	# gridlines
	if ($Log) {
		foreach ($tick in @(0.5, 1, 2, 5, 10, 30, 60, 300, 900, 3600)) {
			if ($tick -gt $Max) { continue }
			$x = $label + (& $scale $tick)
			[void]$sb.AppendLine("<line x1='{0:N1}' y1='{1}' x2='{0:N1}' y2='{2}' stroke='#e5e7eb' stroke-width='1'/>" -f ($x, ($top - 16), ($h - 16)))
			[void]$sb.AppendLine("<text x='{0:N1}' y='{1}' font-size='10' fill='#9ca3af' text-anchor='middle'>{2}</text>" -f ($x, ($h - 4), (Show $tick)))
		}
	}
	$y = $top
	foreach ($row in $Rows) {
		$v = [double]$row.seconds
		$width = [math]::Max(2, (& $scale $v))
		$color = if ($v -ge 60) { '#dc2626' } elseif ($v -ge 10) { '#f59e0b' } else { '#2563eb' }
		[void]$sb.AppendLine("<text x='0' y='$($y + 4)' font-size='13' fill='#111827'>$($row.name)</text>")
		[void]$sb.AppendLine("<rect x='$label' y='$($y - 12)' width='{0:N1}' height='20' rx='4' fill='$color'/>" -f $width)
		$extra = if ($row.n) { "  (n=$($row.n))" } else { '' }
		[void]$sb.AppendLine("<text x='{0:N1}' y='$($y + 4)' font-size='12' fill='#374151'>$(Show $v)$extra</text>" -f ($label + $width + 8))
		$y += $rowH
	}
	[void]$sb.AppendLine("</svg>")
	[void]$sb.AppendLine("</section>")
	return $sb.ToString()
}

function New-GroupedBars {
	param($Rows, [string]$KeyProp, [string]$Title, [double]$Max)
	$keys = @($Rows[0].PSObject.Properties.Name | Where-Object { $_ -ne $KeyProp })
	$w = 860; $h = 250; $left = 60; $bottom = 34; $plot = $h - $bottom - 30
	$colors = @('#2563eb', '#0ea5e9', '#f59e0b', '#16a34a')
	$sb = [System.Text.StringBuilder]::new()
	[void]$sb.AppendLine("<section class='card'>")
	[void]$sb.AppendLine("<h2>$Title</h2>")
	[void]$sb.AppendLine("<svg viewBox='0 0 $w $h' width='100%' role='img' aria-label='$Title'>")
	$groupW = ($w - $left - 40) / $Rows.Count
	$barW = [math]::Min(48, ($groupW - 20) / $keys.Count)
	for ($i = 0; $i -lt $Rows.Count; $i++) {
		$gx = $left + ($i * $groupW) + ($groupW / 2)
		$k = 0
		foreach ($key in $keys) {
			$v = [double]$Rows[$i].$key
			$bh = $plot * ($v / $Max)
			$x = $gx + ($k * $barW) - (($keys.Count * $barW) / 2)
			$y = 30 + $plot - $bh
			[void]$sb.AppendLine("<rect x='{0:N1}' y='{1:N1}' width='{2:N1}' height='{3:N1}' rx='3' fill='$($colors[$k % 4])'/>" -f ($x, $y, ($barW - 6), $bh))
			if ($v -ge 5) {
				[void]$sb.AppendLine("<text x='{0:N1}' y='{1:N1}' font-size='10' fill='#374151' text-anchor='middle'>$(Format-Seconds $v)</text>" -f (($x + ($barW - 6) / 2), ($y - 6)))
			}
			$k++
		}
		[void]$sb.AppendLine("<text x='{0:N1}' y='{1}' font-size='12' fill='#111827' text-anchor='middle'>$($Rows[$i].$KeyProp)</text>" -f ($gx, ($h - 10)))
	}
	$lx = $left
	$k = 0
	foreach ($key in $keys) {
		[void]$sb.AppendLine("<rect x='$lx' y='6' width='10' height='10' rx='2' fill='$($colors[$k % 4])'/>")
		[void]$sb.AppendLine("<text x='$($lx + 14)' y='15' font-size='11' fill='#374151'>$key</text>")
		$lx += 26 + ($key.Length * 6.5)
		$k++
	}
	[void]$sb.AppendLine("</svg>")
	[void]$sb.AppendLine("</section>")
	return $sb.ToString()
}

$html = [System.Text.StringBuilder]::new()
[void]$html.AppendLine(@"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Modding Accelerator — measured</title>
<style>
  :root { color-scheme: light; }
  body { margin: 0; font: 15px/1.5 'Segoe UI', system-ui, sans-serif; background: #f8fafc; color: #111827; }
  main { max-width: 960px; margin: 0 auto; padding: 40px 24px 64px; }
  h1 { font-size: 30px; margin: 0 0 6px; letter-spacing: -0.02em; }
  p.sub { margin: 0 0 28px; color: #6b7280; }
  .kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 28px; }
  .kpi { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px; }
  .kpi b { display: block; font-size: 28px; letter-spacing: -0.02em; }
  .kpi span { color: #6b7280; font-size: 12px; }
  .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 20px 20px 8px; margin-bottom: 18px; }
  h2 { font-size: 16px; margin: 0 0 4px; }
  p.note { margin: 0 0 8px; color: #6b7280; font-size: 12px; }
  ul.notes { margin: 8px 0 0; padding-left: 18px; color: #4b5563; font-size: 13px; }
  footer { color: #9ca3af; font-size: 12px; margin-top: 20px; }
</style>
</head>
<body><main>
<h1>Modding Accelerator — measured</h1>
<p class="sub">$($d.machine) · $($d.measured) · every number below was produced by a run in the repo, recorded in .runs/</p>
<div class="kpis">
  <div class="kpi"><b>1.5s</b><span>one screen change, screenshot included (Fabric 26.3 and NeoForge 1.21.1)</span></div>
  <div class="kpi"><b>2.9s</b><span>one scripted scenario with a probe</span></div>
  <div class="kpi"><b>15s</b><span>a new registration on a fresh server</span></div>
  <div class="kpi"><b>3.8s</b><span>a two-case suite, one warm client</span></div>
</div>
"@)

[void]$html.Append((New-Bars -Rows $d.loop -Title 'One change, one answer' -Note 'Log scale. Warm process unless a row says cold.' -Log))
[void]$html.Append((New-Bars -Rows $d.compare -Title 'Against a conventional mod loop' -Note 'This lab measured here; Diorama from its own commits and docs.' -Log))
[void]$html.Append((New-GroupedBars -Rows $d.versions -KeyProp 'node' -Title 'Three nodes, same checks (build is a warm clean build; NeoForge''s first build was ~290s to fetch and decompile)' -Max 26))
[void]$html.Append((New-Bars -Rows $d.multiplayer -Title 'Multiplayer: what the first run costs' -Note 'After the server and client are up, a scenario is the same 4s.'))
[void]$html.Append((New-Bars -Rows $d.porting -Title 'Moving to another version: the report' -Note 'The diff says what breaks; the port lane writes a handoff.'))
[void]$html.Append((New-GroupedBars -Rows $d.portbuild -KeyProp 'mod' -Title 'How much a real version bump costs to build (6 open-source mods)' -Max 620))
[void]$html.Append((New-Bars -Rows $d.porterrors -Title 'Compile errors at the first failure' -Note 'Counts are not effort: Terrestria''s 99 errors are 3 symbols in 2 files, Artifacts'' 97 split into 22 mechanical, 7 type mismatches and 68 structural, Traveler''s Backpack''s 196 took 4 repair cycles.' -AsCount))
[void]$html.Append((New-Bars -Rows $d.portfixes -Title 'Port report classification: mechanical work vs structural work' -Note 'Port report run on each log. Traveler''s Backpack: 100 errors, 22 mechanical and 78 structural. Artifacts: 97 errors, 22 mechanical, 7 type mismatches, 68 structural (render-state refactor).' -AsCount))
[void]$html.Append((New-Bars -Rows $d.hotreload -Title 'One screen edit, start to verified: hot reload vs a restart' -Note 'Fabric 26.3 and NeoForge 1.21.1 redefine the class in the running client (edit to screenshot, same JVM). A schema change (new field, method or registry entry) still needs a full client start: 28s on Fabric 1.21.11, 22s on NeoForge.' -Log))
[void]$html.Append((New-Bars -Rows $d.repair -Title 'From first failure to a green build: all four mods repaired by time' -Note 'Each repo''s own Gradle build after the repair pass. Trinkets 21 errors in 69s, Polymer 35 in 516s, Traveler''s Backpack 196 over 4 cycles in 660s, Artifacts 97 over 8 builds in 1680s. Traverse and Terrestria were counted in call sites instead, see the panels below.' -Log))

[void]$html.Append((New-Bars -Rows $d.forgeloop -Title 'Forge: what their build costs next to our first run and one edit' -Note 'MineColonies 1.20.1, 2102 sources, JDK 17. Their gradlew build -x test 328.3s and install 5s; then .\modaccel fast 23.9s cold, 2.3s per edit, 1.3s to FAIL a broken file.' -Log))
[void]$html.Append((New-Bars -Rows $d.neoforgeloop -Title 'NeoForge 1.21.1: first check pays for the client, repeats do not' -Note 'Same node, all .\modaccel ui: client start to listening 18.4s cold, first ui after start 2.4s, screen check 3.2s with two screenshots, 6.1s total on a repeat. The very first ui of the session, build included, was 25.6s.' -Log))
[void]$html.Append((New-Bars -Rows $d.dioramaloop -Title 'Diorama: their build vs the fast lane and the gate' -Note 'Stonecutter repo: their :26.3:build -x test 204.5s, fast lane 2.1s, toolchain gate READY 5.2s.' -Log))
[void]$html.Append((New-Bars -Rows $d.toolchain -Title 'Toolchain gate: how long before we know a repo is workable' -Note 'Gate runs the clone the user gave us: Diorama READY 5.2s; MineColonies 1.20.4 BLOCKED 29.4s; MineColonies main configures 88.7s; Traverse BLOCKED 11.1s; Artifacts BLOCKED 8.8s. NeoForge client, direct launch, listening at 7s. Later version checks had no timing: NeoForge 1.21.4 exists (21.4.161); 1.20.1 BLOCKED, the gate looked for 20.1.*.' -Log))
[void]$html.Append((New-Bars -Rows $d.repairsites -Title 'Port repair size: call sites fixed per mod' -Note 'Counts, not seconds. Traverse 68 sites and Terrestria 142 sites repaired to a green compile; both are the 1.21.5 to 1.21.6 jump.' -AsCount))
[void]$html.Append((New-Bars -Rows $d.repairgreen -Title 'What each repaired mod builds in' -Note 'Same repos, their own Gradle build after the repair pass: Traverse 5.9s, Terrestria 7.5s, Traveler''s Backpack 5s, Artifacts 13s.'))

[void]$html.AppendLine("<section class='card'><h2>What to keep in mind</h2><ul class='notes'>")
foreach ($n in $d.notes) { [void]$html.AppendLine("<li>$n</li>") }
[void]$html.AppendLine("</ul></section>")
[void]$html.AppendLine("<footer>Generated from bench/data.json by bench/charts.ps1. Regenerate after a new bench run.</footer></main></body></html>")

Set-Content $Out ($html.ToString())
Write-Host "wrote $Out" -ForegroundColor Green










