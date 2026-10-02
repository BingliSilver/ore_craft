"""缩放机器独立贴图源文件，生成统一外壳模型与静态预览；不会启动游戏。"""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

from generate_ore_machine_model import generate_model


ROOT = Path(__file__).resolve().parents[1]
ARTWORK = ROOT / "artwork"
TEXTURES = ROOT / "src/main/resources/assets/ore_craft/textures/block"
FACE_SIZE = 64
# 全部使用独立源图；侧面实际只在中心标识区域区分机器与等级，外框由模型共用。
FACE_SOURCES = {
    "ore_converter_side": ARTWORK / "ore_converter_side_source.png",
    "ore_converter_plus_side": ARTWORK / "ore_converter_plus_side_source.png",
    "ore_converter_top": ARTWORK / "ore_converter_top_source.png",
    "ore_converter_plus_top": ARTWORK / "ore_converter_plus_top_source.png",
    "ore_conversion_machine_side": ARTWORK / "ore_conversion_machine_side_source.png",
    "ore_conversion_machine_plus_side": ARTWORK / "ore_conversion_machine_plus_side_source.png",
}


def prepare_textures():
    """从独立源图输出 64×64 贴图；只缩放尺寸，最近邻采样保留像素边缘。"""
    for name, path in FACE_SOURCES.items():
        with Image.open(path) as source:
            if source.width != source.height:
                raise ValueError(f"{name} 的独立源图必须为正方形")
            source.convert("RGBA").resize((FACE_SIZE, FACE_SIZE), Image.Resampling.NEAREST).save(TEXTURES / f"{name}.png")


def model_side(side, top, direction):
    """按最终模型 UV 拼出侧面或顶面预览，包含统一外框及包角；不修改游戏贴图。"""
    model_path = ROOT / "src/main/resources/assets/ore_craft/models/block/ore_machine_capped_cube.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))
    result = Image.new("RGBA", (64, 64))
    textures = {"#side": side, "#top": top}
    for key in ("frame", "cap"):
        textures[f"#{key}"] = Image.open(TEXTURES / f"{model['textures'][key].split('/')[-1]}.png").convert("RGBA")
    for element in model["elements"]:
        face = element["faces"].get(direction)
        if face is None:
            continue
        start, end = element["from"], element["to"]
        if direction == "up":
            u0, u1 = start[0], end[0]
        elif direction == "north":
            u0, u1 = 16 - end[0], 16 - start[0]
        elif direction == "south":
            u0, u1 = start[0], end[0]
        elif direction == "west":
            u0, u1 = start[2], end[2]
        else:
            u0, u1 = 16 - end[2], 16 - start[2]
        v0, v1 = (start[2], end[2]) if direction == "up" else (16 - end[1], 16 - start[1])
        uv = face["uv"]
        corners = [(uv[0], uv[1]), (uv[0], uv[3]), (uv[2], uv[3]), (uv[2], uv[1])]
        shift = face.get("rotation", 0) // 90
        corners = corners[shift:] + corners[:shift]
        texture = textures[face["texture"]]
        # 使用 Minecraft BlockFaceUV 的顶点顺序，在像素中心采样，支持镜像及 90 度旋转。
        for y in range(v0 * 4, v1 * 4):
            fy = (y + 0.5 - v0 * 4) / ((v1 - v0) * 4)
            for x in range(u0 * 4, u1 * 4):
                fx = (x + 0.5 - u0 * 4) / ((u1 - u0) * 4)
                sample = [corners[0][axis] + fx * (corners[3][axis] - corners[0][axis])
                          + fy * (corners[1][axis] - corners[0][axis]) for axis in (0, 1)]
                result.putpixel((x, y), texture.getpixel(tuple(min(63, max(0, int(value * 4))) for value in sample)))
    return result


def cube(side_name, top_name):
    """将最终资源贴图投影到静态立方体；亮度调整仅用于预览，不写回贴图。"""
    side = Image.open(TEXTURES / f"{side_name}.png").convert("RGBA")
    top = Image.open(TEXTURES / f"{top_name}.png").convert("RGBA")
    south = model_side(side, top, "south")
    east = model_side(side, top, "east")
    top = model_side(side, top, "up")
    result = Image.new("RGBA", (160, 176))
    # 各面的逆仿射映射把画布坐标换回 64×64 贴图坐标，不引入插值模糊。
    faces = (
        (south, ((0, 40), (80, 80), (80, 176), (0, 136)), (0.8, 0, 0, -1 / 3, 2 / 3, -80 / 3), 0.84),
        (east, ((80, 80), (160, 40), (160, 136), (80, 176)), (0.8, 0, -64, 1 / 3, 2 / 3, -80), 0.66),
        (top, ((80, 0), (160, 40), (80, 80), (0, 40)), (0.4, 0.8, -32, -0.4, 0.8, 32), 1.0),
    )
    for texture, polygon, transform, brightness in faces:
        projected = ImageEnhance.Brightness(texture).enhance(brightness).transform(
            result.size, Image.Transform.AFFINE, transform, Image.Resampling.NEAREST
        )
        mask = Image.new("L", result.size)
        ImageDraw.Draw(mask).polygon(polygon, fill=255)
        result.paste(projected, (0, 0), mask)
    return result.resize((320, 352), Image.Resampling.NEAREST)


def prepare_preview():
    """并排展示普通和 Plus 方块，用于检查纹理密度与两种机器的辨识度。"""
    preview = Image.new("RGB", (1488, 472), "#101a22")
    draw = ImageDraw.Draw(preview)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22)
    note_font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 18)
    blocks = (
        ("矿质接口", "ore_converter_side", "ore_converter_top"),
        ("矿质转化器", "ore_conversion_machine_side", "ore_converter_top"),
        ("矿质接口 Plus", "ore_converter_plus_side", "ore_converter_plus_top"),
        ("矿质转化器 Plus", "ore_conversion_machine_plus_side", "ore_converter_plus_top"),
    )
    for index, (label, side, top) in enumerate(blocks):
        center_x = 186 + index * 372
        draw.text((center_x, 24), label, fill="#c8f7fa", font=font, anchor="mt")
        block_preview = cube(side, top)
        preview.paste(block_preview, (center_x - 160, 68), block_preview)
    draw.text((744, 440), "统一 64×64 像素贴图 · 青色普通版 / 紫色 Plus 版", fill="#afbbc5", font=note_font, anchor="mt")
    preview.save(ARTWORK / "ore_machine_blocks_preview.png")


if __name__ == "__main__":
    prepare_textures()
    generate_model()
    prepare_preview()
