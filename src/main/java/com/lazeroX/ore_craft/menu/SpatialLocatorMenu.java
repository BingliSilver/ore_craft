package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.SpatialLocatorItem;
import com.lazeroX.ore_craft.register.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 定位器设置菜单绑定打开时的物品栈，由服务端检查并保存两个回收开关。 */
public final class SpatialLocatorMenu extends AbstractContainerMenu {
    /** 打开菜单的手和物品引用，防止延迟到达的保存请求覆盖其他物品。 */
    private final InteractionHand hand;
    private final ItemStack locator;

    /** @param id 菜单编号 @param inventory 客户端背包 @param data 打开时服务端发送的持有手 */
    public SpatialLocatorMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, data.readEnum(InteractionHand.class));
    }

    /** @param id 菜单编号 @param inventory 玩家背包 @param hand 配置的定位器所在手 */
    public SpatialLocatorMenu(int id, Inventory inventory, InteractionHand hand) {
        super(ModMenus.SPATIAL_LOCATOR_MENU.get(), id);
        this.hand = hand;
        locator = inventory.player.getItemInHand(hand);
    }

    /** @return 手持定位器的已保存设置，供界面初始化 */
    public int options() { return SpatialLocatorItem.options(locator); }

    /** @param player 操作者 @return 服务端仍持有打开时的定位器时有效 */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide() || player.getItemInHand(hand) == locator
                && locator.getItem() instanceof SpatialLocatorItem;
    }

    /** @param player 操作者 @param options 两个开关的组合 @return 是否成功保存并关闭菜单 */
    @Override
    public boolean clickMenuButton(Player player, int options) {
        if (!(player instanceof ServerPlayer server) || !stillValid(player) || player.isSpectator()
                || options < 0 || options > SpatialLocatorItem.ALL_OPTIONS) return false;
        SpatialLocatorItem.configure(locator, options);
        player.getInventory().setChanged();
        // 菜单没有物品槽，直接同步实际主手槽或副手槽。
        server.connection.send(new ClientboundContainerSetSlotPacket(ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                0, hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40, locator));
        server.closeContainer();
        return true;
    }

    /** @param player 操作者 @param slot 槽位编号 @return 无物品槽，始终返回空栈 */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
