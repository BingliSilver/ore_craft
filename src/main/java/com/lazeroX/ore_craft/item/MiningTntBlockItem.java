package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 可以接受时运附魔的采矿 TNT 方块物品。
 */
public class MiningTntBlockItem extends BlockItem {
    /**
     * 创建采矿 TNT 方块物品。
     *
     * @param block 放置该物品时生成的方块
     * @param properties 物品的基础属性
     */
    public MiningTntBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** 允许任意数量的采矿 TNT 作为一个物品栈进入附魔流程。 */
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return !stack.isEmpty();
    }

    /** 为附魔台提供与钻石工具相近的附魔能力值。 */
    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return 10;
    }

    /** 采矿 TNT 只接受时运附魔。 */
    @Override
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.FORTUNE);
    }

    /** 允许附魔台把时运视为该物品的主要附魔。 */
    @Override
    public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.FORTUNE);
    }

    /** 为采矿 TNT 补充箱子收集和时运使用方式说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.mining_tnt.storage")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.ore_craft.mining_tnt.fortune")
                .withStyle(ChatFormatting.AQUA));
    }
}
