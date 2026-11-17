<#
.SYNOPSIS
重新生成六种物品图标、导弹原生 3D 模型和静态预览。
.DESCRIPTION
保留旧命令入口，统一调用 Python 生成器，避免再次运行时覆盖为旧版平面贴图。
#>
$generator = Join-Path $PSScriptRoot 'generate_missile_models.py'
& python $generator
if ($LASTEXITCODE -ne 0) { throw '物品资源生成失败，请检查 Python 与 Pillow 环境。' }