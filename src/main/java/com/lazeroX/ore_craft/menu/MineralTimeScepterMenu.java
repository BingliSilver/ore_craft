package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.MineralTimeScepterItem;
import com.lazeroX.ore_craft.register.ModMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 矿质时间权杖的无物品槽设置菜单。
 *
 * <p>原版菜单按钮包只发送预设编号，服务端以菜单编号和仍在主手中的原物品校验请求。
 * 选中的倍率和秒数通过数据槽同步给客户端，真实设置写入权杖数据组件。</p>
 */
public final class MineralTimeScepterMenu extends AbstractContainerMenu {
    /** 数据槽零保存倍率。 */
    private static final int MULTIPLIER_DATA = 0;
    /** 数据槽一保存持续秒数。 */
    private static final int DURATION_DATA = 1;
    /** 倍率按钮使用编号 0 至 5；时长按钮接在其后。 */
    private static final int DURATION_BUTTON_START = MineralTimeScepterItem.MULTIPLIERS.size();
    /** 菜单打开时主手中的权杖引用，用于拒绝切换物品后的过期按钮操作。 */
    private final ItemStack openedStack;
    /** 只同步两个小整数，不占用玩家背包槽。 */
    private final SimpleContainerData settings = new SimpleContainerData(2);

    /**
     * 从网络创建客户端菜单；设置由物品当前组件及随后到达的数据槽同步提供。
     *
     * @param id 本次菜单编号
     * @param inventory 玩家背包
     * @param extra 本菜单不需要额外打开数据
     */
    public MineralTimeScepterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory);
    }

    /**
     * 创建绑定当前主手权杖的菜单并初始化同步数据槽。
     *
     * @param id 本次菜单编号
     * @param inventory 玩家背包
     */
    public MineralTimeScepterMenu(int id, Inventory inventory) {
        super(ModMenus.MINERAL_TIME_SCEPTER_MENU.get(), id);
        openedStack = inventory.player.getMainHandItem();
        settings.set(MULTIPLIER_DATA, MineralTimeScepterItem.multiplier(openedStack));
        settings.set(DURATION_DATA, MineralTimeScepterItem.durationSeconds(openedStack));
        addDataSlots(settings);
    }

    /**
     * 菜单只在原权杖仍位于主手时有效，客户端等待服务端同步期间允许显示。
     *
     * @param player 当前打开菜单的玩家
     * @return 原权杖仍在主手时为 true
     */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide()
                || player.getMainHandItem() == openedStack
                && openedStack.getItem() instanceof MineralTimeScepterItem;
    }

    /**
     * 处理固定倍率或时长按钮；网络包只能提交按钮编号，不能直接指定任意费用。
     * 客户端先更新本地预览，服务端再校验并写入真实物品组件。
     *
     * @param player 操作玩家
     * @param buttonId 点击的预设按钮编号
     * @return 有效且成功处理时为 true
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!stillValid(player)) return false;
        if (buttonId >= 0 && buttonId < DURATION_BUTTON_START) {
            settings.set(MULTIPLIER_DATA, MineralTimeScepterItem.MULTIPLIERS.get(buttonId));
        } else {
            int durationIndex = buttonId - DURATION_BUTTON_START;
            if (durationIndex < 0 || durationIndex >= MineralTimeScepterItem.DURATIONS.size()) return false;
            settings.set(DURATION_DATA, MineralTimeScepterItem.DURATIONS.get(durationIndex));
        }

        // 客户端选择仅改变界面预览；服务器确认后物品组件才成为权威设置。
        if (player instanceof ServerPlayer serverPlayer) {
            MineralTimeScepterItem.saveConfiguration(openedStack, multiplier(), durationSeconds());
            // 本菜单没有背包槽；主动同步主手物品，避免设置完成后客户端仍显示旧提示。
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                    ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0,
                    serverPlayer.getInventory().selected, openedStack));
        }
        return true;
    }

    /** 返回目前同步到界面的倍率。 */
    public int multiplier() {
        return settings.get(MULTIPLIER_DATA);
    }

    /** 返回目前同步到界面的施加时长，单位秒。 */
    public int durationSeconds() {
        return settings.get(DURATION_DATA);
    }

    /** 菜单没有物品槽，因此快捷转移始终返回空物品。 */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }
}
