package com.lazeroX.ore_craft.value;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.Tags;

import java.util.Comparator;
import java.util.List;

/** 跨模组矿石目录；按同步后的方块标签识别矿石，并为未标记的常见矿石注册名提供兜底。 */
public final class GeologicalOreCatalog {
    /** 目录工具不保存世界状态，不创建实例。 */
    private GeologicalOreCatalog() {}

    /**
     * 每次打开设置时重建目录，使数据包标签更新后立即生效。
     * 石质、深板岩等变种分别列出，精确匹配玩家选择的矿石方块。
     * @return 按完整注册名排序的原版及模组矿石列表
     */
    public static List<Block> entries() {
        return BuiltInRegistries.BLOCK.stream().filter(GeologicalOreCatalog::isOre)
                .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString())).toList();
    }

    /** @param block 注册方块 @return 是否可作为矿石目标；兼容 Forge、通用标签及原版矿石分类标签 */
    public static boolean isOre(Block block) {
        if (block == Blocks.AIR) return false;
        if (block == Blocks.ANCIENT_DEBRIS || block.defaultBlockState().is(Tags.Blocks.ORES)) return true;
        boolean tagged = block.builtInRegistryHolder().tags().anyMatch(tag -> {
            ResourceLocation id = tag.location();
            String path = id.getPath();
            return (id.getNamespace().equals("forge") || id.getNamespace().equals("c")
                    || id.getNamespace().equals("minecraft"))
                    && (path.equals("ores") || path.startsWith("ores/") || path.endsWith("_ores"));
        });
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        // 仅对常见的 *_ore 后缀兜底；ore_* 还可能是转化桌、机器等，不能一概当作矿石。
        return tagged || path.endsWith("_ore");
    }
}
