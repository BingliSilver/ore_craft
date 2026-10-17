package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;

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
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return enchantment == Enchantments.BLOCK_FORTUNE;
    }

    /** 铁砧使用附魔书时也仅接受时运，避免通过另一入口添加无效附魔。 */
    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(book)
                .keySet().stream().allMatch(enchantment -> enchantment == Enchantments.BLOCK_FORTUNE);
    }

    /** 为采矿 TNT 补充箱子收集和时运使用方式说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level context,
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
