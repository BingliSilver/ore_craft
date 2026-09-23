package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** 保存服务端同步到客户端的余额、价格目录和价格查询状态。 */
public final class OreConversionClient {
    /** 最近同步到本地的玩家余额。 */
    private static long balance;
    /** 当前已学习且可提取的物品目录。 */
    private static List<OreConversionNetwork.PriceEntry> catalog = List.of();
    /** 当前完整价格目录，供物品提示和输入状态查询使用。 */
    private static Map<ResourceLocation, OreConversionNetwork.PriceEntry> prices = Map.of();
    /** 正在接收的价格分块；末块到达前不会发布为完整目录。 */
    private static Map<ResourceLocation, OreConversionNetwork.PriceEntry> incomingPrices;

    /** 工具类不允许创建实例。 */
    private OreConversionClient() {}

    /** 接收账户状态，并更新当前打开的转化菜单。 */
    public static void receive(OreConversionNetwork.SyncPayload packet) {
        balance = packet.balance();
        catalog = List.copyOf(packet.catalog());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof OreConversionMenu menu
                && menu.containerId == packet.containerId()) {
            menu.receive(packet.balance(), packet.catalog());
        }
    }

    /** 接收价格目录分块；收齐最后一块后原子替换本地目录。 */
    public static void receivePrices(OreConversionNetwork.PricesPayload packet) {
        if (packet.first()) {
            // 新传输开始时丢弃旧目录，避免把两次同步的数据拼在一起。
            incomingPrices = new HashMap<>();
            prices = Map.of();
        }
        if (incomingPrices == null) return;
        for (OreConversionNetwork.PriceEntry entry : packet.entries()) {
            incomingPrices.put(entry.id(), entry);
        }
        if (packet.last()) {
            prices = Map.copyOf(incomingPrices);
            incomingPrices = null;
        }
    }

    /** 将操作结果转交给当前转化桌界面显示。 */
    public static void receiveStatus(OreConversionNetwork.StatusPayload packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof OreConversionScreen screen) screen.receiveStatus(packet);
    }

    /** 玩家退出服务器时清理所有服务端同步缓存。 */
    public static void clear() {
        balance = 0;
        catalog = List.of();
        prices = Map.of();
        incomingPrices = null;
    }

    /** 返回最近同步的账户余额。 */
    public static long balance() { return balance; }
    /** 返回最近同步的已学习目录。 */
    public static List<OreConversionNetwork.PriceEntry> catalog() { return catalog; }
    /** 查询客户端缓存中的物品单价。 */
    public static OptionalLong price(Item item) {
        OreConversionNetwork.PriceEntry entry = prices.get(BuiltInRegistries.ITEM.getKey(item));
        return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.price());
    }

    /** 判断物品栈能否作为普通物品输入转化桌。 */
    public static boolean canConvert(ItemStack stack) {
        OreConversionNetwork.PriceEntry entry = prices.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return entry != null && entry.convertible() && OreConversionPrices.isPlain(stack);
    }
}
