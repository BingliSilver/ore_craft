package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
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

/** 管理两种转化桌的交易；容器版仅用第三槽结算 ME，学习记录仍属于当前玩家。 */
public class OreConversionMenu extends AbstractContainerMenu {
    /** 普通容器向全局账户充值的槽位索引，紧接 36 个玩家物品栏槽。 */
    public static final int ME_INPUT_SLOT = 36;
    /** 从全局账户提取 ME 到普通容器的槽位索引。 */
    public static final int ME_OUTPUT_SLOT = 37;
    /** 容器版独有的交易槽；不参与另外两个槽位的全局账户充值、提现。 */
    public static final int TRADE_CONTAINER_SLOT = 38;
    /** 交易槽在原界面设计稿中的横坐标，供菜单与背景绘制共用。 */
    public static final int TRADE_CONTAINER_X = 116;
    /** 交易槽在原界面设计稿中的纵坐标，与充值、提现槽同高。 */
    public static final int TRADE_CONTAINER_Y = 75;
    /** 是否强制通过交易槽中的矿质容器结算。 */
    private final boolean containerBacked;
    /** 该菜单绑定的转化桌坐标。 */
    private final BlockPos pos;
    /** 转化桌所在世界，用于校验菜单有效性。 */
    private final Level level;
    /** 转化口内暂存的容器，关闭界面时归还玩家。 */
    private final SimpleContainer oreContainers;
    /** 当前打开菜单的玩家；末影交易容器连接此玩家而非方块放置者。 */
    private final Player owner;
    /** 防止交互口更新物品组件时递归触发 ME 转移。 */
    private boolean transferring;
    /** 最近同步的全局 ME 余额；普通交易容器的独立余额直接从槽位组件读取。 */
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
        this(id, inventory, pos, false);
    }

    /**
     * 创建指定版本的转化桌菜单；容器仅在本次打开期间暂存，关闭时返还玩家。
     *
     * @param id 菜单容器 ID
     * @param inventory 玩家物品栏
     * @param pos 转化桌坐标
     * @param containerBacked 为 true 时增加交易槽，并禁止交易直接回退到全局余额
     */
    public OreConversionMenu(int id, Inventory inventory, BlockPos pos, boolean containerBacked) {
        super(containerBacked ? ModMenus.ORE_CONTAINER_CONVERSION_MENU.get() : ModMenus.ORE_CONVERSION_MENU.get(), id);
        this.containerBacked = containerBacked;
        this.oreContainers = new SimpleContainer(containerBacked ? 3 : 2);
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
        if (containerBacked) addContainerSlot(2, TRADE_CONTAINER_X, TRADE_CONTAINER_Y);
    }

    /**
     * 创建容器槽；原有两槽仅接受普通容器，交易槽同时接受普通和末影容器。
     *
     * @param portIndex 临时库存索引，0 为充值、1 为提现、2 为交易
     * @param x 设计稿横坐标
     * @param y 设计稿纵坐标
     */
    private void addContainerSlot(int portIndex, int x, int y) {
        // 槽位对象在菜单整个生命周期中校验物品；交易槽不自动搬运 ME。
        addSlot(new Slot(oreContainers, portIndex, Math.round(x * 432 / 390.0F), Math.round(y * 228 / 206.0F)) {
            /** 按槽位用途校验矿质容器类型。 */
            @Override
            public boolean mayPlace(ItemStack stack) {
                return portIndex == 2 ? OreMachineEnergy.isContainer(stack) : stack.getItem() instanceof OreContainerItem;
            }

            /** 物品变化时更新交互口；交易槽只同步新的结算余额。 */
            @Override
            public void setChanged() {
                super.setChanged();
                if (owner instanceof ServerPlayer player && !transferring) {
                    if (portIndex == 2) sync(player);
                    else transferContainer(player, portIndex);
                }
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
        return level.getBlockState(pos).is(containerBacked
                ? ModBlocks.ORE_CONTAINER_CONVERSION_TABLE.get() : ModBlocks.ORE_CONVERSION_TABLE.get())
                && player.canInteractWithBlock(pos, 4.0);
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
        if (slot >= ME_INPUT_SLOT && slot < slots.size()) {
            ItemStack stack = getSlot(slot).getItem();
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack original = stack.copy();
            if (!moveItemStackTo(stack, 0, ME_INPUT_SLOT, false)) return ItemStack.EMPTY;
            getSlot(slot).set(ItemStack.EMPTY);
            return original;
        }
        // 背包中的矿质容器可学习并整体回收；只有手动放入交互口才会无损转移内部 ME。
        return useInventorySlot(serverPlayer, slot);
    }

    /** 关闭界面时返还所有暂存容器，普通交易容器保留交易后的 ME 组件。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) clearContainer(player, oreContainers);
    }

    /**
     * 从背包物品的默认状态学习类型；只有原物品本身满足兑换规则时才消耗并转入 ME。
     * 普通矿质容器回收本体和内部存储量；末影容器只回收本体，不把既有账户余额重复计价。
     * 容器版把回收所得写入第三槽的容器，容量不足时不消耗原物品。
     * 附魔、耐久等不允许输入的特殊组件不会被转化，原物品保留在背包。
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
        // 学习目录按物品 ID 保存，提取时生成该物品的默认状态；用同样的默认栈校验可学习性。
        // 这里不修改原栈，附魔、损伤、容器 ME 和其他数据都不会被写进学习记录。
        ItemStack learningStack = new ItemStack(stack.getItem());
        if (!OreConversionPrices.isPlain(learningStack)) { status(player, "special_state"); return ItemStack.EMPTY; }
        if (!OreConversionPrices.canLearn(learningStack)) { status(player, "unpriced"); return ItemStack.EMPTY; }
        OreConversionSavedData data = OreConversionSavedData.get(player);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (data.account(player).learned().size() >= OreConversionSavedData.MAX_LEARNED && !data.account(player).knows(id)) {
            status(player, "learn_limit"); return ItemStack.EMPTY;
        }
        long converted = 0;
        ItemStack moved = ItemStack.EMPTY;
        // 兑换按原栈计算，普通容器的存储量只在此时计入，学习记录仍是默认空容器。
        OptionalLong unit = OreConversionPrices.depositValue(stack);
        if (unit.isEmpty() && OreConversionPrices.canDeposit(stack)) {
            status(player, "overflow");
            return ItemStack.EMPTY;
        }
        if (unit.isPresent()) {
            // 容器版必须明确提供结算容器；未放入时保留原物品，避免误存入全局账户。
            if (!requireTradeContainer(player)) return ItemStack.EMPTY;
            long amount;
            try { amount = Math.multiplyExact(unit.getAsLong(), stack.getCount()); }
            catch (ArithmeticException ex) { status(player, "overflow"); return ItemStack.EMPTY; }
            if (!creditTrade(player, amount)) {
                status(player, containerBacked ? "container_full" : "overflow");
                return ItemStack.EMPTY;
            }
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
     * 从已学习目录提取物品到鼠标指针，并从当前结算来源扣除 ME。
     *
     * @param player 发起提取的服务端玩家
     * @param id 目标物品注册 ID
     * @param count 请求提取的数量；当前仅接受单个物品
     */
    public void extract(ServerPlayer player, ResourceLocation id, int count) {
        if (!validRequest(player) || count != 1 || id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        if (!requireTradeContainer(player)) return;
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
        // 先校验余额并成功扣款后才更新鼠标指针，确保交易不会免费生成物品。
        if (tradeBalance(player) < total) { status(player, "insufficient_me"); return; }
        if (!debitTrade(player, total)) return;
        setCarried(new ItemStack(item, carried.getCount() + count));
        broadcastChanges();
        sync(player);
    }

    /**
     * 按当前结算余额购买最多一组物品放入背包，仅对实际可插入的数量扣款。
     *
     * @param player 发起提取的服务端玩家
     * @param id 目标物品注册 ID
     */
    public void extractStackToInventory(ServerPlayer player, ResourceLocation id) {
        if (!validRequest(player) || id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        if (!requireTradeContainer(player)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!OreConversionPrices.canExtract(item) || !OreConversionSavedData.get(player).account(player).knows(id)) {
            status(player, "not_learned"); return;
        }
        ItemStack output = new ItemStack(item);
        if (!OreConversionPrices.isPlain(output)) return;
        OptionalLong unit = OreConversionPrices.price(item);
        if (unit.isEmpty()) return;
        // 同时受标准一组上限、物品自身堆叠上限和结算来源余额约束。
        int affordable = (int) Math.min(Math.min(64, output.getMaxStackSize()),
                tradeBalance(player) / unit.getAsLong());
        if (affordable == 0) { status(player, "insufficient_me"); return; }
        Inventory inventory = player.getInventory();
        // 先规划所有槽位的变更并计算真实可放数量，再扣款，避免背包放不下时多扣 ME。
        int[] additions = planInsertion(inventory, output.copyWithCount(affordable));
        int count = 0;
        for (int addition : additions) count += addition;
        if (count == 0) { status(player, "inventory_full"); return; }
        long total = unit.getAsLong() * count;
        if (!debitTrade(player, total)) return;
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

    /**
     * 检查结算容器是否存在；普通桌不受此限制，容器版缺槽时发送提示。
     *
     * @param player 请求交易的玩家
     * @return 当前菜单是否具有有效的 ME 结算来源
     */
    private boolean requireTradeContainer(ServerPlayer player) {
        if (!containerBacked || OreMachineEnergy.isContainer(oreContainers.getItem(2))) return true;
        status(player, "missing_trade_container");
        return false;
    }

    /**
     * 服务端读取结算余额，普通容器的余额不会与玩家全局余额相加。
     *
     * @param player 当前交易玩家，也是末影容器连接的账户所有者
     * @return 当前可支付的 ME；空交易槽为零
     */
    private long tradeBalance(ServerPlayer player) {
        return containerBacked
                ? OreMachineEnergy.stored(player.getServer(), player.getUUID(), oreContainers.getItem(2))
                : OreConversionSavedData.get(player).account(player).balance();
    }

    /**
     * 将回收所得存入结算来源；容量不足时完整拒绝，调用方不得消耗原物品。
     *
     * @param player 交易玩家
     * @param amount 要存入的正数 ME
     * @return 存入成功时为 true
     */
    private boolean creditTrade(ServerPlayer player, long amount) {
        if (!containerBacked) return OreConversionSavedData.get(player).credit(player, amount);
        ItemStack updated = OreMachineEnergy.credit(player.getServer(), player.getUUID(), oreContainers.getItem(2), amount);
        if (updated == null) return false;
        getSlot(TRADE_CONTAINER_SLOT).set(updated);
        return true;
    }

    /**
     * 从结算来源扣除购买费用，成功后写回普通容器的物品组件。
     *
     * @param player 交易玩家
     * @param amount 要支付的正数 ME
     * @return 扣款成功时为 true
     */
    private boolean debitTrade(ServerPlayer player, long amount) {
        if (!containerBacked) return OreConversionSavedData.get(player).debit(player, amount);
        ItemStack updated = OreMachineEnergy.debit(player.getServer(), player.getUUID(), oreContainers.getItem(2), amount);
        if (updated == null) return false;
        getSlot(TRADE_CONTAINER_SLOT).set(updated);
        return true;
    }

    /** 返回是否为具有独立交易容器槽的新版转化桌，供客户端选择界面布局。 */
    public boolean containerBacked() { return containerBacked; }

    /** 向玩家同步全局账户与学习目录；普通容器余额通过槽位组件同步，避免污染全局提示缓存。 */
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

    /** 返回客户端当前结算余额：普通容器读槽位组件，末影容器与原版桌读全局快照。 */
    public long clientBalance() {
        if (!containerBacked) return clientBalance;
        ItemStack stack = oreContainers.getItem(2);
        if (stack.getItem() instanceof OreContainerItem normal) return normal.storedMe(stack);
        return OreMachineEnergy.isContainer(stack) ? clientBalance : 0;
    }
    /** 返回最近一次同步到客户端的已学习物品目录。 */
    public List<OreConversionNetwork.PriceEntry> clientCatalog() { return clientCatalog; }
    /** 返回服务端快照修订号，供界面检测变化。 */
    public int revision() { return revision; }
}
