package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.event.SpatialMissileStrikes;
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
 * 空间定位器的交互入口：向服务端申请一次有限目标的空间导弹打击。
 * 注册实例不保存玩家或目标状态；每个目标的锁定、扣弹和命中由独立临时实体维护。
 */
public final class SpatialLocatorItem extends Item {
    /** @param properties 基础物品属性；保持现有堆叠规则，不引入耐久或额外能源费用 */
    public SpatialLocatorItem(Properties properties) {
        super(properties);
    }

    /**
     * 对空气右键申请扫描；客户端只预测挥手，扫描和弹药检查始终在服务端执行。
     * @param level 当前世界
     * @param player 使用者
     * @param hand 当前操作手
     * @return 已消费交互；具体结果由服务端消息反馈
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) SpatialMissileStrikes.start(serverPlayer);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 右键方块时也优先发起定位，避免箱子等方块抢占操作；冷却由服务端入口统一检查。
     * @param stack 当前定位器
     * @param context 方块交互上下文
     * @return 消费本次右键，不再触发方块界面
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) SpatialMissileStrikes.start(player);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 展示扫描、弹药和友军保护规则，避免将锁定提示误解为已经扣除导弹。
     * @param stack 当前物品
     * @param level 提示世界，可为空
     * @param tooltip 待追加的本地化说明
     * @param flag 提示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.spatial_locator.scan",
                SpatialMissileStrikes.RADIUS, SpatialMissileStrikes.MAX_TARGETS).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.spatial_locator.attack").withStyle(ChatFormatting.RED));
        tooltip.add(Component.translatable("tooltip.ore_craft.spatial_locator.ammo").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.ore_craft.spatial_locator.safety").withStyle(ChatFormatting.GRAY));
    }
}
