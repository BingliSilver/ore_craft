package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.GeologicalDetectorItem;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.GeologicalOreCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** 无物品槽的探测器设置菜单；服务端验证矿石注册名、范围和持有物品后保存，不接受坐标或费用。 */
public final class GeologicalDetectorMenu extends AbstractContainerMenu {
    /** 打开时的持有手与真实物品引用，用于拒绝换物品后的旧请求。 */
    private final InteractionHand hand;
    private final ItemStack openedStack;

    /** @param id 菜单编号 @param inventory 玩家背包 @param extra 服务端下发的持有手 */
    public GeologicalDetectorMenu(int id, Inventory inventory, FriendlyByteBuf extra) {
        this(id, inventory, extra.readEnum(InteractionHand.class));
    }

    /** @param id 菜单编号 @param inventory 玩家背包 @param hand 配置期间需要保持持有的手 */
    public GeologicalDetectorMenu(int id, Inventory inventory, InteractionHand hand) {
        super(ModMenus.GEOLOGICAL_DETECTOR_MENU.get(), id);
        this.hand = hand;
        openedStack = inventory.player.getItemInHand(hand);
    }

    /** @return 当前物品配置快照，仅供客户端初始化界面预览 */
    public ItemStack detector() { return openedStack; }

    /** @param player 操作者 @return 原探测器仍在同一只手中时有效；客户端等待权威同步 */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide() || player.getItemInHand(hand) == openedStack
                && openedStack.getItem() instanceof GeologicalDetectorItem;
    }

    /**
     * 验证并保存客户端选择，成功后同步实际手持物品并关闭设置界面。
     * @param player 请求发起者
     * @param oreId 矿石方块的完整注册名
     * @param rangeIndex 四档范围索引，其他数值一律拒绝
     */
    public void configure(ServerPlayer player, ResourceLocation oreId, int rangeIndex) {
        if (!stillValid(player) || player.isSpectator() || rangeIndex < 0
                || rangeIndex >= GeologicalDetectorItem.RADII.size() || !BuiltInRegistries.BLOCK.containsKey(oreId)) return;
        var ore = BuiltInRegistries.BLOCK.get(oreId);
        if (!GeologicalOreCatalog.isOre(ore)) return;
        GeologicalDetectorItem.configure(openedStack, ore, rangeIndex);
        player.getInventory().setChanged();
        // 无背包槽菜单不能依赖 broadcastChanges 同步手持物品；副手使用原版全局索引 40。
        player.connection.send(new ClientboundContainerSetSlotPacket(
                ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0,
                hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40, openedStack));
        player.closeContainer();
    }

    /** 无物品槽，不提供 Shift 移动物品。@param player 操作者 @param slot 槽编号 @return 空栈 */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
