"""Render static layout previews; sample items and values are illustrative, not a game test."""

from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
CLIENT = Path.home() / '.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar'
SCALE = 4
TEXT, MUTED = '#E9F1F3', '#AFBBC5'
CYAN, BORDER = '#85F1EF', '#647989'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 9 * SCALE)
BACKGROUND = Image.open(ROOT / 'src/main/resources/assets/ore_craft/textures/gui/ore_conversion_table.png').convert('RGBA')


class Preview:
    """Mirror the native Java drawing layout with readable preview text."""

    def __init__(self, width):
        self.width = width
        self.image = Image.new('RGBA', (width * SCALE, 244 * SCALE))
        self.draw = ImageDraw.Draw(self.image)

    def box(self, x, y, width, height, color):
        self.draw.rectangle((x * SCALE, y * SCALE, (x + width) * SCALE - 1,
                             (y + height) * SCALE - 1), fill=color)

    def outline(self, x, y, width, height, color):
        self.box(x, y, width, 1, color)
        self.box(x, y + height - 1, width, 1, color)
        self.box(x, y, 1, height, color)
        self.box(x + width - 1, y, 1, height, color)

    def gradient(self, x, y, width, height, top, bottom):
        first = tuple(bytes.fromhex(top.removeprefix('#')))
        last = tuple(bytes.fromhex(bottom.removeprefix('#')))
        for row in range(height):
            ratio = row / max(1, height - 1)
            color = tuple(round(a + (b - a) * ratio) for a, b in zip(first, last))
            self.box(x, y + row, width, 1, color)

    def label(self, text, x, y, color=TEXT, align='left'):
        width = self.draw.textlength(text, FONT) / SCALE
        if align == 'center':
            x -= width / 2
        elif align == 'right':
            x -= width
        self.draw.text((round(x * SCALE), y * SCALE), text, font=FONT, fill=color, anchor='lt')

    def panel(self, x, y, width, height):
        self.texture(x, y, width, height, 990, 210, 450, 400)
        overlay = Image.new('RGBA', (width * SCALE, height * SCALE), (10, 16, 22, 90))
        self.image.alpha_composite(overlay, (x * SCALE, y * SCALE))
        self.outline(x, y, width, height, BORDER)
        self.box(x + 1, y + 1, width - 2, 1, '#394A56')

    def texture(self, x, y, width, height, source_x, source_y, source_width, source_height):
        tile = BACKGROUND.crop((source_x, source_y, source_x + source_width, source_y + source_height))
        tile = tile.resize((width * SCALE, height * SCALE), Image.Resampling.NEAREST)
        self.image.alpha_composite(tile, (x * SCALE, y * SCALE))

    def frame(self):
        width, height, corner = self.width, 244, 28
        self.texture(12, 12, width - 24, height - 24, 990, 210, 450, 400)
        for offset in range(corner, width - corner, 32):
            segment = min(32, width - corner - offset)
            self.texture(offset, 2, segment, 12, 340, 30, 240 * segment // 32, 86)
            self.texture(offset, height - 14, segment, 12, 350, 809, 240 * segment // 32, 86)
        for offset in range(corner, height - corner, 48):
            segment = min(48, height - corner - offset)
            self.texture(2, offset, 16, segment, 40, 210, 115, 350 * segment // 48)
            self.texture(width - 18, offset, 16, segment, 1585, 210, 115, 350 * segment // 48)
        for x, y, sx, sy in ((0, 0, 0, 0), (width - corner, 0, 1528, 0),
                              (0, height - corner, 0, 720), (width - corner, height - corner, 1528, 720)):
            self.texture(x, y, corner, corner, sx, sy, 200, 192)
        self.outline(19, 18, width - 38, height - 36, '#367F92')

    def divider(self, x):
        self.texture(x - 4, 18, 8, 208, 850, 120, 30, 660)
        self.texture(x - 14, 0, 28, 28, 795, 0, 142, 145)
        self.texture(x - 14, 216, 28, 28, 795, 767, 142, 145)

    def slot(self, x, y, machine=False, accent=CYAN):
        inset = 5 if machine else 1
        size = 16 + inset * 2
        self.box(x - inset, y - inset, size, size, '#111A22')
        self.outline(x - inset, y - inset, size, size, accent if machine else '#627383')
        self.box(x - inset + 1, y - inset + 1, size - 2, 1, '#34595C' if machine else '#2B3946')
        if machine:
            self.outline(x - inset + 2, y - inset + 2, size - 4, size - 4, BORDER)

    def item(self, name, x, y, count=None):
        if name.endswith('_ore_container'):
            icon = Image.open(ROOT / f'src/main/resources/assets/ore_craft/textures/item/{name}.png').convert('RGBA')
        else:
            with ZipFile(CLIENT) as archive:
                icon = Image.open(BytesIO(archive.read(f'assets/minecraft/textures/item/{name}.png'))).convert('RGBA')
        icon = icon.resize((16 * SCALE, 16 * SCALE), Image.Resampling.NEAREST)
        self.image.alpha_composite(icon, (x * SCALE, y * SCALE))
        if count is not None:
            self.label(str(count), x + 17, y + 10, align='right')

    def arrow(self, x, y, length, color):
        self.box(x, y, length - 4, 1, BORDER)
        self.box(x + length - 5, y - 2, 2, 5, color)
        self.box(x + length - 3, y - 1, 2, 3, color)
        self.box(x + length - 1, y, 1, 1, color)

    def chrome(self, title, subtitle):
        self.frame()
        if self.width > 220:
            self.divider(220)
        if self.width > 220:
            self.panel(22, 48, 176, 178)
            self.box(30, 130, 160, 1, '#53616E')
        else:
            self.panel(22, 48, 176, 82)
            self.panel(27, 145, 166, 81)
        self.label(title, 29, 23)
        self.label(subtitle, 29, 36, MUTED)
        self.box(self.width - 60, 21, 28, 13, '#24565C')
        self.outline(self.width - 60, 21, 28, 13, BORDER)
        self.label('标准', self.width - 55, 23, CYAN)
        self.label('物品栏', 29, 135)
        for y in (148, 166, 184, 206):
            for column in range(9):
                self.slot(29 + column * 18, y)
        self.box(29, 202, 162, 1, '#53616E')
        self.box(30, 106, 160, 6, '#09151C')
        self.outline(30, 106, 160, 6, BORDER)
        self.gradient(31, 107, 98, 4, CYAN, '#329B9A')
        self.label('5 秒 / 最多 16 个', 30, 117, MUTED)
        self.label('62%', 190, 117, CYAN, 'right')
        for index, name in enumerate(('diamond', 'emerald', 'gold_ingot', 'iron_ingot', 'coal')):
            self.item(name, 29 + index * 18, 148, 32)


def main():
    interface = Preview(220)
    interface.chrome('矿质传输接口', '物品 → ME')
    interface.slot(54, 76, True, CYAN)
    interface.slot(150, 76, True, CYAN)
    interface.arrow(81, 84, 61, CYAN)
    interface.label('输入', 62, 59, CYAN, 'center')
    interface.label('容器', 158, 59, CYAN, 'center')
    interface.item('diamond', 54, 76, 16)
    interface.item('gold_ore_container', 150, 76)
    interface.image.save(ROOT / 'artwork/ore_converter_ui_preview.png')

    machine = Preview(440)
    machine.chrome('矿质转化器', 'ME → 物品')
    machine.panel(242, 48, 176, 178)
    for x, title, color in ((34, '容器', CYAN), (102, '选择', CYAN), (170, '输出', CYAN)):
        machine.slot(x, 76, True, color)
        machine.label(title, x + 8, 59, color, 'center')
    machine.arrow(62, 84, 31, CYAN)
    machine.arrow(130, 84, 31, CYAN)
    machine.item('gold_ore_container', 34, 76)
    machine.item('diamond', 102, 76)
    machine.item('diamond', 170, 76, 16)
    machine.label('已学习物品', 250, 55)
    machine.label('ME ↓', 402, 55, CYAN, 'right')
    machine.box(249, 70, 154, 18, '#111921')
    machine.outline(249, 70, 154, 18, BORDER)
    machine.box(250, 71, 152, 1, '#394A56')
    machine.outline(255, 75, 6, 6, MUTED)
    machine.box(260, 80, 3, 2, MUTED)
    machine.label('搜索物品...', 270, 75, MUTED)
    samples = [('nether_star', '下界之星', 139264), ('emerald', '绿宝石', 16384),
               ('diamond', '钻石', 8192), ('gold_ingot', '金锭', 2048),
               ('iron_ingot', '铁锭', 256), ('coal', '煤炭', 128)]
    for index, (icon, title, price) in enumerate(samples):
        y = 94 + index * 20
        active = icon == 'diamond'
        machine.box(250, y, 152, 18, '#24565C' if active else '#19222C' if index % 2 == 0 else '#1C2732')
        machine.outline(250, y, 152, 18, CYAN if active else '#586777')
        machine.box(251, y + 1, 150, 1, '#5FAEB2' if active else '#34424E')
        if active:
            machine.box(250, y + 1, 2, 16, CYAN)
        machine.item(icon, 253, y + 1)
        # 预览也为精确价格保留空间，按实际字体宽度省略长名称，避免给出错误的布局效果。
        price_text = f'{price:,} ME'
        name_width = 397 - machine.draw.textlength(price_text, FONT) / SCALE - 273 - 7
        if machine.draw.textlength(title, FONT) / SCALE > name_width:
            while title and machine.draw.textlength(title + '...', FONT) / SCALE > name_width:
                title = title[:-1]
            title = title + '...' if name_width >= machine.draw.textlength('...', FONT) / SCALE else ''
        machine.label(title, 273, y + 5)
        machine.label(price_text, 397, y + 5, CYAN if active else TEXT, 'right')
    machine.box(407, 94, 4, 118, '#111A22')
    machine.box(407, 94, 4, 26, '#667989')
    machine.label('1–6 / 27', 250, 214, MUTED)
    machine.box(102, 132, 86, 12, '#2A333D')
    machine.outline(102, 132, 86, 12, '#667989')
    machine.box(103, 133, 84, 1, '#3D4E5C')
    machine.label('清空选择', 145, 134, TEXT, 'center')
    machine.image.save(ROOT / 'artwork/ore_conversion_machine_ui_preview.png')


if __name__ == '__main__':
    main()
