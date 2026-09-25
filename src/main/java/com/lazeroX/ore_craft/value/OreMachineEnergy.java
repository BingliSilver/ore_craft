package com.lazeroX.ore_craft.value;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** 矿质机器的容器 ME 读写入口；普通容器存于物品组件，末影容器连接放置者账户。 */
public final class OreMachineEnergy {
    /** 工具类不允许创建实例。 */
    private OreMachineEnergy() {}

    /**
     * 判断物品是否属于可供机器存取 ME 的两类矿质容器。
     *
     * @param stack 待检查的物品栈
     * @return 普通或末影矿质容器为 true
     */
    public static boolean isContainer(ItemStack stack) {
        return stack.getItem() instanceof OreContainerItem || stack.getItem() instanceof EnderOreContainerItem;
    }

    /**
     * 读取当前可用 ME；无效容器返回零。
     *
     * @param server 当前服务器，用于查找末影容器连接的账户
     * @param owner 方块放置者 UUID，也是末影账户的所有者
     * @param stack 容器物品栈
     * @return 可支付的 ME 数量
     */
    public static long stored(MinecraftServer server, UUID owner, ItemStack stack) {
        if (stack.getItem() instanceof OreContainerItem normal) return normal.storedMe(stack);
        if (stack.getItem() instanceof EnderOreContainerItem)
            return OreConversionSavedData.get(server).account(owner).balance();
        return 0;
    }

    /**
     * 返回剩余容量；末影容器由全局账户的 long 上限约束。
     *
     * @param server 当前服务器
     * @param owner 末影账户所有者
     * @param stack 容器物品栈
     * @return 还可接收的 ME 数量
     */
    public static long remaining(MinecraftServer server, UUID owner, ItemStack stack) {
        if (stack.getItem() instanceof OreContainerItem normal)
            return normal.capacity() - normal.storedMe(stack);
        if (stack.getItem() instanceof EnderOreContainerItem)
            return Long.MAX_VALUE - OreConversionSavedData.get(server).account(owner).balance();
        return 0;
    }

    /**
     * 存入正数 ME 并返回需要写回槽位的容器副本；失败返回 null。
     * 末影容器本身不变，记账成功后返回原物品栈。
     *
     * @param server 当前服务器
     * @param owner 末影账户所有者
     * @param stack 当前容器物品栈
     * @param amount 正数 ME 存入量
     * @return 成功后的容器栈；容量不足或容器无效时为 null
     */
    public static ItemStack credit(MinecraftServer server, UUID owner, ItemStack stack, long amount) {
        if (amount <= 0 || amount > remaining(server, owner, stack)) return null;
        if (stack.getItem() instanceof OreContainerItem normal) {
            ItemStack updated = stack.copy();
            normal.setStoredMe(updated, normal.storedMe(stack) + amount);
            return updated;
        }
        return OreConversionSavedData.get(server).credit(owner, amount) ? stack : null;
    }

    /**
     * 扣除正数 ME 并返回需要写回的容器；余额不足时返回 null。
     *
     * @param server 当前服务器
     * @param owner 末影账户所有者
     * @param stack 当前容器物品栈
     * @param amount 正数 ME 扣除量
     * @return 成功后的容器栈；余额不足或容器无效时为 null
     */
    public static ItemStack debit(MinecraftServer server, UUID owner, ItemStack stack, long amount) {
        if (amount <= 0 || amount > stored(server, owner, stack)) return null;
        if (stack.getItem() instanceof OreContainerItem normal) {
            ItemStack updated = stack.copy();
            normal.setStoredMe(updated, normal.storedMe(stack) - amount);
            return updated;
        }
        return OreConversionSavedData.get(server).debit(owner, amount) ? stack : null;
    }
}
