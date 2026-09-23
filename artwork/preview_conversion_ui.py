"""Render a static layout preview of the game-drawn conversion UI."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ARTWORK = ROOT / "artwork"
TEXTURE = ROOT / "src/main/resources/assets/ore_craft/textures/gui/ore_conversion_table.png"
OUTPUT = ARTWORK / "ore_conversion_ui_preview.png"
FONT = Path("C:/Windows/Fonts/msyh.ttc")
LAYOUT_X = 432 / 390
LAYOUT_Y = 228 / 206
SX = 4
SY = 4


def px(value: float) -> int:
    return round(value * LAYOUT_X * SX)


def py(value: float) -> int:
    return round(value * LAYOUT_Y * SY)


def box(x: float, y: float, width: float, height: float) -> tuple[int, int, int, int]:
    return px(x), py(y), px(x + width), py(y + height)


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT, size)


def main() -> None:
    image = Image.open(TEXTURE).convert("RGBA")
    controls = Image.new("RGBA", image.size)
    draw = ImageDraw.Draw(controls)

    def panel(x: int, y: int, width: int, height: int, fill: str = "#17212b") -> None:
        rect = box(x, y, width, height)
        draw.rectangle(rect, fill=fill, outline="#687d8d", width=3)
        draw.line((rect[0] + 3, rect[1] + 3, rect[2] - 3, rect[1] + 3), fill="#405461", width=3)

    def label(value: str, x: int, y: int, size: int = 25, color: str = "#e9f1f3") -> None:
        draw.text((px(x), py(y)), value, font=font(size), fill=color)

    panel(210, 29, 140, 16)
    draw.ellipse(box(216, 32, 8, 8), outline="#afbbc5", width=3)
    draw.line((px(222), py(39), px(226), py(43)),
              fill="#afbbc5", width=3)
    label("搜索已学习物品…", 231, 31, 26, "#afbbc5")

    categories = ("全部", "方块", "材料", "工具", "装备", "其他")
    for index, name in enumerate(categories):
        panel(205 + index * 25, 49, 24, 14, "#24565c" if index == 0 else "#2a333d")
        label(name, 209 + index * 25, 50, 27)

    panel(204, 66, 148, 98)
    for row in range(4):
        for col in range(5):
            panel(206 + col * 29, 68 + row * 24, 27, 23)
    panel(221, 164, 18, 13, "#252d36")
    panel(317, 164, 18, 13, "#252d36")
    label("‹", 226, 164, 31)
    label("›", 322, 164, 31)
    label("1 / 1", 267, 165, 27)

    label("矿质转化桌", 47, 27, 49)
    label("TRANSMUTATION TABLE", 48, 45, 22, "#afbbc5")
    label("当前矿质 · ME", 46, 57, 27, "#afbbc5")
    label("81k ME", 46, 67, 44, "#85f1ef")
    label("Shift + 左键：转化或学习", 46, 80, 26, "#afbbc5")
    label("物品栏", 42, 91, 28)

    for row in range(3):
        for col in range(9):
            panel(36 + col * 17, 102 + row * 17, 17, 17)
    for col in range(9):
        panel(36 + col * 17, 156, 17, 17)

    image = Image.alpha_composite(image, controls)
    image.save(OUTPUT, optimize=True)


if __name__ == "__main__":
    main()
