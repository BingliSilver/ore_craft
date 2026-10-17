package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 绿宝石煤炭物品。
 *
 * <p>通过 Forge 燃料接口提供燃烧时长，同时显示物品用途。</p>
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

    /** 每件燃烧 25,600 游戏刻，等于 16 件煤炭；1.20.1 不使用燃料数据映射。 */
    @Override
    public int getBurnTime(ItemStack stack, @Nullable RecipeType<?> recipeType) {
        return 25_600;
    }

    /** 为物品补充燃料倍率说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 使用翻译键，使燃料说明能够随当前语言切换。
        tooltip.add(Component.translatable("tooltip.ore_craft.emerald_coal.fuel")
                .withStyle(ChatFormatting.GREEN));
    }
}
