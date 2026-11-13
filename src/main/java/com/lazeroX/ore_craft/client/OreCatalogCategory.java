package com.lazeroX.ore_craft.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;

/**
 * 转化桌与矿质合成台共用的客户端目录分类，枚举顺序即筛选按钮的展示顺序。
 * 分类只决定已学习目录的显示范围，不改变服务端的定价、学习状态或合成材料选择。
 */
enum OreCatalogCategory {
    /** 显示所有已学习且可展示的物品。 */
    ALL("gui.ore_craft.conversion.category.all"),
    /** 可放置的方块物品，优先于材料名称规则匹配。 */
    BLOCKS("gui.ore_craft.conversion.category.blocks"),
    /** 按注册名称识别的常见矿物、锭、粒、碎片和粉末。 */
    MATERIALS("gui.ore_craft.conversion.category.materials"),
    /** 挖掘工具、剑、弓、弩及钓鱼竿。 */
    TOOLS("gui.ore_craft.conversion.category.tools"),
    /** 护甲及不属于已知工具类型的耐久物品。 */
    EQUIPMENT("gui.ore_craft.conversion.category.equipment"),
    /** 未命中其他具体分类的物品。 */
    OTHER("gui.ore_craft.conversion.category.other");

    /** 沿用转化桌现有本地化键，两种界面的分类名称保持一致。 */
    private final String translation;

    /**
     * 创建固定分类；客户端界面重建时仍可保留同一个枚举值作为筛选条件。
     *
     * @param translation 分类按钮的本地化键
     */
    OreCatalogCategory(String translation) {
        this.translation = translation;
    }

    /** 返回按当前游戏语言解析的分类标签，供按钮显示及悬停提示使用。 */
    Component label() {
        return Component.translatable(translation);
    }

    /**
     * 根据物品类型和注册名称判断其是否属于当前分类，保持转化桌原有规则。
     *
     * @param item 从客户端物品注册表解析的物品
     * @return 全部分类始终返回 true，其他分类仅在识别结果相同时返回 true
     */
    boolean matches(Item item) {
        if (this == ALL) return true;
        OreCatalogCategory actual;
        // 先检查明确的方块、装备和工具类型，避免同名方块被材料后缀误归类。
        if (item instanceof BlockItem) actual = BLOCKS;
        else if (item instanceof ArmorItem || item.getDefaultInstance().isDamageableItem()
                && !(item instanceof DiggerItem || item instanceof SwordItem
                || item instanceof BowItem || item instanceof CrossbowItem || item instanceof FishingRodItem)) {
            actual = EQUIPMENT;
        } else if (item instanceof DiggerItem || item instanceof SwordItem || item instanceof BowItem
                || item instanceof CrossbowItem || item instanceof FishingRodItem) actual = TOOLS;
        else {
            // 延续现有名称约定：其他模组采用相同命名的矿物也能归入材料。
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            actual = path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_shard")
                    || path.endsWith("_dust") || path.startsWith("raw_")
                    || path.equals("coal") || path.equals("charcoal") || path.equals("diamond")
                    || path.equals("emerald") || path.equals("redstone") || path.equals("lapis_lazuli")
                    || path.equals("quartz") ? MATERIALS : OTHER;
        }
        return this == actual;
    }
}
