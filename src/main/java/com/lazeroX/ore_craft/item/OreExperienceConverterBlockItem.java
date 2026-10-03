package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.block.entity.OreExperienceConverterBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Locale;

/**
 * 矿质经验转化器的可放置物品，在背包和创造模式提示中说明用途、经验单价及开关操作。
 * 红色高消耗提示始终展示，便于玩家在放置或开启机器前了解前期使用成本。
 * 该类仅补充物品提示；实际库存、倍率和 ME 扣费仍由放置后的方块实体管理。
 */
public final class OreExperienceConverterBlockItem extends BlockItem {
    /**
     * 创建关联经验转化器方块的物品，沿用原版方块物品的放置和堆叠行为。
     *
     * @param block 放置时生成的矿质经验转化器方块
     * @param properties 注册器提供的物品属性
     */
    public OreExperienceConverterBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 补充用途、实际经验单价、倍率选择、开关操作和红色高消耗警示，无需按 Shift 才显示。
     * 单价读取生产逻辑共用的常量，避免后续调价时提示与真实扣费不一致。
     *
     * @param stack 当前悬停的方块物品栈
     * @param context 原版提供的提示上下文
     * @param tooltip 待追加的本地化提示列表，本方法会修改此列表
     * @param flag 普通或高级物品提示标记
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.experience.purpose").withStyle(ChatFormatting.GRAY));
        // 使用分组数字展示精确费用，倍率只增加整轮产量，不改变每点经验的单价。
        String unitCost = String.format(Locale.ROOT, "%,d", OreExperienceConverterBlockEntity.ME_PER_EXPERIENCE);
        tooltip.add(Component.translatable("tooltip.ore_craft.experience.cost", unitCost).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.experience.toggle").withStyle(ChatFormatting.GRAY));
        // 高消耗警示独立成行并使用红色，始终可见，不依赖高级提示或附加按键。
        tooltip.add(Component.translatable("tooltip.ore_craft.experience.warning").withStyle(ChatFormatting.RED));
    }
}
