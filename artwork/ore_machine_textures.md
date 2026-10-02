# 机器贴图与包角模型

执行 `python artwork/prepare_ore_machine_textures.py` 会依次生成 64×64 游戏贴图、共用包角模型和静态方块预览。

- 接口普通版与 Plus 版侧面使用各自的 `ore_converter*_side_source.png` 独立源图。
- 两种机器的顶面共用对应等级的 `ore_converter*_top_source.png` 独立源图，避免再次使用旧图集里被截断的角块。
- 转化器使用 `ore_conversion_machine*_side_source.png` 独立源图，中心标识为四角星；接口标识为菱形。
- 四种方块模型继承 `ore_machine_capped_cube.json`。每个面分为 4、8、4 模型单位的九宫格，侧面中央 32×32 像素使用 `#side`，柱子与上下横框统一使用 `#frame`。普通、Plus、接口、转化器只在中心标识区域使用不同侧面像素。
- 八个金色角块统一使用 `#cap`，分别沿上下棱边折到侧面。四个方向通过镜像或旋转 UV，使侧面顶边、底边与对应水平面的角块取到相同像素；上下包角位置完全对齐。
- 顶面中央保留普通版青色、Plus 版紫色；两版顶面四角也共用同一金色贴图。底面四角使用相同包角，其余部分保留磨制黑石。
- 模型面片互不重叠，避免共面叠加产生闪烁。
- `ore_machine_blocks_preview.png` 按最终 JSON 模型的 UV 生成，包含四个侧面的包角映射；静态预览不启动游戏。

顶面和转化器中心标识由 imagegen 内置工具生成，提示词分别见 `ore_machine_top_fix_prompts.txt`、`ore_machine_shared_frame_prompts.txt`。美术变更时需保留共用外框和包角映射，只在指定中心区域区分机器。
