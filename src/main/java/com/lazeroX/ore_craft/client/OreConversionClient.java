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

/** Isolated client payload target; this class is never used by server game logic. */
public final class OreConversionClient {
    private static long balance;
    private static List<OreConversionNetwork.PriceEntry> catalog = List.of();
    private static Map<ResourceLocation, OreConversionNetwork.PriceEntry> prices = Map.of();
    private static Map<ResourceLocation, OreConversionNetwork.PriceEntry> incomingPrices;

    private OreConversionClient() {}

    public static void receive(OreConversionNetwork.SyncPayload packet) {
        balance = packet.balance();
        catalog = List.copyOf(packet.catalog());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof OreConversionMenu menu
                && menu.containerId == packet.containerId()) {
            menu.receive(packet.balance(), packet.catalog());
        }
    }

    public static void receivePrices(OreConversionNetwork.PricesPayload packet) {
        if (packet.first()) {
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

    public static void receiveStatus(OreConversionNetwork.StatusPayload packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof OreConversionScreen screen) screen.receiveStatus(packet);
    }

    public static void clear() {
        balance = 0;
        catalog = List.of();
        prices = Map.of();
        incomingPrices = null;
    }

    public static long balance() { return balance; }
    public static List<OreConversionNetwork.PriceEntry> catalog() { return catalog; }
    public static OptionalLong price(Item item) {
        OreConversionNetwork.PriceEntry entry = prices.get(BuiltInRegistries.ITEM.getKey(item));
        return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.price());
    }

    public static boolean canConvert(ItemStack stack) {
        OreConversionNetwork.PriceEntry entry = prices.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return entry != null && entry.convertible() && OreConversionPrices.isPlain(stack);
    }
}
