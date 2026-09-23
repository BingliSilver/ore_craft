package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 用于绿宝石与绿宝石煤炭配方的绿宝石粒。
 */
public class EmeraldNuggetItem extends Item {
    /**
     * 创建绿宝石粒。
     *
     * @param properties 物品的基础属性
     */
    public EmeraldNuggetItem(Properties properties) {
        super(properties);
    }

    /** 为物品补充合成用途说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 使用本地化文本提示玩家该材料的主要用途。
        tooltip.add(Component.translatable("tooltip.ore_craft.emerald_nugget.usage")
                .withStyle(ChatFormatting.GREEN));
    }
}
