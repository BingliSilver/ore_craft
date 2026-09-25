package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.item.OreContainerItem;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.OptionalLong;

/** 管理转化桌交易，并通过原版快速移动操作处理背包输入。 */
public class OreConversionMenu extends AbstractContainerMenu {
    /** 单个账户最多允许记录的已学习物品数量。 */
    private static final int MAX_LEARNED = 2048;
    /** 背包槽位后的两个容器交互口索引。 */
    public static final int ME_INPUT_SLOT = 36;
    public static final int ME_OUTPUT_SLOT = 37;
    /** 该菜单绑定的转化桌坐标。 */
    private final BlockPos pos;
    /** 转化桌所在世界，用于校验菜单有效性。 */
    private final Level level;
    /** 转化口内暂存的容器，关闭界面时归还玩家。 */
    private final SimpleContainer oreContainers = new SimpleContainer(2);
    private final Player owner;
    private boolean transferring;
    /** 最近同步给客户端显示的 ME 余额。 */
    private long clientBalance;
    /** 最近同步给客户端显示的可提取物品目录。 */
    private List<OreConversionNetwork.PriceEntry> clientCatalog = List.of();
    /** 每次接收新快照时递增，供界面检测目录变化。 */
    private int revision;

    /**
     * 从网络附加数据读取方块坐标并创建菜单。
     *
     * @param id 菜单容器 ID
     * @param inventory 玩家物品栏
     * @param extra 服务端传入的菜单附加数据
     */
    public OreConversionMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建绑定到指定转化桌的菜单，并添加玩家的 36 个背包槽位。
     *
     * @param id 菜单容器 ID
     * @param inventory 玩家物品栏
     * @param pos 转化桌方块坐标
     */
    public OreConversionMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_CONVERSION_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        this.owner = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 41 + column * 19, 114 + row * 19));
            }
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 41 + column * 19, 174));
        addContainerSlot(0, 142, 75);
        addContainerSlot(1, 168, 75);
    }

    /** 创建只接受可存储 ME 容器的输入或输出槽。 */
    private void addContainerSlot(int portIndex, int x, int y) {
        addSlot(new Slot(oreContainers, portIndex, Math.round(x * 432 / 390.0F), Math.round(y * 228 / 206.0F)) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof OreContainerItem;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (owner instanceof ServerPlayer player && !transferring) transferContainer(player, portIndex);
            }
        });
    }

    /**
     * 在容器放入交互口时转移当前能够转移的全部 ME。
     * 输入口从容器到账户；输出口从账户到容器。转移前先确定上限，避免溢出或丢失。
     */
    private void transferContainer(ServerPlayer player, int index) {
        if (!validRequest(player)) return;
        ItemStack stack = oreContainers.getItem(index);
        if (!(stack.getItem() instanceof OreContainerItem container)) return;
        OreConversionSavedData data = OreConversionSavedData.get(player);
        long stored = container.storedMe(stack);
        long balance = data.account(player).balance();
        long amount = index == 0
                ? Math.min(stored, Long.MAX_VALUE - balance)
                : Math.min(container.capacity() - stored, balance);
        if (amount <= 0) return;
        // 先成功更新账户，再写入物品；两者均在服务端同一线程完成。
        boolean success = index == 0 ? data.credit(player, amount) : data.debit(player, amount);
        if (!success) return;
        transferring = true;
        try {
            ItemStack updated = stack.copy();
            container.setStoredMe(updated, index == 0 ? stored - amount : stored + amount);
            getSlot(ME_INPUT_SLOT + index).set(updated);
        } finally {
            transferring = false;
        }
        broadcastChanges();
        sync(player);
    }

    /** 检查转化桌仍存在且玩家仍在交互距离内。 */
    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).is(ModBlocks.ORE_CONVERSION_TABLE.get()) && player.canInteractWithBlock(pos, 4.0);
    }

    /**
     * 处理原版容器的 Shift 快速移动请求；背包物品交给学习与转化逻辑，交互口中的容器可移回背包。
     *
     * @param player 操作容器的玩家
     * @param slot 被快速移动的容器格子索引
     * @return 已转化或移回背包的物品栈；仅学习或无有效操作时返回空栈
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        if (!(player instanceof ServerPlayer serverPlayer)) return ItemStack.EMPTY;
        if (slot == ME_INPUT_SLOT || slot == ME_OUTPUT_SLOT) {
            ItemStack stack = getSlot(slot).getItem();
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack original = stack.copy();
            if (!moveItemStackTo(stack, 0, ME_INPUT_SLOT, false)) return ItemStack.EMPTY;
            getSlot(slot).set(ItemStack.EMPTY);
            return original;
        }
        // 背包中的矿质容器也走学习流程；只有手动放入交互口才会转移 ME。
        return useInventorySlot(serverPlayer, slot);
    }

    /** 关闭界面时将两侧交互口内的容器返还给玩家。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) clearContainer(player, oreContainers);
    }

    /**
     * 将背包格子的可转化物品输入转化桌；矿质容器按零 ME 状态学习类型，原容器及其 ME 留在背包。
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
        // 只在副本中清除存储量：有 ME 的容器也能学习，但原物品不变，其他特殊数据仍受普通校验约束。
        ItemStack learningStack = stack;
        if (stack.getItem() instanceof OreContainerItem container) {
            learningStack = stack.copy();
            container.setStoredMe(learningStack, 0);
        }
        if (!OreConversionPrices.isPlain(learningStack)) { status(player, "special_state"); return ItemStack.EMPTY; }
        if (!OreConversionPrices.canLearn(learningStack)) { status(player, "unpriced"); return ItemStack.EMPTY; }
        OreConversionSavedData data = OreConversionSavedData.get(player);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (data.account(player).learned().size() >= MAX_LEARNED && !data.account(player).knows(id)) {
            status(player, "learn_limit"); return ItemStack.EMPTY;
        }
        long converted = 0;
        ItemStack moved = ItemStack.EMPTY;
        // 即使数据包将容器列为可输入来源，Shift 点击仍只学习，不消耗容器或兑换 ME。
        if (!(stack.getItem() instanceof OreContainerItem) && OreConversionPrices.canDeposit(stack)) {
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
        // 价格交易和学习记录是两个独立结果：只允许学习的物品不会从背包中移除。
        boolean learned = data.learn(player, id);
        sync(player);
        status(player, converted > 0 ? learned ? "converted_learned" : "converted_known"
                : learned ? "learned" : "already_learned", converted);
        return moved;
    }

    /**
     * 从账户提取指定数量的物品到鼠标指针，并扣除对应 ME。
     *
     * @param player 发起提取的服务端玩家
     * @param id 目标物品注册 ID
     * @param count 请求提取的数量；当前仅接受单个物品
     */
    public void extract(ServerPlayer player, ResourceLocation id, int count) {
        if (!validRequest(player) || count != 1 || id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        // 只有已学习且仍可提取的物品才能购买，所有条件都在服务端重新核验。
        if (!OreConversionPrices.canExtract(item) || !OreConversionSavedData.get(player).account(player).knows(id)) {
            status(player, "not_learned"); return;
        }
        ItemStack carried = getCarried();
        ItemStack output = new ItemStack(item);
        // 指针上已有其他物品时不扣款；同类物品则只填充剩余堆叠空间。
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
        // 先校验余额并成功扣款后才更新鼠标指针，确保交易不会免费生成物品。
        if (data.account(player).balance() < total) { status(player, "insufficient_me"); return; }
        if (!data.debit(player, total)) return;
        setCarried(new ItemStack(item, carried.getCount() + count));
        broadcastChanges();
        sync(player);
    }

    /**
     * 将账户允许购买的最多一组物品插入玩家物品栏，并按实际插入数量扣款。
     *
     * @param player 发起提取的服务端玩家
     * @param id 目标物品注册 ID
     */
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
        // 同时受标准一组上限、物品自身堆叠上限和账户余额约束。
        int affordable = (int) Math.min(Math.min(64, output.getMaxStackSize()),
                data.account(player).balance() / unit.getAsLong());
        if (affordable == 0) { status(player, "insufficient_me"); return; }
        Inventory inventory = player.getInventory();
        // 先规划所有槽位的变更并计算真实可放数量，再扣款，避免背包放不下时多扣 ME。
        int[] additions = planInsertion(inventory, output.copyWithCount(affordable));
        int count = 0;
        for (int addition : additions) count += addition;
        if (count == 0) { status(player, "inventory_full"); return; }
        long total = unit.getAsLong() * count;
        if (!data.debit(player, total)) return;
        ItemStack stack = output.copyWithCount(count);
        // 按之前规划的数量写入各槽位，保证实际插入量与扣款金额一致。
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

    /**
     * 规划一组物品在背包现有堆叠和空槽中的分配，不实际修改背包。
     *
     * @param inventory 目标玩家物品栏
     * @param output 待插入物品栈
     * @return 每个背包槽位将增加的数量
     */
    private static int[] planInsertion(Inventory inventory, ItemStack output) {
        int[] additions = new int[36];
        int remaining = output.getCount();
        // 先填充同类堆叠，再使用空格，符合玩家手动整理物品的预期。
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

    /** 向玩家同步当前账户状态；菜单无效时忽略请求。 */
    public void sync(ServerPlayer player) {
        if (!validRequest(player)) return;
        OreConversionNetwork.sendState(player, containerId);
    }

    /** 验证请求玩家当前打开的正是此菜单且仍可交互。 */
    private boolean validRequest(ServerPlayer player) {
        return player.containerMenu == this && stillValid(player);
    }

    /** 发送不携带数量的操作提示。 */
    private void status(ServerPlayer player, String key) {
        status(player, key, 0);
    }

    /** 发送携带数量参数的操作提示。 */
    private void status(ServerPlayer player, String key, long amount) {
        OreConversionNetwork.sendStatus(player, containerId, key, amount);
    }

    /** 接收服务端同步快照并递增修订号，通知界面刷新目录。 */
    public void receive(long balance, List<OreConversionNetwork.PriceEntry> catalog) {
        clientBalance = balance;
        clientCatalog = List.copyOf(catalog);
        revision++;
    }

    /** 返回最近一次同步到客户端的余额。 */
    public long clientBalance() { return clientBalance; }
    /** 返回最近一次同步到客户端的已学习物品目录。 */
    public List<OreConversionNetwork.PriceEntry> clientCatalog() { return clientCatalog; }
    /** 返回服务端快照修订号，供界面检测变化。 */
    public int revision() { return revision; }
}
