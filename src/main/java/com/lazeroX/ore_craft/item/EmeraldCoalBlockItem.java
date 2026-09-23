package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 绿宝石煤炭块对应的方块物品。
 *
 * <p>除允许玩家放置关联方块外，该物品还显示自身的燃料倍率说明。</p>
 */
public class EmeraldCoalBlockItem extends BlockItem {
    /**
     * 创建与指定方块关联的绿宝石煤炭块物品。
     *
     * @param block      放置该物品时生成的方块
     * @param properties 物品的基础属性
     */
    public EmeraldCoalBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** 为方块物品补充燃料倍率说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 方块物品沿用独立翻译键，以便展示与单个煤炭不同的燃烧时长。
        tooltip.add(Component.translatable("tooltip.ore_craft.emerald_coal_block.fuel")
                .withStyle(ChatFormatting.GREEN));
    }
}
