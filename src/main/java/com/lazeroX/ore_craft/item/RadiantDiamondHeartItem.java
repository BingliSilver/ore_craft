package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 可主动使用的辉耀钻石之心。
 *
 * <p>玩家手持右键后获得 1 分钟急迫 IV，随后进入 20 秒冷却并消耗 1 点耐久。
 * 物品的全部 6 点耐久耗尽后会被销毁。</p>
 */
public class RadiantDiamondHeartItem extends Item {
    /** 急迫效果的持续时间：1 分钟。 */
    private static final int HASTE_DURATION_TICKS = 20 * 60;

    /** 急迫 IV 对应的零基放大等级。 */
    private static final int HASTE_AMPLIFIER = 3;

    /** 每次成功使用后的冷却时间：20 秒。 */
    private static final int USE_COOLDOWN_TICKS = 20 * 20;

    /**
     * 创建辉耀钻石之心。
     *
     * @param properties 物品的基础属性
     */
    public RadiantDiamondHeartItem(Properties properties) {
        super(properties);
    }

    /** 为物品补充效果、冷却时间和耐久消耗说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 将能力拆成多行，方便玩家在物品栏中快速确认效果与使用限制。
        tooltip.add(Component.translatable("tooltip.ore_craft.radiant_diamond_heart.effect")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.radiant_diamond_heart.cooldown")
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.radiant_diamond_heart.durability")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 响应玩家手持右键，为玩家施加急迫效果并消耗物品耐久。
     *
     * @param level 物品被使用时所在的世界
     * @param player 使用物品的玩家
     * @param hand 持有物品的手
     * @return 本次使用的交互结果及使用后的物品栈
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 冷却期间拒绝使用，且不消耗耐久或刷新效果。
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }

        // 状态效果、冷却和耐久均由服务端修改，客户端随后接收同步结果。
        if (!level.isClientSide()) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.DIG_SPEED,
                    HASTE_DURATION_TICKS,
                    HASTE_AMPLIFIER
            ));
            player.getCooldowns().addCooldown(this, USE_COOLDOWN_TICKS);

            // hurtAndBreak 会在达到最大损伤值时销毁物品并广播破损动画。
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        }

        return InteractionResultHolder.consume(stack);
    }
}
