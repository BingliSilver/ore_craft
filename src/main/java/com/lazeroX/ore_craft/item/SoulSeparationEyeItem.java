package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.event.SoulSeparation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 魂离之眼：一次支付 4096 ME，开启限时灵魂出窍。
 * 携带末影容器或穿戴末影容器附魔盔甲时使用全局账户，否则使用背包普通容器。
 * 物品注册实例不持有玩家状态；计时、身体和回归均由服务端 SoulSeparation 管理。
 */
public final class SoulSeparationEyeItem extends Item {
    /** 创建可重复使用、不可堆叠且没有耐久的魂离之眼。@param properties 基础物品属性 */
    public SoulSeparationEyeItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /**
     * 对空气使用时申请出窍；客户端只预测动作，扣费与条件检查在服务端执行。
     * @param level 当前世界
     * @param player 使用者
     * @param hand 使用的手
     * @return 消费此次交互，失败原因由服务端提示
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) SoulSeparation.start(serverPlayer);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 方块自身交互前启动出窍，避免箱子等界面抢占魂离之眼的右键。
     * @param stack 当前魂离之眼
     * @param context 目标方块及使用者
     * @return 已处理本次交互
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) SoulSeparation.start(player);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 显示费用、持续时间、身体风险和提前回归方式。
     * @param stack 当前物品
     * @param level 预览时可以为空的世界
     * @param tooltip 待追加的说明列表
     * @param flag 提示级别
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.soul_eye.cost", SoulSeparation.COST,
                SoulSeparation.DURATION_TICKS / 20).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.soul_eye.flight").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.soul_eye.body").withStyle(ChatFormatting.RED));
        tooltip.add(Component.translatable("tooltip.ore_craft.soul_eye.return").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
