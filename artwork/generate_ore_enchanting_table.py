"""生成矿质附魔台模型；复用原版石材和已有像素贴图，不覆盖美术源图。"""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/ore_craft"
MODEL = ROOT / "models/block/ore_enchanting_table.json"
ELEMENTS = []


def cube(start, end, texture, *, uv=None, omit=(), rotation=None, glow=False):
    """添加长方体；石材按模型尺寸取样，金属和晶体可指定局部色块。"""
    faces = {}
    for direction in ("north", "east", "south", "west", "up", "down"):
        if direction in omit:
            continue
        face = {"texture": "#" + texture}
        # 默认 UV 由 Minecraft 按方块坐标生成，避免把整张纹理压进薄边。
        if uv is not None:
            face["uv"] = uv
        faces[direction] = face
    element = {"from": start, "to": end, "faces": faces}
    if rotation is not None:
        element["rotation"] = rotation
    if glow:
        element["neoforge_data"] = {"block_light": 15, "ambient_occlusion": False}
    ELEMENTS.append(element)


def build_model():
    """构造最高 12/16 格的祭台，给上方的动态附魔书留出空间。"""
    ELEMENTS.clear()
    # 底座宽、台身内收、台面外挑；相邻层只保留朝外的面。
    cube([1, 0, 1], [15, 1, 15], "stone", omit=("up",))
    cube([0, 1, 0], [16, 2.5, 16], "obsidian")
    cube([1, 2.5, 1], [15, 3, 15], "gold", uv=[1, 1, 3, 2])
    cube([2, 3, 2], [14, 8.5, 14], "stone", omit=("up", "down"))
    cube([1, 8.5, 1], [15, 9.25, 15], "obsidian")
    cube([0, 9.25, 0], [16, 9.75, 16], "gold", uv=[1, 1, 3, 2])
    cube([0, 9.75, 0], [16, 11, 16], "obsidian", omit=("down",))

    # 符文只占中央台面，保持完整纹样；四周留出暗色石框。
    cube([1, 11, 1], [15, 11.5, 15], "obsidian", omit=("up", "down"))
    cube([1, 11, 1], [15, 11.5, 15], "runes", uv=[0, 0, 16, 16],
         omit=("north", "east", "south", "west", "down"))

    # 短石柱收住四角，金属只作小包角，不再用大面积高亮晶柱。
    for x in (1, 13):
        for z in (1, 13):
            cube([x, 3, z], [x + 2, 8.5, z + 2], "obsidian", omit=("up", "down"))
            cube([x, 11.5, z], [x + 2, 12, z + 2], "gold", uv=[0, 0, 3, 3],
                 omit=("down",))

    # 四面均有小晶核。45 度旋转形成菱形，金托和晶体前后错开以避免闪面。
    # 金属只从已有金纹的一块内部取样，保留粗像素而不重复密集砖缝。
    for near in (True, False):
        frame_z = (1.5, 2) if near else (14, 14.5)
        gem_z = (1.25, 1.5) if near else (14.5, 14.75)
        rotation = {"origin": [8, 5.75, 8], "axis": "z", "angle": 45}
        cube([6.5, 4.25, frame_z[0]], [9.5, 7.25, frame_z[1]], "gold",
             uv=[0, 0, 3, 3], rotation=rotation)
        cube([7.1, 4.85, gem_z[0]], [8.9, 6.65, gem_z[1]], "crystal",
             uv=[0, 0, 3, 3], rotation=rotation, glow=True)
        rotation = {"origin": [8, 5.75, 8], "axis": "x", "angle": 45}
        cube([frame_z[0], 4.25, 6.5], [frame_z[1], 7.25, 9.5], "gold",
             uv=[0, 0, 3, 3], rotation=rotation)
        cube([gem_z[0], 4.85, 7.1], [gem_z[1], 6.65, 8.9], "crystal",
             uv=[0, 0, 3, 3], rotation=rotation, glow=True)

    textures = {
        "stone": "minecraft:block/polished_deepslate",
        "obsidian": "minecraft:block/obsidian",
        **{name: f"ore_craft:block/ore_enchanting_table_{name}"
           for name in ("gold", "crystal", "runes")},
        "particle": "minecraft:block/polished_deepslate",
    }
    MODEL.write_text(json.dumps({
        "parent": "minecraft:block/block", "textures": textures, "elements": ELEMENTS,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(ELEMENTS)} elements: {MODEL}")


if __name__ == "__main__":
    build_model()
