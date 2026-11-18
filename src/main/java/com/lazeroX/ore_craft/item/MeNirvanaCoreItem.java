package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 背包被动保命物品；服务端事件负责生命重构、扣费和玩家共享冷却，物品自身不存储能量。 */
public final class MeNirvanaCoreItem extends Item {
    /** @param properties 不可堆叠的基础属性；核心可以反复使用，不损耗耐久 */
    public MeNirvanaCoreItem(Properties properties) {
        super(properties);
    }

    /**
     * 展示触发条件、收费方式与增益时长，使玩家可提前准备足够 ME。
     * @param stack 当前物品 @param level 提示世界，可为空
     * @param tooltip 待追加的说明 @param flag 显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        // 逐行本地化，避免将中文写死在客户端逻辑中。
        for (String line : List.of("trigger", "cost", "effects", "cooldown")) {
            tooltip.add(Component.translatable("tooltip.ore_craft.nirvana." + line).withStyle(ChatFormatting.AQUA));
        }
    }
}
