#requires -Version 7.0
# Minecraft mod loop chart: their build vs our answer
# System.Drawing only, no packages, no network. Output: 1200x800 PNG.
Add-Type -AssemblyName System.Drawing

$W = 1200
$H = 800
$OutDir = 'C:\Users\Jk101\Projects\modding-accelerator\bench\chart-experiment'
$OutFile = Join-Path $OutDir 'deepseek.png'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function C([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }

$bg      = C '#11161d'
$panelIn = C '#161c25'
$track   = C '#1b222c'
$grid    = C '#232c38'
$text    = C '#e6edf3'
$muted   = C '#9aa7b5'
$faint   = C '#8c99a8'
$prim    = C '#3b82f6'
$primDk  = C '#2c5fa8'
$accent  = C '#f5a623'
$teal    = C '#2dd4bf'

$fTitle = [System.Drawing.Font]::new('Segoe UI', 22, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fHead  = [System.Drawing.Font]::new('Segoe UI', 17, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fLabel = [System.Drawing.Font]::new('Segoe UI', 17, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fVal   = [System.Drawing.Font]::new('Segoe UI', 17, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fSmall = [System.Drawing.Font]::new('Segoe UI', 16, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fSmallB= [System.Drawing.Font]::new('Segoe UI', 16, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)

$bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear($bg)

$bText  = [System.Drawing.SolidBrush]::new($text)
$bMuted = [System.Drawing.SolidBrush]::new($muted)
$bFaint = [System.Drawing.SolidBrush]::new($faint)
$bPrim  = [System.Drawing.SolidBrush]::new($prim)
$bTrack = [System.Drawing.SolidBrush]::new($track)
$bAcc   = [System.Drawing.SolidBrush]::new($accent)
$bTeal  = [System.Drawing.SolidBrush]::new($teal)
$pGrid  = [System.Drawing.Pen]::new($grid, 1)

$sfRight = [System.Drawing.StringFormat]::new()
$sfRight.Alignment = [System.Drawing.StringAlignment]::Far
$sfRight.LineAlignment = [System.Drawing.StringAlignment]::Center

function Txt($s, $font, $brush, [float]$x, [float]$y) {
  $g.DrawString($s, $font, $brush, [float]$x, [float]$y)
}
function TxtR($s, $font, $brush, [float]$rightEdge, [float]$y, [float]$w = 400, [float]$h = 24) {
  $g.DrawString($s, $font, $brush, [System.Drawing.RectangleF]::new($rightEdge - $w, $y, $w, $h), $sfRight)
}
function Bar($brush, [float]$x, [float]$y, [float]$w, [float]$h) {
  if ($w -lt 1) { $w = 1 }
  $g.FillRectangle($brush, $x, $y, $w, $h)
}

# ---------- Title ----------
Txt 'Minecraft mod loop: their build vs our answer' $fTitle $bText 60 26

# ---------- Panel A: full build times ----------
$ax0 = 210.0
$ax1 = 970.0
$aMax = 600.0
$aScale = ($ax1 - $ax0) / $aMax
$aTop = 104.0
$aRow = 46.0
$aBarH = 24.0

Txt 'A  Full build time (seconds)' $fHead $bText 60 74

# legend A
$g.FillRectangle($bPrim, 690, 80, 13, 13)
Txt 'full build' $fSmall $bMuted 712 77
$g.FillRectangle($bAcc, 815, 80, 13, 13)
Txt 'measured edit loop (marker, not to scale)' $fSmall $bMuted 837 77

# grid + axis
foreach ($sec in 100, 200, 300, 400, 500) {
  $x = $ax0 + $sec * $aScale
  $g.DrawLine($pGrid, [float]$x, 100, [float]$x, 418)
}
$g.DrawLine([System.Drawing.Pen]::new($grid, 1), [float]$ax0, 100, [float]$ax0, 418)

$builds = @(
  @{ n = 'Artifacts';       v = 567.5 },
  @{ n = 'Polymer';         v = 474.4 },
  @{ n = 'Traverse';        v = 335.1 },
  @{ n = 'MineColonies';    v = 328.3; loop = 2.3 },
  @{ n = 'Terrestria';      v = 321.1 },
  @{ n = 'Diorama';         v = 204.5; loop = 2.1 },
  @{ n = 'Trinkets';        v = 141.8 }
)

for ($i = 0; $i -lt $builds.Count; $i++) {
  $b = $builds[$i]
  $cy = $aTop + $i * $aRow + $aRow / 2
  $by = $cy - $aBarH / 2
  $bw = $b.v * $aScale
  Bar $bPrim $ax0 $by $bw $aBarH
  TxtR $b.n $fLabel $bText 198 ($cy - 12) 184 24
  $lx = $ax0 + $bw + 7
  Txt ([string]$b.v) $fVal $bText $lx ($cy - 11)
  if ($b.ContainsKey('loop')) {
    $tickX = $ax0 + $bw
    $g.DrawLine([System.Drawing.Pen]::new($accent, 3), [float]$tickX, [float]$by, [float]$tickX, [float]($by + $aBarH))
    $loopText = 'loop ' + $b.loop + ' s'
    Txt $loopText $fSmallB $bAcc ($lx + 62) ($cy - 10)
  }
}

Txt 'own gradlew build -x test, fresh clone  ·  gridlines every 100 s' $fSmall $bMuted 60 428

# separator
$g.DrawLine($pGrid, 60, 458, 1140, 458)

# ---------- Panel B: repair ----------
$ex0 = 400.0; $ex1 = 780.0; $eMax = 200.0
$eScale = ($ex1 - $ex0) / $eMax
$mx0 = 900.0; $mx1 = 1080.0; $mMax = 30.0
$mScale = ($mx1 - $mx0) / $mMax
$bTop = 528.0
$bRow = 32.0
$bBarH = 18.0

Txt 'B  Repair: errors fixed vs minutes of editing' $fHead $bText 60 470

# legend B
$g.FillRectangle($bPrim, 700, 476, 13, 13)
Txt 'errors fixed' $fSmall $bMuted 722 473
$g.FillRectangle($bTeal, 835, 476, 13, 13)
Txt 'minutes of editing' $fSmall $bMuted 857 473

# column headers
Txt 'errors fixed (count)' $fSmallB $bMuted 400 500
Txt 'minutes' $fSmallB $bMuted 900 500

$repairs = @(
  @{ n = 'Trinkets';            e = 21;  m = 1.15 },
  @{ n = 'Polymer';             e = 35;  m = 8.58 },
  @{ n = "Traveler's Backpack"; e = 196; m = 11.0 },
  @{ n = 'Artifacts';           e = 97;  m = 28.1 },
  @{ n = 'Traverse';            e = 68;  m = $null; note = 'sites' },
  @{ n = 'Terrestria';          e = 142; m = $null; note = 'sites' }
)

for ($i = 0; $i -lt $repairs.Count; $i++) {
  $r = $repairs[$i]
  $cy = $bTop + $i * $bRow + $bRow / 2
  $by = $cy - $bBarH / 2
  TxtR $r.n $fLabel $bText 388 ($cy - 12) 210 24

  # errors track + bar
  Bar $bTrack $ex0 $by ($ex1 - $ex0) $bBarH
  $ew = $r.e * $eScale
  Bar $bPrim $ex0 $by $ew $bBarH
  $etext = [string]$r.e
  if ($r.ContainsKey('note')) { $etext = "$($r.e) $($r.note)" }
  Txt $etext $fVal $bText ($ex0 + $ew + 7) ($cy - 11)

  # minutes track + bar
  Bar $bTrack $mx0 $by ($mx1 - $mx0) $bBarH
  if ($null -ne $r.m) {
    $mw = [double]$r.m * $mScale
    Bar $bTeal $mx0 $by $mw $bBarH
    Txt ([string]$r.m) $fVal $bText ($mx0 + $mw + 7) ($cy - 11)
  } else {
    Txt 'not tracked' $fLabel $bFaint ($mx0 + 7) ($cy - 11)
  }
}

# ---------- footnote ----------
Txt 'Provenance: full build = fresh-clone ./gradlew build -x test timed on this PC; loop = measured incremental rebuild after one edit.' $fSmall $bFaint 60 726
Txt 'Repair = errors fixed in compiler logs after porting to our API; minutes = tracked hands-on editing time; blank means not tracked.' $fSmall $bFaint 60 748

$g.Dispose()
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Output "saved: $OutFile"
