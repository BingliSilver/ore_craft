package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.menu.MineralTeleportScepterMenu;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.player.TeleportWaypointSavedData;
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
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 矿质传送权杖：潜行右键命名当前位置，普通右键管理当前玩家在本维度的标记。
 * 主副手均可使用；权杖本身不保存坐标、不消耗耐久，位置捕获和界面打开由服务端执行。
 */
public final class MineralTeleportScepterItem extends Item {
    /** @param properties 注册时提供的不可堆叠物品属性 */
    public MineralTeleportScepterItem(Properties properties) {
        super(properties);
    }

    /**
     * 对空气右键也能标记，记录玩家脚底位置而非准星朝向。
     * @param level 当前世界
     * @param player 使用者
     * @param hand 本次使用权杖的手
     * @return 消费当前操作，防止另一只手重复打开界面
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) openMenu(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 在方块自身交互之前打开权杖界面，面向箱子等交互方块时也保持相同操作。
     * @param stack 使用中的权杖
     * @param context 玩家、持有手和目标方块上下文
     * @return 存在玩家时消费操作，没有玩家时交由后续处理
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) openMenu(player, context.getHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 捕获待命名位置并打开无槽位菜单；本维度列表另发状态包，避免超出菜单打开附加数据上限。
     * @param player 服务端使用者
     * @param hand 菜单整个生命周期内需要保持持有权杖的手
     */
    private static void openMenu(ServerPlayer player, InteractionHand hand) {
        TeleportWaypoint pending = player.isShiftKeyDown() ? TeleportWaypoint.capture(player) : null;
        if (pending != null && TeleportWaypointSavedData.get(player).waypoints(player).size()
                >= TeleportWaypointSavedData.MAX_WAYPOINTS) {
            player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter.limit"), true);
            return;
        }
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inventory, owner) -> new MineralTeleportScepterMenu(id, inventory, hand, pending),
                Component.translatable("container.ore_craft.mineral_teleport_scepter")), buffer -> {
            buffer.writeEnum(hand);
            buffer.writeBoolean(pending != null);
            if (pending != null) pending.encode(buffer);
        });
        if (player.containerMenu instanceof MineralTeleportScepterMenu menu) menu.sync(player, "");
    }

    /**
     * 展示两种右键操作、玩家共享存档规则及全局 ME 的距离计费规则。
     * @param stack 当前权杖
     * @param level 提示上下文，可为空
     * @param tooltip 待追加的本地化提示
     * @param flag 提示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.teleport_scepter.mark").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.ore_craft.teleport_scepter.open").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.teleport_scepter.shared").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.teleport_scepter.cost",
                TeleportWaypoint.ME_PER_BLOCK).withStyle(ChatFormatting.GOLD));
    }
}
