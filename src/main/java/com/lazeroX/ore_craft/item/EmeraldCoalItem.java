package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 绿宝石煤炭物品。
 *
 * <p>具体燃烧时间通过数据资源注册，本类负责物品实例及其说明文字。</p>
 */
public class EmeraldCoalItem extends Item {
    /**
     * 创建绿宝石煤炭。
     *
     * @param properties 物品的基础属性
     */
    public EmeraldCoalItem(Properties properties) {
        super(properties);
    }

    /** 为物品补充燃料倍率说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 使用翻译键，使燃料说明能够随当前语言切换。
        tooltip.add(Component.translatable("tooltip.ore_craft.emerald_coal.fuel")
                .withStyle(ChatFormatting.GREEN));
    }
}
