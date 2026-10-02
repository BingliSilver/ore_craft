# 生成书本界面的静态布局预览；字体用于估算排版，不代表游戏内实际渲染。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$backgroundPath = Join-Path $projectRoot 'src/main/resources/assets/ore_craft/textures/gui/ore_learning_book.png'
$iconPath = Join-Path $projectRoot 'src/main/resources/assets/ore_craft/textures/item/ore_learning_book.png'
$previewPath = Join-Path $PSScriptRoot 'ore_learning_ui_preview.png'
$background = [System.Drawing.Image]::FromFile($backgroundPath)
$icon = [System.Drawing.Image]::FromFile($iconPath)
$canvas = [System.Drawing.Bitmap]::new(432, 248, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$drawing = [System.Drawing.Graphics]::FromImage($canvas)
$labelFont = [System.Drawing.Font]::new('SimSun', 9, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$textFormat = [System.Drawing.StringFormat]::GenericTypographic.Clone()
$ink = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#493023'))
$muted = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#806548'))
$lineBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#B59359'))
$slotBorder = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#9D8158'))
$slotFill = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#DFCAA0'))
$slotShade = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#C5AC7E'))
$slotHighlight = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#F6E8C6'))

function Draw-CenteredLabel([string] $text, [int] $center, [int] $y, $brush) {
    $labelWidth = $drawing.MeasureString($text, $labelFont, 1000, $textFormat).Width
    $drawing.DrawString($text, $labelFont, $brush, [single]($center - $labelWidth / 2), [single]$y, $textFormat)
}

function Draw-Paragraph([string] $text, [int] $y, $brush) {
    $line = ''
    foreach ($character in $text.ToCharArray()) {
        $candidate = $line + $character
        if ($line.Length -gt 0 -and $drawing.MeasureString($candidate, $labelFont, 1000, $textFormat).Width -gt 140) {
            $drawing.DrawString($line, $labelFont, $brush, 48, $y, $textFormat)
            $y += 10
            $line = [string]$character
        } else {
            $line = $candidate
        }
    }
    $drawing.DrawString($line, $labelFont, $brush, 48, $y, $textFormat)
    return ($y + 10)
}

try {
    $drawing.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $drawing.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $drawing.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
    $drawing.DrawImage($background, [System.Drawing.Rectangle]::new(0, 0, 432, 248))
    foreach ($rowY in @(88, 106, 124, 158)) {
        for ($column = 0; $column -lt 9; $column++) {
            $slotX = 231 + $column * 18
            $drawing.FillRectangle($slotBorder, $slotX, $rowY - 1, 18, 18)
            $drawing.FillRectangle($slotFill, $slotX + 1, $rowY, 16, 16)
            $drawing.FillRectangle($slotShade, $slotX + 1, $rowY, 16, 1)
            $drawing.FillRectangle($slotHighlight, $slotX + 1, $rowY + 15, 16, 1)
        }
    }
    $drawing.FillRectangle($lineBrush, 66, 57, 104, 1)
    $drawing.FillRectangle($lineBrush, 263, 57, 100, 1)
    $drawing.DrawImage($icon, [System.Drawing.Rectangle]::new(106, 65, 24, 24))
    Draw-CenteredLabel '矿质学习宝典' 118 42 $ink
    Draw-CenteredLabel '物品栏' 313 42 $ink
    $nextY = Draw-Paragraph 'Shift 点击右页物品，即可学习。' 94 $ink
    $nextY = Draw-Paragraph '保留物品与 ME，无法提取物品。' ($nextY + 5) $muted
    $null = Draw-Paragraph '学习记录与矿质转化桌共用' ($nextY + 5) $muted
    $drawing.DrawString('选择需要学习的物品', $labelFont, $muted, 232, 67, $textFormat)
    $drawing.DrawString('快捷栏', $labelFont, $muted, 232, 146, $textFormat)
    Draw-CenteredLabel '学习结果会显示在这里' 118 169 $muted

    # 放大预览时保持像素边缘，游戏背景本身仍使用 432×248 资源。
    $preview = [System.Drawing.Bitmap]::new(1296, 744, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $previewDrawing = [System.Drawing.Graphics]::FromImage($preview)
    try {
        $previewDrawing.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $previewDrawing.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $previewDrawing.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $previewDrawing.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 1296, 744), 0, 0, 432, 248, [System.Drawing.GraphicsUnit]::Pixel)
        $preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $previewDrawing.Dispose()
        $preview.Dispose()
    }
} finally {
    $drawing.Dispose()
    $canvas.Dispose()
    $background.Dispose()
    $icon.Dispose()
    $labelFont.Dispose()
    $textFormat.Dispose()
    foreach ($brush in @($ink, $muted, $lineBrush, $slotBorder, $slotFill, $slotShade, $slotHighlight)) {
        $brush.Dispose()
    }
}
Write-Output $previewPath
