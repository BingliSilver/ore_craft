param(
    [string]$SourcePath = (Join-Path $PSScriptRoot '../src/main/resources/assets/ore_craft/textures/item/mineral_time_scepter.png'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '../src/main/resources/assets/ore_craft/textures/item/mineral_teleport_scepter.png'),
    [string]$PreviewPath = (Join-Path $PSScriptRoot 'mineral_teleport_scepter_preview.png')
)

Add-Type -AssemblyName System.Drawing

# Preserve the reference's native pixel grid, metalwork, handle, and transparent silhouette.
$reference = [System.Drawing.Bitmap]::FromFile([System.IO.Path]::GetFullPath($SourcePath))
$sprite = New-Object System.Drawing.Bitmap 64, 64
$palette = @{
    Outline = [System.Drawing.ColorTranslator]::FromHtml('#17272C')
    GoldShade = [System.Drawing.ColorTranslator]::FromHtml('#8F5823')
    Gold = [System.Drawing.ColorTranslator]::FromHtml('#DEAF5C')
    GoldLight = [System.Drawing.ColorTranslator]::FromHtml('#FFF0B1')
    Deep = [System.Drawing.ColorTranslator]::FromHtml('#062C2E')
    Dark = [System.Drawing.ColorTranslator]::FromHtml('#075345')
    Green = [System.Drawing.ColorTranslator]::FromHtml('#0B8461')
    Teal = [System.Drawing.ColorTranslator]::FromHtml('#26D99D')
    Light = [System.Drawing.ColorTranslator]::FromHtml('#75FFD3')
    Glow = [System.Drawing.ColorTranslator]::FromHtml('#E3FFF4')
}

try {
    if ($reference.Width -ne 64 -or $reference.Height -ne 64) {
        throw 'The time scepter reference must use its original 64x64 pixel grid.'
    }
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $pixel = $reference.GetPixel($x, $y)
            if ($pixel.A -eq 0) { continue }
            $hue = $pixel.GetHue()
            if ($hue -ge 235 -and $hue -le 330 -and ($pixel.B - $pixel.G) -gt 18) {
                # Convert purple energy into emerald/cyan while retaining the original brightness detail.
                $red = [int][Math]::Round($pixel.G * 0.9 + $pixel.R * 0.1)
                $blue = [int][Math]::Round($pixel.R * 0.52 + $pixel.B * 0.34)
                $pixel = [System.Drawing.Color]::FromArgb($pixel.A, $red, $pixel.B, $blue)
            }
            $sprite.SetPixel($x, $y, $pixel)
        }
    }

    # Replace the diagonal hourglass chamber with a faceted gold-framed portal.
    # Coordinates follow the reference's staff axis, so the original mounts stay aligned.
    $axis = [Math]::Sqrt(0.5)
    for ($y = 9; $y -le 43; $y++) {
        for ($x = 20; $x -le 54; $x++) {
            $dx = $x - 37
            $dy = $y - 26
            $along = ($dx - $dy) * $axis
            $across = ($dx + $dy) * $axis
            if ([Math]::Abs($along) -gt 13 -or [Math]::Abs($across) -gt 7.5) { continue }

            # An octagonal boundary gives crisp pixel-art facets instead of antialiased curves.
            $u = [Math]::Abs($along) / 12.8
            $v = [Math]::Abs($across) / 7.2
            $edge = [Math]::Max([Math]::Max($u, $v), ($u + $v) / 1.48)
            if ($edge -gt 1.04) {
                $sprite.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
                continue
            }

            $tone = if ($edge -gt 0.95) { 'Outline' }
                    elseif ($edge -gt 0.82) {
                        if ($across -lt -1) { 'GoldLight' }
                        elseif ($across -gt 2) { 'GoldShade' }
                        else { 'Gold' }
                    }
                    elseif ($edge -gt 0.70) { 'Light' }
                    elseif ($edge -gt 0.59) { 'Green' }
                    else {
                        # Quantized spiral bands identify a portal rather than another gemstone or clock.
                        $radius = [Math]::Sqrt(($along / 11.2) * ($along / 11.2) + ($across / 6.2) * ($across / 6.2))
                        $angle = [Math]::Atan2($across / 6.2, $along / 11.2)
                        $spiral = [Math]::Sin($angle - $radius * 11)
                        if ($radius -lt 0.12) { 'Glow' }
                        elseif ($spiral -gt 0.78) { 'Light' }
                        elseif ($spiral -gt 0.34) { 'Teal' }
                        elseif ($spiral -gt -0.10) { 'Green' }
                        elseif ($spiral -gt -0.55) { 'Dark' }
                        else { 'Deep' }
                    }
            $sprite.SetPixel($x, $y, $palette[$tone])
        }
    }

    $sprite.Save([System.IO.Path]::GetFullPath($OutputPath), [System.Drawing.Imaging.ImageFormat]::Png)

    # Keep a nearest-neighbor comparison for review without launching Minecraft.
    $preview = New-Object System.Drawing.Bitmap 1024, 576
    $graphics = [System.Drawing.Graphics]::FromImage($preview)
    $font = New-Object System.Drawing.Font 'Consolas', 17
    try {
        $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#25282C'))
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.DrawString('TIME SCEPTER / REFERENCE', $font, [System.Drawing.Brushes]::WhiteSmoke, 30, 18)
        $graphics.DrawString('TELEPORT SCEPTER / 64 x 64', $font, [System.Drawing.Brushes]::WhiteSmoke, 542, 18)
        $graphics.DrawImage($reference, 0, 56, 512, 512)
        $graphics.DrawImage($sprite, 512, 56, 512, 512)
        $preview.Save([System.IO.Path]::GetFullPath($PreviewPath), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $font.Dispose()
        $graphics.Dispose()
        $preview.Dispose()
    }
} finally {
    $sprite.Dispose()
    $reference.Dispose()
}
