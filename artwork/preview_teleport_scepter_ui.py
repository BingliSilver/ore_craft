"""Static layout previews using the mod's real textures, not in-game screenshots."""

from io import BytesIO
from math import ceil, dist
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw

from preview_ore_machine_ui import Preview, ROOT, SCALE, FONT, TEXT, MUTED, CYAN, BORDER


CLIENT = Path.home() / '.gradle/caches/neoformruntime/artifacts/minecraft_1.20.1_client.jar'


class ScepterPreview(Preview):
    """Reuse the existing machine preview's texture sampling and pixel-aligned drawing."""

    def __init__(self, width, height):
        self.width, self.height = width, height
        self.image = Image.new('RGBA', (width * SCALE, height * SCALE))
        self.draw = ImageDraw.Draw(self.image)

    def frame(self):
        width, height, corner = self.width, self.height, 28
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

    def fit(self, text, width):
        if self.draw.textlength(text, FONT) / SCALE <= width:
            return text
        while text and self.draw.textlength(text + '...', FONT) / SCALE > width:
            text = text[:-1]
        return text + '...' if width >= self.draw.textlength('...', FONT) / SCALE else ''

    def button(self, text, x, y, width, height=18, active=True, hovered=False):
        self.box(x, y, width, height, '#252D36' if not active else '#405760' if hovered else '#2A333D')
        self.outline(x, y, width, height, CYAN if hovered and active else '#667989')
        self.box(x + 1, y + 1, width - 2, 1, '#3D4E5C')
        self.label(text, x + width / 2, y + (height - 8) // 2, TEXT if active else MUTED, 'center')

    def input(self, text, x, y, width, focused=False, search=False):
        self.box(x, y, width, 22, '#111921')
        self.outline(x, y, width, 22, CYAN if focused else BORDER)
        self.box(x + 1, y + 1, width - 2, 1, '#394A56')
        if search:
            self.outline(x + 6, y + 6, 7, 7, MUTED)
            self.box(x + 12, y + 12, 3, 3, MUTED)
        self.label(self.fit(text, width - 32), x + (21 if search else 7), y + 6, MUTED if search else TEXT)

    def icon(self, image, x, y):
        self.image.alpha_composite(image.convert('RGBA').resize((16 * SCALE, 16 * SCALE), Image.Resampling.NEAREST),
                                   (x * SCALE, y * SCALE))

    def header(self, english=False, count=True):
        self.frame()
        self.icon(Image.open(ROOT / 'src/main/resources/assets/ore_craft/textures/item/mineral_teleport_scepter.png'), 29, 23)
        label = '18 local waypoints' if english else '本维度坐标 18'
        title_right = self.width - 42 - self.draw.textlength(label, FONT) / SCALE if count else self.width - 30
        self.label(self.fit('Mineral Teleport Scepter' if english else '矿质传送权杖', title_right - 52), 52, 27)
        if count:
            self.label(label, self.width - 30, 27, MUTED, 'right')


def list_preview(english=False, empty=False):
    view = ScepterPreview(440, 334)
    view.header(english)
    view.panel(22, 50, 396, 210)
    view.input('No match' if empty else 'Search names or coordinates...' if english else '搜索名称或坐标...',
               30, 58, 379, search=True)
    samples = [
        ('Home Base' if english else '主基地', (128, 64, -256)),
        ('Deep Mine Entrance' if english else '深层矿洞入口', (-864, -42, 1024)),
        ('Mountain Outpost' if english else '山顶哨站', (320, 192, -640)),
        ('Village Workshop' if english else '村庄工坊', (100, 70, 0)),
        ('Coastal Village' if english else '海边村庄', (2400, 70, -1680)),
        ('A very long waypoint name to fit' if english else '这是一个用于验证长坐标名称自动省略的矿洞记录',
         (-29999999, 319, -29999999)),
    ]
    samples.sort(key=lambda entry: dist((128, 64, -256), entry[1]))
    with ZipFile(CLIENT) as archive:
        for index, (name, position) in enumerate([] if empty else samples):
            y = 88 + index * 28
            hovered = index == 1
            view.box(30, y, 370, 26, '#24565C' if hovered else '#19222C' if index % 2 == 0 else '#1C2732')
            view.outline(30, y, 370, 26, CYAN if hovered else '#586777')
            view.box(31, y + 1, 368, 1, '#5FAEB2' if hovered else '#34424E')
            if hovered:
                view.box(30, y + 1, 2, 24, CYAN)
            icon = Image.open(BytesIO(archive.read('assets/minecraft/textures/block/grass_block_side.png')))
            view.icon(icon, 35, y + 5)
            cost = max(1, ceil(dist((128, 64, -256), position))) * 10
            amount = f'{cost:,}' if cost < 10000 else f'{cost // 1000}k' if cost < 1000000 else f'{cost / 1000000:.2f}m'
            price = view.fit(f'Est. {amount} ME' if english else f'约 {amount} ME', 116)
            price_x = 276 - view.draw.textlength(price, FONT) / SCALE
            view.label(view.fit(name, price_x - 56 - 8), 56, y + 4)
            view.label(price, price_x, y + 4, CYAN if cost <= 8192 else '#FF9993')
            coordinates = f'X {position[0]}   Y {position[1]}   Z {position[2]}'
            view.label(view.fit(coordinates, 220), 56, y + 15, MUTED)
            view.button('Teleport' if english else '传送', 284, y + 4, 62, hovered=hovered)
            view.button('Delete' if english else '删除', 352, y + 4, 42)
    view.box(405, 88, 4, 166, '#111A22')
    if not empty:
        view.box(405, 88, 4, 55, '#667989')
    else:
        view.label('No matching waypoints' if english else '没有匹配的坐标', 220, 164, MUTED, 'center')
    view.button('<', 30, 264, 22, 20, active=False)
    view.button('>', 126, 264, 22, 20, active=not empty)
    view.label('1 / 1' if empty else '1 / 3', 89, 270, TEXT, 'center')
    view.label('0-0 / 0' if empty else '1-6 / 18', 248, 270, MUTED, 'center')
    view.button('Done' if english else '完成', 350, 264, 60, 20)
    view.label('Global ME: 8,192' if english else '全局 ME：8,192', 30, 298, MUTED)
    return view.image


def naming_preview(english=False):
    view = ScepterPreview(360, 244)
    view.header(english, count=False)
    view.panel(22, 50, 316, 114)
    view.label('Mark current position' if english else '标记当前位置', 30, 62, CYAN)
    view.label('Overworld' if english else '主世界', 330, 62, MUTED, 'right')
    view.label('X 128   Y 64   Z -256', 30, 84)
    view.label('Waypoint name' if english else '坐标名称', 30, 109)
    view.label('9 / 32' if english else '3 / 32', 330, 109, MUTED, 'right')
    view.input('Home Base' if english else '主基地', 30, 123, 300, focused=True)
    view.button('Save waypoint' if english else '保存坐标', 146, 174, 88, 20, hovered=True)
    view.button('Cancel' if english else '取消', 242, 174, 88, 20)
    return view.image


if __name__ == '__main__':
    list_preview().save(ROOT / 'artwork/mineral_teleport_scepter_list_preview.png')
    naming_preview().save(ROOT / 'artwork/mineral_teleport_scepter_naming_preview.png')
    list_preview(english=True).save(ROOT / 'build/teleport_list_english_preview.png')
    naming_preview(english=True).save(ROOT / 'build/teleport_naming_english_preview.png')
    list_preview(empty=True).save(ROOT / 'build/teleport_list_empty_preview.png')
