Add-Type -AssemblyName System.Drawing

$outPath = "C:\Users\Jk101\Projects\modding-accelerator\bench\chart-experiment\longcat.png"
$width = 1200
$height = 800

$bg = [System.Drawing.ColorTranslator]::FromHtml("#11161d")
$fg = [System.Drawing.Color]::White
$grid = [System.Drawing.ColorTranslator]::FromHtml("#2a3340")
$accent1 = [System.Drawing.ColorTranslator]::FromHtml("#4fc3f7")
$accent2 = [System.Drawing.ColorTranslator]::FromHtml("#ff7043")
$accent3 = [System.Drawing.ColorTranslator]::FromHtml("#81c784")

$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = "AntiAlias"
$graphics.TextRenderingHint = "ClearTypeGridFit"
$graphics.Clear($bg)

$titleFont = New-Object System.Drawing.Font("Segoe UI", 20, [System.Drawing.FontStyle]::Bold)
$panelTitleFont = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Bold)
$labelFont = New-Object System.Drawing.Font("Segoe UI", 13)
$labelFontSmall = New-Object System.Drawing.Font("Segoe UI", 12)
$valueFont = New-Object System.Drawing.Font("Segoe UI", 13, [System.Drawing.FontStyle]::Bold)
$captionFont = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Italic)
$footnoteFont = New-Object System.Drawing.Font("Segoe UI", 11)

$sf = New-Object System.Drawing.StringFormat
$sf.Alignment = "Near"
$sf.LineAlignment = "Center"

# Title
$titleBrush = New-Object System.Drawing.SolidBrush($fg)
$sf.Alignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString("Minecraft mod loop: their build vs our answer", $titleFont, $titleBrush, ($width / 2), 25, $sf)
$sf.Alignment = [System.Drawing.StringAlignment]::Near

# Panel positions
$panelMargin = 40
$panelTop = 75
$panelW = ($width - $panelMargin * 3) / 2
$panelH = $height - $panelTop - 110

# Draw panel backgrounds
$panelBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#1a212b"))
$graphics.FillRectangle($panelBrush, $panelMargin, $panelTop, $panelW, $panelH)
$graphics.FillRectangle($panelBrush, $panelMargin * 2 + $panelW, $panelTop, $panelW, $panelH)

# Grid pens
$gridPen = New-Object System.Drawing.Pen($grid, 1)

# ============================================================
# Panel A - Build seconds
# ============================================================
$aTitleX = $panelMargin + 20
$aTitleY = $panelTop + 15
$graphics.DrawString("A. Full build seconds", $panelTitleFont, $titleBrush, $aTitleX, $aTitleY)

# Caption
$captionBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#9aa7b5"))
$graphics.DrawString("own gradlew build -x test, fresh clone", $captionFont, $captionBrush, $aTitleX, $aTitleY + 28)

# Build times (sorted descending)
$buildData = @(
    @{ Name = "Artifacts"; Value = 567.5 },
    @{ Name = "Polymer"; Value = 474.4 },
    @{ Name = "Traverse"; Value = 335.1 },
    @{ Name = "MineColonies"; Value = 328.3 },
    @{ Name = "Terrestria"; Value = 321.1 },
    @{ Name = "Diorama"; Value = 204.5 },
    @{ Name = "Trinkets"; Value = 141.8 }
)

$barTop = $aTitleY + 65
$barLeft = $aTitleX + 10
$barAreaW = $panelW - 30
$barHeight = 28
$barGap = 12
$labelAreaWidth = 110

# Max value for scaling
$maxBuild = 600
$scaleFactor = ($barAreaW - $labelAreaWidth - 50) / $maxBuild

# Draw bars
$barBrush = New-Object System.Drawing.SolidBrush($accent1)
$barBrushLoop = New-Object System.Drawing.SolidBrush($accent3)
$whiteBrush = New-Object System.Drawing.SolidBrush($fg)
$dimBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#9aa7b5"))

for ($i = 0; $i -lt $buildData.Count; $i++) {
    $entry = $buildData[$i]
    $y = $barTop + $i * ($barHeight + $barGap)
    
    # Label
    $graphics.DrawString($entry.Name, $labelFontSmall, $whiteBrush, $barLeft, $y + $barHeight / 2, $sf)
    
    # Bar background
    $barX = $barLeft + $labelAreaWidth + 5
    $barW = [int]($entry.Value * $scaleFactor)
    $graphics.FillRectangle($barBrush, $barX, $y, $barW, $barHeight)
    
    # Value label
    $graphics.DrawString("$($entry.Value)s", $valueFont, $whiteBrush, $barX + $barW + 8, $y + $barHeight / 2, $sf)
    
    # Grid lines
    if ($i -lt $buildData.Count - 1) {
        $graphics.DrawLine($gridPen, $barLeft, $y + $barHeight + $barGap / 2, $barLeft + $barAreaW, $y + $barHeight + $barGap / 2)
    }
}

# Scale markers
$scaleY = $barTop + $buildData.Count * ($barHeight + $barGap) + 10
$scaleValues = @(0, 200, 400, 600)
foreach ($sv in $scaleValues) {
    $sx = $barLeft + $labelAreaWidth + 5 + [int]($sv * $scaleFactor)
    $graphics.DrawString("$sv", $captionFont, $dimBrush, $sx, $scaleY, $sf)
}

# Legend
$legY = $panelTop + $panelH - 50
$legX = $barLeft
$graphics.FillRectangle($barBrush, $legX, $legY, 16, 16)
$graphics.DrawString("Full build (seconds)", $labelFontSmall, $dimBrush, $legX + 22, $legY + 8, $sf)
$legX += 200
$graphics.FillRectangle($barBrushLoop, $legX, $legY, 16, 16)
$graphics.DrawString("Our loop (seconds)", $labelFontSmall, $dimBrush, $legX + 22, $legY + 8, $sf)

# Overlay second series - MineColonies loop and Diorama loop
$loopTop = $barTop + $buildData.Count * ($barHeight + $barGap) + 30
$loopLabelY = $loopTop
$graphics.DrawString("Our loop times:", $labelFont, $titleBrush, $barLeft, $loopLabelY)

$loopData = @(
    @{ Name = "MineColonies"; Value = 2.3 },
    @{ Name = "Diorama"; Value = 2.1 }
)

$loopBarTop = $loopLabelY + 28
for ($i = 0; $i -lt $loopData.Count; $i++) {
    $entry = $loopData[$i]
    $y = $loopBarTop + $i * ($barHeight + $barGap)
    $graphics.DrawString($entry.Name, $labelFontSmall, $whiteBrush, $barLeft, $y + $barHeight / 2, $sf)
    
    $loopScaleFactor = ($barAreaW - $labelAreaWidth - 50) / 600
    $barX = $barLeft + $labelAreaWidth + 5
    $barW = [int]($entry.Value * $loopScaleFactor)
    $graphics.FillRectangle($barBrushLoop, $barX, $y, [Math]::Max($barW, 8), $barHeight)
    
    # Value label in green
    $greenBrush = New-Object System.Drawing.SolidBrush($accent3)
    $graphics.DrawString("$($entry.Value)s", $valueFont, $greenBrush, $barX + [Math]::Max($barW, 8) + 8, $y + $barHeight / 2, $sf)
}

# ============================================================
# Panel B - Repair
# ============================================================
$bPanelX = $panelMargin * 2 + $panelW
$bTitleX = $bPanelX + 20
$bTitleY = $panelTop + 15
$graphics.DrawString("B. Repair: errors fixed vs editing minutes", $panelTitleFont, $titleBrush, $bTitleX, $bTitleY)

$repairData = @(
    @{ Name = "Polymer"; Errors = 35; Minutes = 8.58 },
    @{ Name = "Traveler's Backpack"; Errors = 196; Minutes = 11.0 },
    @{ Name = "Artifacts"; Errors = 97; Minutes = 28.1 },
    @{ Name = "Traverse"; Errors = 68; Minutes = 0; Note = "sites" },
    @{ Name = "Terrestria"; Errors = 142; Minutes = 0; Note = "sites" },
    @{ Name = "Trinkets"; Errors = 21; Minutes = 1.15 }
)

$bBarTop = $bTitleY + 55
$bBarLeft = $bTitleX + 10
$bBarAreaW = $panelW - 30

$maxErrors = 220
$maxMinutes = 30
$errorsScale = ($bBarAreaW - 120 - 80) / $maxErrors
$minutesScale = ($bBarAreaW - 120 - 80) / $maxMinutes

$errBrush = New-Object System.Drawing.SolidBrush($accent2)
$minBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#ffd54f"))
$siteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#ba68c8"))

for ($i = 0; $i -lt $repairData.Count; $i++) {
    $entry = $repairData[$i]
    $y = $bBarTop + $i * ($barHeight + $barGap)
    
    # Label
    $graphics.DrawString($entry.Name, $labelFontSmall, $whiteBrush, $bBarLeft, $y + $barHeight / 2, $sf)
    
    $barX = $bBarLeft + 125
    
    # Errors bar
    $errW = [int]($entry.Errors * $errorsScale)
    $barToUse = if ($entry.Minutes -eq 0 -and $entry.Note -eq "sites") { $siteBrush } else { $errBrush }
    $graphics.FillRectangle($barToUse, $barX, $y, $errW, $barHeight)
    
    # Minutes bar (thinner, below/overlapping or next to it)
    $minBarY = $y + $barHeight / 2 + 2
    $minBarH = $barHeight / 2 - 4
    $errBarH = $barHeight / 2 - 4
    
    # Redraw split: errors on top half, minutes on bottom half
    $graphics.FillRectangle($barToUse, $barX, $y, $errW, $errBarH)
    $minW = if ($entry.Minutes -gt 0) { [int]($entry.Minutes * $minutesScale) } else { 0 }
    if ($minW -gt 0) {
        $graphics.FillRectangle($minBrush, $barX, $y + $barHeight / 2, $minW, $minBarH)
    }
    
    # Value labels
    $valStr = "$($entry.Errors)"
    if ($entry.Note -eq "sites") {
        $valStr = "$($entry.Errors) sites"
    }
    $graphics.DrawString($valStr, $valueFont, $whiteBrush, $barX + $errW + 6, $y + 7, $sf)
    
    if ($entry.Minutes -gt 0) {
        $graphics.DrawString("$($entry.Minutes)m", $labelFontSmall, $minBrush, $barX + $errW + 6, $y + $barHeight / 2 + 2, $sf)
    } else {
        $graphics.DrawString("—", $labelFontSmall, $dimBrush, $barX + $errW + 6, $y + $barHeight / 2 + 2, $sf)
    }
}

# Legend for Panel B
$bLegY = $panelTop + $panelH - 50
$bLegX = $bBarLeft
$graphics.FillRectangle($errBrush, $bLegX, $bLegY, 16, 16)
$graphics.DrawString("Errors fixed", $labelFontSmall, $dimBrush, $bLegX + 22, $bLegY + 8, $sf)
$bLegX += 140
$graphics.FillRectangle($minBrush, $bLegX, $bLegY, 16, 16)
$graphics.DrawString("Editing minutes", $labelFontSmall, $dimBrush, $bLegX + 22, $bLegY + 8, $sf)
$bLegX += 170
$graphics.FillRectangle($siteBrush, $bLegX, $bLegY, 16, 16)
$graphics.DrawString("Sites (no minutes)", $labelFontSmall, $dimBrush, $bLegX + 22, $bLegY + 8, $sf)

# Footnote
$footnote = "Data: own gradlew build -x test (fresh clone); loop times from modding-accelerator bench; repair counts from agent session logs."
$footnoteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#6b7a8a"))
$sf.Alignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString($footnote, $footnoteFont, $footnoteBrush, ($width / 2), $height - 25, $sf)
$sf.Alignment = [System.Drawing.StringAlignment]::Near

# Save
$bitmap.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)

# Cleanup
$graphics.Dispose()
$bitmap.Dispose()
$titleFont.Dispose()
$panelTitleFont.Dispose()
$labelFont.Dispose()
$labelFontSmall.Dispose()
$valueFont.Dispose()
$captionFont.Dispose()
$footnoteFont.Dispose()

# Verify
$file = Get-Item $outPath
$image = [System.Drawing.Image]::FromFile($outPath)
Write-Output "OK: $($file.FullName) | Size: $($file.Length) bytes | $($image.Width)x$($image.Height)"
$image.Dispose()
