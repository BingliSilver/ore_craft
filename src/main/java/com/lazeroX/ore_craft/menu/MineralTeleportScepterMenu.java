package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.event.MineralTeleportation;
import com.lazeroX.ore_craft.item.MineralTeleportScepterItem;
import com.lazeroX.ore_craft.network.MineralTeleportScepterNetwork;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.player.TeleportWaypointSavedData;
import com.lazeroX.ore_craft.register.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 绑定当前持有权杖的无槽位菜单，负责命名、删除及生成传送门的服务端校验。
 * 客户端仅持有展示快照，真实目的地总是从当前玩家的世界存档读取。
 */
public final class MineralTeleportScepterMenu extends AbstractContainerMenu {
    /** 打开菜单的玩家编号，禁止借用菜单对象操作其他账户。 */
    private final UUID ownerId;
    /** 打开菜单时的持有手与物品引用，换手、丢弃或切换权杖都会使旧菜单失效。 */
    private final InteractionHand hand;
    private final ItemStack openedStack;
    /** 菜单打开时的维度；外部传送切换维度后关闭旧菜单，防止使用过期的列表或命名快照。 */
    private final ResourceLocation openedDimension;
    /** Shift 右键那一刻的坐标快照；普通右键列表菜单没有待命名位置。 */
    @Nullable
    private final TeleportWaypoint pending;
    /** 是否仍处于命名阶段；保存成功后结束命名并关闭菜单，重复请求不会创建第二条记录。 */
    private boolean naming;
    /** 客户端显示用的当前维度不可变列表，其他维度标记保留在存档中但不发送。 */
    private List<TeleportWaypoint> waypoints = List.of();
    /** 最近一次服务端操作结果的本地化键后缀；空字符串表示没有消息。 */
    private String status = "";
    /** 每次收到服务端快照递增，供界面刷新按钮及解除等待状态。 */
    private int revision;

    /** @param id 菜单编号 @param inventory 客户端背包 @param extra 持有手及可选的待命名坐标 */
    public MineralTeleportScepterMenu(int id, Inventory inventory, FriendlyByteBuf extra) {
        this(id, inventory, extra.readEnum(InteractionHand.class), extra.readBoolean() ? TeleportWaypoint.decode(extra) : null);
    }

    /**
     * 创建服务端菜单并绑定原物品，客户端通过附加数据使用同一构造路径。
     * @param id 菜单编号
     * @param inventory 玩家背包
     * @param hand 打开菜单的持有手
     * @param pending 待命名快照，普通列表为 null
     */
    public MineralTeleportScepterMenu(int id, Inventory inventory, InteractionHand hand, @Nullable TeleportWaypoint pending) {
        super(ModMenus.MINERAL_TELEPORT_SCEPTER_MENU.get(), id);
        ownerId = inventory.player.getUUID();
        this.hand = hand;
        openedStack = inventory.player.getItemInHand(hand);
        openedDimension = inventory.player.level().dimension().location();
        this.pending = pending;
        naming = pending != null;
    }

    /** @param player 当前操作者 @return 玩家存活、维度未改变且原权杖仍在原手中时有效；客户端等待服务端关闭 */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide() || player.isAlive() && ownerId.equals(player.getUUID())
                && openedDimension.equals(player.level().dimension().location())
                && player.getItemInHand(hand) == openedStack && openedStack.getItem() instanceof MineralTeleportScepterItem;
    }

    /**
     * 执行客户端请求；名称以外的目的地信息均由菜单快照或当前玩家存档决定。
     * 保存成功后关闭命名窗口并显示快捷栏提示，失败则保留输入框供玩家修改。
     * @param player 网络连接对应的玩家
     * @param request 已解码的操作及标记编号
     */
    public void handle(ServerPlayer player, MineralTeleportScepterNetwork.Request request) {
        if (!stillValid(player) || player.containerMenu != this) return;
        TeleportWaypointSavedData data = TeleportWaypointSavedData.get(player);
        if (request.operation() == MineralTeleportScepterNetwork.Operation.SAVE) {
            if (!naming || pending == null) return;
            String name = request.name().strip();
            if (!TeleportWaypoint.isValidName(name)) {
                sync(player, "invalid_name");
            } else if (!data.add(player, pending.withName(name))) {
                sync(player, "limit");
            } else {
                naming = false;
                // 直接由服务端关闭菜单，不发送列表状态，避免客户端短暂跳转到传送列表。
                player.closeContainer();
                player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter.saved"), true);
            }
            return;
        }
        if (naming || request.waypointId() == null) return;
        TeleportWaypoint destination = data.waypoints(player).stream()
                .filter(entry -> entry.id().equals(request.waypointId())).findFirst().orElse(null);
        if (destination == null) {
            sync(player, "missing");
        } else if (!destination.dimension().equals(player.level().dimension().location())) {
            // 列表不可见的异维度条目不能通过手工发包删除或生成入口。
            sync(player, "wrong_dimension");
        } else if (request.operation() == MineralTeleportScepterNetwork.Operation.DELETE) {
            sync(player, data.remove(player, request.waypointId()) ? "deleted" : "missing");
        } else {
            openPortal(player, destination);
        }
    }

    /**
     * 选择坐标后只生成前方入口，成功关闭列表；进入检测和实际传送由实体独立负责。
     * 生成失败保留列表和错误提示，方便玩家关闭界面并调整站位。
     * @param player 当前服务端玩家
     * @param waypoint 从本人存档查出的目标
     */
    private void openPortal(ServerPlayer player, TeleportWaypoint waypoint) {
        String error = MineralTeleportation.openPortal(player, waypoint);
        if (error != null) {
            sync(player, error);
            return;
        }
        player.closeContainer();
        player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter.portal_opened", waypoint.name()), true);
    }

    /**
     * 只同步本人当前维度的坐标，其他维度的数据保留到玩家进入相应维度后再展示。
     * 同步全局余额供费用提示使用；每次真正传送仍重新读取服务端余额。
     * @param player 当前菜单所有者
     * @param status 本地化结果后缀
     */
    public void sync(ServerPlayer player, String status) {
        List<TeleportWaypoint> localWaypoints = TeleportWaypointSavedData.get(player).waypoints(player).stream()
                .filter(entry -> entry.dimension().equals(player.level().dimension().location())).toList();
        OreConversionNetwork.sendState(player, -1);
        MineralTeleportScepterNetwork.sync(player, new MineralTeleportScepterNetwork.State(
                containerId, localWaypoints, naming, status));
    }

    /** @param entries 服务端列表 @param naming 服务端命名阶段 @param status 服务端操作结果，仅供客户端调用 */
    public void applyState(List<TeleportWaypoint> entries, boolean naming, String status) {
        waypoints = List.copyOf(entries);
        this.naming = naming;
        this.status = status;
        revision++;
    }

    /** @return 当前维度的客户端坐标列表，不含其他维度的标记 */
    public List<TeleportWaypoint> waypoints() { return waypoints; }

    /** @return 待命名位置；普通列表菜单为 null */
    @Nullable
    public TeleportWaypoint pending() { return pending; }

    /** @return 是否显示名称输入框 */
    public boolean naming() { return naming; }

    /** @return 最近的服务端结果键后缀，空字符串代表没有结果 */
    public String status() { return status; }

    /** @return 客户端快照修订号 */
    public int revision() { return revision; }

    /** @param player 操作者 @param slotIndex 槽位编号 @return 无槽位菜单不支持 Shift 移动物品 */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) { return ItemStack.EMPTY; }
}
