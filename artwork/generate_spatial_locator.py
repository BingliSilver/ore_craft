"""复用同系列像素渲染器，生成空间定位器的透明图标和静态预览。"""

from PIL import Image, ImageDraw, ImageFont

from generate_missile_models import ARTWORK, TEXTURES, box, render


def main():
    """将金属定位终端、末影雷达屏与顶部天线绘制为 64×64 物品图标。"""
    parts = []
    # 厚实的银灰外壳与铜色护角沿用导弹组件配色，正面屏幕留出独立识别区域。
    box(parts, (3.5, 2, 6), (12.5, 12.5, 9), 1)
    box(parts, (4, 2.5, 5.6), (12, 12, 6), 3)
    box(parts, (4.7, 5.2, 5.2), (11.3, 11.2, 5.6), 0)
    box(parts, (5.1, 5.6, 5), (10.9, 10.8, 5.2), 11)
    # 正交准星、分段扫描框和中心亮点表现定位主题；这些细节仅用于图标显示。
    for start, end in [((5.5, 8, 4.8), (10.5, 8.3, 5)),
                       ((7.8, 6, 4.8), (8.1, 10.4, 5)),
                       ((6, 6.5, 4.8), (6.3, 9.5, 5)),
                       ((9.7, 6.5, 4.8), (10, 9.5, 5)),
                       ((6.3, 6.5, 4.8), (9.7, 6.8, 5)),
                       ((6.3, 9.2, 4.8), (9.7, 9.5, 5))]:
        box(parts, start, end, 12)
    box(parts, (7.3, 7.5, 4.6), (8.7, 8.9, 4.8), 13)
    box(parts, (9, 9.5, 4.6), (9.7, 10.2, 4.8), 7)
    # 四个护角与侧边握持条增加层次，避免小尺寸下变成单一色块。
    for x in (3.5, 11.2):
        for y in (2, 11.2):
            box(parts, (x, y, 5.4), (x + 1.3, y + 1.3, 6.5), 6)
    for y in (4, 6, 8, 10):
        box(parts, (3.2, y, 6.6), (3.5, y + 0.7, 8.4), 0)
    # 下方控制键和末影紫天线区分芯片类材料，突出可手持终端外观。
    for x in (5.2, 7.5, 9.8):
        box(parts, (x, 3.4, 5), (x + 1, 4.4, 5.6), 14 if x == 7.5 else 2)
    box(parts, (5, 12.5, 7), (6.2, 13.2, 8.2), 6)
    box(parts, (5.3, 13.2, 7.3), (5.9, 15, 7.9), 2)
    box(parts, (5, 14.7, 7), (6.2, 15.5, 8.2), 15)
    box(parts, (10, 12.5, 7), (11, 13.5, 8), 12)
    sprite = render(parts, (16, -25, -12))
    sprite.save(TEXTURES / 'spatial_locator.png')
    preview = Image.new('RGB', (460, 430), '#182634')
    enlarged = sprite.resize((320, 320), Image.Resampling.NEAREST)
    preview.paste(enlarged, (70, 20), enlarged)
    draw = ImageDraw.Draw(preview)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 24)
    draw.text((230, 375), '空间定位器', font=font, fill='#e3eef2', anchor='mm')
    preview.save(ARTWORK / 'spatial_locator_preview.png')
    print('Generated spatial locator texture and preview.')


if __name__ == '__main__':
    main()
