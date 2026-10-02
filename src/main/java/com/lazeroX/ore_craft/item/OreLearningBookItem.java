package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.menu.OreLearningMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 转化桌的便携学习工具，使用期间只向持有者的共享账户登记物品类型。
 * 学习记录属于玩家存档，宝典本身不保存目录、储存 ME 或提供提取功能。
 */
public final class OreLearningBookItem extends Item {
    /** 菜单标题与物品名称分别由语言资源提供。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_learning_book");

    /**
     * 创建无耐久消耗的学习宝典；注册时限制为不可堆叠。
     *
     * @param properties 物品注册属性
     */
    public OreLearningBookItem(Properties properties) {
        super(properties);
    }

    /**
     * 手持右键空气时打开学习菜单，主手与副手都可使用。
     *
     * @param level 玩家所在世界
     * @param player 使用者
     * @param hand 持有宝典的手
     * @return 保留原物品栈的交互成功结果
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) openLearning(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 优先处理对方块的右键，保证面向箱子或转化桌时仍能打开宝典。
     *
     * @param stack 正在使用的宝典
     * @param context 方块交互上下文
     * @return 有玩家时消费本次交互，否则交由后续逻辑处理
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) openLearning(player, context.getHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 在服务端创建独立学习菜单，并同步价格与完整学习记录用于物品提示。
     *
     * @param player 使用者
     * @param hand 菜单生命周期内必须继续持有宝典的手
     */
    private static void openLearning(ServerPlayer player, InteractionHand hand) {
        // 额外数据只传递持有手；目标物品始终由服务端背包槽读取，不能由客户端任意指定。
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new OreLearningMenu(id, inventory, hand), TITLE), extra -> extra.writeEnum(hand));
        if (player.containerMenu instanceof OreLearningMenu menu) {
            OreConversionNetwork.sendPrices(player);
            OreConversionNetwork.sendState(player, menu.containerId);
        }
    }

    /**
     * 用一句简短说明展示宝典用途，具体操作与学习规则由书本界面说明。
     *
     * @param stack 当前宝典
     * @param context 提示上下文
     * @param tooltip 待追加的文字列表
     * @param flag 提示显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.learning.purpose").withStyle(ChatFormatting.GRAY));
    }
}
