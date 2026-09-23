package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.OptionalLong;

/** 管理转化桌交易，并通过原版快速移动操作处理背包输入。 */
public class OreConversionMenu extends AbstractContainerMenu {
    private static final int MAX_LEARNED = 2048;
    private final BlockPos pos;
    private final Level level;
    private long clientBalance;
    private List<OreConversionNetwork.PriceEntry> clientCatalog = List.of();
    private int revision;

    public OreConversionMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    public OreConversionMenu(int id, Inventory inventory, BlockPos pos) {
        super(Ore_craft.ORE_CONVERSION_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 41 + column * 19, 114 + row * 19));
            }
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 41 + column * 19, 174));
    }

    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).is(Ore_craft.ORE_CONVERSION_TABLE.get()) && player.canInteractWithBlock(pos, 4.0);
    }

    /**
     * 处理原版 Shift 快速移动：背包中的整组物品输入转化桌。
     * 原版 Shift 双击会对匹配的格子分别调用此方法。
     *
     * @param player 操作容器的玩家
     * @param slot 被快速移动的容器格子索引
     * @return 已输入的物品栈；无有效输入时返回空栈
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        if (!(player instanceof ServerPlayer serverPlayer)) return ItemStack.EMPTY;
        return useInventorySlot(serverPlayer, slot);
    }

    /**
     * 将背包格子的可转化物品输入转化桌；仅可学习的物品保持在原格子。
     *
     * @param player 操作转化桌的服务端玩家
     * @param slotIndex 容器中的背包格子索引
     * @return 已输入的原物品栈；未消耗物品时返回空栈，以结束原版快速移动循环
     */
    private ItemStack useInventorySlot(ServerPlayer player, int slotIndex) {
        if (!validRequest(player) || slotIndex < 0 || slotIndex >= 36 || !getCarried().isEmpty()) return ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!OreConversionPrices.isPlain(stack)) { status(player, "special_state"); return ItemStack.EMPTY; }
        if (!OreConversionPrices.canLearn(stack)) { status(player, "unpriced"); return ItemStack.EMPTY; }
        OreConversionSavedData data = OreConversionSavedData.get(player);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (data.account(player).learned().size() >= MAX_LEARNED && !data.account(player).knows(id)) {
            status(player, "learn_limit"); return ItemStack.EMPTY;
        }
        long converted = 0;
        ItemStack moved = ItemStack.EMPTY;
        if (OreConversionPrices.canDeposit(stack)) {
            OptionalLong price = OreConversionPrices.price(stack.getItem());
            if (price.isEmpty()) { status(player, "unpriced"); return ItemStack.EMPTY; }
            long amount;
            try { amount = Math.multiplyExact(price.getAsLong(), stack.getCount()); }
            catch (ArithmeticException ex) { status(player, "overflow"); return ItemStack.EMPTY; }
            if (!data.credit(player, amount)) { status(player, "overflow"); return ItemStack.EMPTY; }
            converted = amount;
            moved = stack.copy();
            slot.set(ItemStack.EMPTY);
            broadcastChanges();
        }
        boolean learned = data.learn(player, id);
        sync(player);
        status(player, converted > 0 ? learned ? "converted_learned" : "converted_known"
                : learned ? "learned" : "already_learned", converted);
        return moved;
    }

    public void extract(ServerPlayer player, ResourceLocation id, int count) {
        if (!validRequest(player) || count != 1 || id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!OreConversionPrices.canExtract(item) || !OreConversionSavedData.get(player).account(player).knows(id)) {
            status(player, "not_learned"); return;
        }
        ItemStack carried = getCarried();
        ItemStack output = new ItemStack(item);
        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, output)) return;
        int room = output.getMaxStackSize() - carried.getCount();
        count = Math.min(count, room);
        if (count <= 0 || !OreConversionPrices.isPlain(output)) return;
        OptionalLong unit = OreConversionPrices.price(item);
        if (unit.isEmpty()) return;
        long total;
        try { total = Math.multiplyExact(unit.getAsLong(), count); }
        catch (ArithmeticException ex) { status(player, "overflow"); return; }
        OreConversionSavedData data = OreConversionSavedData.get(player);
        if (data.account(player).balance() < total) { status(player, "insufficient_me"); return; }
        if (!data.debit(player, total)) return;
        setCarried(new ItemStack(item, carried.getCount() + count));
        broadcastChanges();
        sync(player);
    }

    public void extractStackToInventory(ServerPlayer player, ResourceLocation id) {
        if (!validRequest(player) || id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!OreConversionPrices.canExtract(item) || !OreConversionSavedData.get(player).account(player).knows(id)) {
            status(player, "not_learned"); return;
        }
        ItemStack output = new ItemStack(item);
        if (!OreConversionPrices.isPlain(output)) return;
        OptionalLong unit = OreConversionPrices.price(item);
        if (unit.isEmpty()) return;
        OreConversionSavedData data = OreConversionSavedData.get(player);
        int affordable = (int) Math.min(Math.min(64, output.getMaxStackSize()),
                data.account(player).balance() / unit.getAsLong());
        if (affordable == 0) { status(player, "insufficient_me"); return; }
        Inventory inventory = player.getInventory();
        int[] additions = planInsertion(inventory, output.copyWithCount(affordable));
        int count = 0;
        for (int addition : additions) count += addition;
        if (count == 0) { status(player, "inventory_full"); return; }
        long total = unit.getAsLong() * count;
        if (!data.debit(player, total)) return;
        ItemStack stack = output.copyWithCount(count);
        for (int slot = 0; slot < additions.length; slot++) {
            if (additions[slot] == 0) continue;
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) inventory.setItem(slot, stack.copyWithCount(additions[slot]));
            else existing.grow(additions[slot]);
        }
        inventory.setChanged();
        broadcastChanges();
        sync(player);
    }

    private static int[] planInsertion(Inventory inventory, ItemStack output) {
        int[] additions = new int[36];
        int remaining = output.getCount();
        for (int slot = 0; slot < additions.length; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, output)) continue;
            int space = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize(existing)) - existing.getCount();
            int take = Math.min(remaining, Math.max(0, space));
            additions[slot] = take;
            remaining -= take;
            if (remaining == 0) return additions;
        }
        for (int slot = 0; slot < additions.length; slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            int take = Math.min(remaining, Math.min(output.getMaxStackSize(), inventory.getMaxStackSize(output)));
            additions[slot] = take;
            remaining -= take;
            if (remaining == 0) return additions;
        }
        return additions;
    }

    public void sync(ServerPlayer player) {
        if (!validRequest(player)) return;
        OreConversionNetwork.sendState(player, containerId);
    }

    private boolean validRequest(ServerPlayer player) {
        return player.containerMenu == this && stillValid(player);
    }

    private void status(ServerPlayer player, String key) {
        status(player, key, 0);
    }

    private void status(ServerPlayer player, String key, long amount) {
        OreConversionNetwork.sendStatus(player, containerId, key, amount);
    }

    public void receive(long balance, List<OreConversionNetwork.PriceEntry> catalog) {
        clientBalance = balance;
        clientCatalog = List.copyOf(catalog);
        revision++;
    }

    public long clientBalance() { return clientBalance; }
    public List<OreConversionNetwork.PriceEntry> clientCatalog() { return clientCatalog; }
    public int revision() { return revision; }
}
