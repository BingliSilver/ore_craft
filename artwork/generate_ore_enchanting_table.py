"""Generate the static mineral enchanting table model and its pixel textures."""

import json
import random
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/ore_craft"
TEXTURES = ROOT / "textures/block"
MODEL = ROOT / "models/block/ore_enchanting_table.json"
TEXTURES.mkdir(parents=True, exist_ok=True)
random.seed(84)


def save(name, image):
    image.save(TEXTURES / f"ore_enchanting_table_{name}.png")


def stone(name, color, gem=None):
    image = Image.new("RGBA", (16, 16), color)
    draw = ImageDraw.Draw(image)
    shades = ["#29282d", "#35343a", "#47434a", "#585158", "#6a6062"]
    for y in range(0, 16, 4):
        for x in range(0, 16, 4):
            draw.rectangle((x, y, x + 3, y + 3), fill=random.choice(shades))
            draw.line((x, y, x + 3, y), fill="#70696a")
            draw.line((x, y + 3, x + 3, y + 3), fill="#25242a")
    if gem:
        dark, main, light = gem
        draw.polygon(((8, 2), (13, 7), (10, 13), (5, 13), (2, 7)), fill=dark)
        draw.polygon(((8, 3), (12, 7), (8, 12), (4, 7)), fill=main)
        draw.polygon(((8, 3), (8, 12), (4, 7)), fill=light)
        draw.rectangle((7, 6, 8, 8), fill="#ecffff")
    save(name, image)


stone("stone", "#36343a")
stone("diamond", "#35343b", ("#075571", "#20c8ed", "#94faff"))
stone("emerald", "#35343b", ("#075d37", "#12cb77", "#8effc8"))
stone("redstone", "#35343b", ("#7b151a", "#f3342d", "#ff9d75"))


def tiled(name, background, base, highlight, shadow):
    image = Image.new("RGBA", (16, 16), background)
    draw = ImageDraw.Draw(image)
    for y in range(0, 16, 4):
        for x in range(0, 16, 4):
            draw.rectangle((x, y, x + 3, y + 3), fill=base)
            draw.line((x, y, x + 3, y), fill=highlight)
            draw.line((x, y + 3, x + 3, y + 3), fill=shadow)
    save(name, image)


tiled("gold", "#a65e1b", "#df951f", "#ffe079", "#96521b")
tiled("rim", "#625b5e", "#82777b", "#b6a8a5", "#4e494e")
tiled("crystal", "#047db2", "#15b9e1", "#a8ffff", "#07628c")


image = Image.new("RGBA", (16, 16), "#242933")
draw = ImageDraw.Draw(image)
for y in range(0, 16, 4):
    for x in range(0, 16, 4):
        draw.rectangle((x, y, x + 3, y + 3), fill=random.choice(["#252a34", "#2e3541", "#383a43"]))
draw.rectangle((1, 1, 14, 14), outline="#0986b4", width=1)
draw.rectangle((3, 3, 12, 12), outline="#38defb", width=1)
draw.polygon(((8, 3), (12, 8), (8, 12), (4, 8)), outline="#84faff")
draw.rectangle((7, 7, 8, 8), fill="#c9ffff")
save("runes", image)


def cube(a, b, texture, top=None, glow=False, omit=()):
    # 小尺寸角块和书页也需要呈现完整纹样，不能使用按尺寸自动裁切的默认 UV。
    faces = {direction: {"texture": "#" + (top if direction == "up" and top else texture),
                         "uv": [0, 0, 16, 16]}
             for direction in ("north", "east", "south", "west", "up", "down") if direction not in omit}
    element = {"from": a, "to": b, "faces": faces}
    if glow:
        element["neoforge_data"] = {"block_light": 15, "ambient_occlusion": False}
    elements.append(element)


elements = []
# 底座层次互相错开，装饰表面不会与主方块共面。
cube([0, 0, 0], [16, 2, 16], "stone", "rim")
cube([1, 2, 1], [15, 8, 15], "stone")
cube([0, 8, 0], [16, 10, 16], "rim", "rim")
cube([1, 10, 1], [15, 11, 15], "stone", "runes")

# 四角足、晶柱和矿石角块逐层覆盖，朝外表面的坐标各不相同。
for x, z, mineral in ((0, 0, "diamond"), (13, 0, "redstone"),
                      (0, 13, "diamond"), (13, 13, "emerald")):
    cube([x - .08, .1, z - .08], [x + 3.08, 2.12, z + 3.08], "gold")
    # 顶面被上层石板完全遮住，省略它以免与石芯顶面共面。
    cube([x, 2.05, z], [x + 3, 8, z + 3], "crystal", glow=True, omit=("up",))
    cap_x = 0 if x == 0 else 12
    cap_z = 0 if z == 0 else 12
    cube([cap_x - .08, 9.7, cap_z - .08], [cap_x + 4.08, 10.15, cap_z + 4.08], "gold")
    cube([cap_x, 10.1, cap_z], [cap_x + 4, 12.1, cap_z + 4], mineral, mineral)

# 侧面的金条和宝石镶片位于主方块之外，保留清晰的深度间隔。
for x in (5, 10):
    cube([x, 2.2, -.08], [x + 1, 7.8, .92], "gold")
    cube([x, 2.2, 15.08], [x + 1, 7.8, 16.08], "gold")
for z in (5, 10):
    cube([-.08, 2.2, z], [.92, 7.8, z + 1], "gold")
    cube([15.08, 2.2, z], [16.08, 7.8, z + 1], "gold")
for front in (-.14, 15.14):
    cube([6, 3, front], [10, 7, front + 1], "gold")
    gem_front = -.22 if front < 0 else 15.22
    cube([7, 4, gem_front], [9, 6, gem_front + 1], "crystal", glow=True)
for front in (-.14, 15.14):
    cube([front, 3, 6], [front + 1, 7, 10], "gold")
    gem_front = -.22 if front < 0 else 15.22
    cube([gem_front, 4, 7], [gem_front + 1, 6, 9], "crystal", glow=True)

# 上方只保留书本基座；真正的书由方块实体使用原版模型逐帧渲染。
cube([6, 11, 5], [10, 11.7, 11], "gold")
cube([7, 11.7, 6], [9, 12.2, 10], "crystal", glow=True)

textures = {name: f"ore_craft:block/ore_enchanting_table_{name}" for name in
            ("stone", "diamond", "emerald", "redstone", "gold", "rim", "crystal", "runes")}
textures["particle"] = textures["stone"]
MODEL.write_text(json.dumps({"parent": "minecraft:block/block", "textures": textures, "elements": elements},
                            ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
