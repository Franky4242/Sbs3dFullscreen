# Derives the PNG Store logo assets AppxManifest.xml references (packaging/msix/Assets/) from
# src/commonMain/composeResources/drawable/icon.png (the 256x256 master the cube app icon is
# already drawn at - much cleaner than upscaling icons/icon.ico's 32x32 embedded frame). Each tile
# is the cube glyph centered on a round white badge, with safe-zone padding per Microsoft's Store
# icon guidelines (https://learn.microsoft.com/windows/apps/design/style/app-icons-and-logos), not
# a plain edge-to-edge resize. Re-run whenever icon.png changes.

Add-Type -AssemblyName System.Drawing

$sourcePath = Join-Path $PSScriptRoot "..\src\commonMain\composeResources\drawable\icon.png"
$outDir = Join-Path $PSScriptRoot "..\packaging\msix\Assets"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$source = [System.Drawing.Image]::FromFile($sourcePath)

function New-RoundBadge([string]$destName, [int]$size) {
    $canvas = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # White round badge, ~2% inset so the anti-aliased edge doesn't get clipped by the canvas rect.
    $margin = [Math]::Round($size * 0.02)
    $badgeRect = New-Object System.Drawing.Rectangle($margin, $margin, ($size - 2 * $margin), ($size - 2 * $margin))
    $whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillEllipse($whiteBrush, $badgeRect)
    $whiteBrush.Dispose()

    # Cube glyph at ~66% of the canvas, centered - leaves safe-zone padding inside the circle so
    # it isn't clipped by Windows' own further masking/scaling at small taskbar sizes.
    $glyphSize = [Math]::Round($size * 0.66)
    $glyphOffset = [Math]::Round(($size - $glyphSize) / 2)
    $g.DrawImage($source, $glyphOffset, $glyphOffset, $glyphSize, $glyphSize)

    $g.Dispose()
    $destPath = Join-Path $outDir $destName
    $canvas.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Dispose()
    Write-Host "Wrote $destPath"
}

New-RoundBadge "Square44x44Logo.png" 44
New-RoundBadge "Square150x150Logo.png" 150
New-RoundBadge "StoreLogo.png" 50

$source.Dispose()
