"""生成机器统一外壳：中心标识独立，柱子共用，八个金属包角沿棱边连续。"""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/ore_craft/models/block"
MODEL_NAME = "ore_machine_capped_cube"
MACHINES = ("ore_converter", "ore_converter_plus", "ore_conversion_machine", "ore_conversion_machine_plus")
# 每面划分为 4、8、4 个模型单位，四角均为 16×16 像素，中心为 32×32 像素。
GRID = ((0, 4), (4, 12), (12, 16))


def side_bounds(direction, u0, v0, u1, v1):
    """将正视侧面的 0～16 坐标换成模型范围；v 从上向下，与贴图一致。"""
    if direction == "north":
        return [16 - u1, 16 - v1, 0], [16 - u0, 16 - v0, 16]
    if direction == "south":
        return [u0, 16 - v1, 0], [u1, 16 - v0, 16]
    if direction == "west":
        return [0, 16 - v1, u0], [16, 16 - v0, u1]
    return [0, 16 - v1, 16 - u1], [16, 16 - v0, 16 - u0]


def folded_uv(direction, u0, u1, bottom=False):
    """把角块折到侧面；底部再沿侧面竖直镜像，令金属一直延伸到最下边缘。"""
    if direction == "north":
        uv, rotation = [16 - u0, 0, 16 - u1, 4], 0
    elif direction == "south":
        uv, rotation = [u0, 16, u1, 12], 0
    elif direction == "west":
        uv, rotation = [0, u1, 4, u0], 90
    else:
        uv, rotation = [16, 16 - u1, 12, 16 - u0], 90
    if bottom:
        # 旋转 90 度的面，其贴图横轴对应侧面竖轴，需要镜像 U 而不是 V。
        uv = [uv[2], uv[1], uv[0], uv[3]] if rotation else [uv[0], uv[3], uv[2], uv[1]]
    return uv, rotation


def generate_model():
    """将外表面分区，不叠加共面几何，避免包角闪烁；保留完整立方体尺寸。"""
    elements = []
    # 顶、底四角都使用同一张包角贴图，Plus 版的紫色仅影响顶面中央图案。
    for direction in ("up", "down"):
        for ix, (x0, x1) in enumerate(GRID):
            for iz, (z0, z1) in enumerate(GRID):
                cap = ix != 1 and iz != 1
                texture = "#cap" if cap else ("#top" if direction == "up" else "#bottom")
                # 底面观察方向与顶面相反，包角翻转 V 后仍使用同一世界坐标的颜色。
                uv = [x0, z0, x1, z1] if direction == "up" else [x0, z1, x1, z0]
                elements.append({"from": [x0, 0, z0], "to": [x1, 16, z1],
                                 "faces": {direction: {"uv": uv, "texture": texture, "cullface": direction}}})
    for direction in ("north", "south", "west", "east"):
        for iu, (u0, u1) in enumerate(GRID):
            for iv, (v0, v1) in enumerate(GRID):
                cap = iu != 1 and iv != 1
                center = iu == 1 and iv == 1
                start, end = side_bounds(direction, u0, v0, u1, v1)
                uv, rotation = folded_uv(direction, u0, u1, bottom=iv == 2) if cap else ([u0, v0, u1, v1], 0)
                # 两种机器、两个等级的外框只引用一个纹理，避免柱子随独立绘图偏移。
                texture = "#cap" if cap else ("#side" if center else "#frame")
                face = {"uv": uv, "texture": texture, "cullface": direction}
                if rotation:
                    face["rotation"] = rotation
                elements.append({"from": start, "to": end, "faces": {direction: face}})
    model = {"parent": "minecraft:block/block", "textures": {
        "particle": "#frame", "frame": "ore_craft:block/ore_converter_side", "cap": "ore_craft:block/ore_converter_top"
    }, "elements": elements}
    (MODELS / f"{MODEL_NAME}.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    for name in MACHINES:
        path = MODELS / f"{name}.json"
        model = json.loads(path.read_text(encoding="utf-8"))
        model["parent"] = f"ore_craft:block/{MODEL_NAME}"
        path.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate_model()
