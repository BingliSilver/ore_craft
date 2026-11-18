package com.lazeroX.ore_craft.value;

import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 随身道具在服务端主线程查询和支付 ME 的统一入口，每次操作均以玩家当前装备和容器为准。 */
public final class CarriedContainerEnergy {
    /** 无状态工具类无需实例。 */
    private CarriedContainerEnergy() {}

    /**
     * 查询本次可用于付款的余额。接入末影账户时只计共享余额，否则合计随身普通容器。
     * @param player 付款玩家
     * @return 可用 ME，上限为 long 能表示的最大正数
     */
    public static long available(ServerPlayer player) {
        if (EnderContainerAccess.hasAccess(player)) return OreConversionSavedData.get(player).account(player).balance();
        return ordinaryBalance(player);
    }

    /** @param player 容器持有者 @return 随身普通容器的 ME 总量，饱和累加以防溢出 */
    private static long ordinaryBalance(ServerPlayer player) {
        long balance = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof OreContainerItem container) {
                balance += Math.min(Long.MAX_VALUE - balance, container.storedMe(stack));
            }
        }
        return balance;
    }

    /**
     * 一次性支付完整费用，成功后同步账户或容器变化。余额不足时保留所有 ME。
     * @param player 付款玩家
     * @param cost 本次完整费用，单位 ME，必须为正数
     * @return 是否成功付清
     */
    public static boolean pay(ServerPlayer player, long cost) {
        if (cost <= 0) return false;
        if (EnderContainerAccess.hasAccess(player)) {
            if (!OreConversionSavedData.get(player).debit(player, cost)) return false;
            OreConversionNetwork.sendState(player, -1);
            return true;
        }
        // 同一服务端线程内先确认总额，再按槽位扣款，允许多个普通容器合付一笔费用。
        if (ordinaryBalance(player) < cost) return false;
        long remaining = cost;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof OreContainerItem container) {
                long stored = container.storedMe(stack);
                long debit = Math.min(remaining, stored);
                if (debit == 0) continue;
                container.setStoredMe(stack, stored - debit);
                remaining -= debit;
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }
}
