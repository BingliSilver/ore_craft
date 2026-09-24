package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.EnchantMeCostCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 矿质附魔台的服务端菜单，管理目标、支付容器及玩家背包。
 * 预览由服务端同步；领取输出时重新校验并按附魔等级差额扣除或存入 ME。
 */
public final class OreEnchantingMenu extends AbstractContainerMenu {
    /** 待附魔物品槽索引。 */
    public static final int TARGET_SLOT = 0;
    /** 支付容器槽索引。 */
    public static final int CONTAINER_SLOT = 1;
    /** 仅展示结果的输出槽，不参与关闭菜单时的输入物归还。 */
    public static final int OUTPUT_SLOT = 2;
    /** 玩家背包槽起始索引。 */
    private static final int INVENTORY_START = 3;
    /** 两个输入槽、一个输出槽加 36 个玩家背包槽。 */
    private static final int SLOT_COUNT = 39;
    /** 菜单内结果提示状态；零表示尚未进行交易。 */
    public static final int RESULT_NONE = 0;
    /** 已扣除 ME 并交付结果。 */
    public static final int RESULT_SPENT = 1;
    /** 已把降级返还的 ME 存入容器。 */
    public static final int RESULT_STORED = 2;
    /** 多项调整互相抵消，余额不变。 */
    public static final int RESULT_UNCHANGED = 3;
    /** 输入容器余额不足。 */
    public static final int RESULT_INSUFFICIENT = 4;
    /** 输入容器容量不足。 */
    public static final int RESULT_FULL = 5;
    /** 菜单对应的附魔台方块坐标。 */
    private final BlockPos pos;
    /** 当前世界，用于验证方块是否仍存在。 */
    private final Level level;
    /** 输入槽的临时物品，菜单关闭时全部归还。 */
    private final SimpleContainer inputs = new SimpleContainer(2) {
        /** 输入物变化时立即重建预览，避免展示已经失效的附魔结果。 */
        @Override public void setChanged() {
            super.setChanged();
            if (previewReady && !level.isClientSide()) refreshPreview();
        }
    };
    /** 输出副本只用于菜单同步；真实输入物在领取成功前始终保留。 */
    private final SimpleContainer preview = new SimpleContainer(1);
    /** 构造完槽位后才允许输入容器触发预览刷新。 */
    private boolean previewReady;
    /** 每条附魔的服务端目标等级，零表示删除该附魔。 */
    private final Map<ResourceLocation, Integer> selectedLevels = new HashMap<>();
    /** 修改目标物品后清空旧选择，避免把上一件物品的调整应用到新物品。 */
    private ItemStack selectedTarget = ItemStack.EMPTY;
    /** 当前预览净 ME 变动：正数扣除，负数存入。 */
    private long previewDifference;
    /** 结果状态与金额拆成 16 位片段，以适配原版菜单数据槽同步。 */
    private final int[] resultData = new int[5];

    /** 从服务端附加数据创建客户端菜单。 */
    public OreEnchantingMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建绑定指定方块的菜单。输入槽各收纳一件物品；主背包和快捷栏都可操作。
     *
     * @param id 容器同步编号
     * @param inventory 玩家背包
     * @param pos 附魔台所在坐标
     */
    public OreEnchantingMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_ENCHANTING_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        // 两个匿名槽分别约束物品类型，并限制每次只处理一件。
        addSlot(new Slot(inputs, TARGET_SLOT, 80, 92) {
            @Override public boolean mayPlace(ItemStack stack) { return canPlaceTarget(stack); }
            @Override public int getMaxStackSize() { return 1; }
        });
        addSlot(new Slot(inputs, CONTAINER_SLOT, 80, 148) {
            @Override public boolean mayPlace(ItemStack stack) { return isMeContainer(stack); }
            @Override public int getMaxStackSize() { return 1; }
        });
        // 领取动作在 tryRemove 内完成交易，不能让原版先交付预览物品再扣费。
        addSlot(new Slot(preview, 0, 184, 126) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) {
                // 领取失败也要进入 tryRemove，才能把失败原因同步到界面。
                return !getItem().isEmpty();
            }
            @Override public Optional<ItemStack> tryRemove(int amount, int limit, Player player) {
                if (!player.level().isClientSide()) {
                    if (!(player instanceof ServerPlayer server)) return Optional.empty();
                    ItemStack result = takePreview(server);
                    return result.isEmpty() ? Optional.empty() : Optional.of(result);
                }
                // 客户端仅预测取物；服务端仍会独立检查并同步最终状态。
                return super.tryRemove(amount, limit, player);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 52 + column * 23, 224 + row * 23));
            }
        }
        // 预览图未画快捷栏；这里在底边补出九格，方便放入手持工具。
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 52 + column * 23, 299));
        }
        // 先同步金额片段、最后同步状态码，避免界面短暂显示新状态与旧金额。
        for (int index = 1; index < resultData.length; index++) addDataSlot(DataSlot.shared(resultData, index));
        addDataSlot(DataSlot.shared(resultData, 0));
        previewReady = true;
    }

    /** 判断物品能否保存附魔；已有附魔的装备也可以继续附魔。 */
    private static boolean canPlaceTarget(ItemStack stack) {
        return !stack.isEmpty() && (isBookTarget(stack) || EnchantmentHelper.canStoreEnchantments(stack));
    }

    /** 普通书和附魔书都允许记录任意已注册附魔。 */
    private static boolean isBookTarget(ItemStack stack) {
        return stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK);
    }

    /** 固定容量容器与连接全局账户的末影容器都可支付 ME。 */
    private static boolean isMeContainer(ItemStack stack) {
        return stack.getItem() instanceof OreContainerItem || stack.getItem() instanceof EnderOreContainerItem;
    }

    /** 返回目标物品，供客户端构建候选附魔。 */
    public ItemStack target() { return inputs.getItem(TARGET_SLOT); }

    /** 返回当前支付容器，供客户端显示可用 ME。 */
    public ItemStack container() { return inputs.getItem(CONTAINER_SLOT); }

    /** 返回最近一次领取尝试的结果状态，供菜单界面绘制提示。 */
    public int resultStatus() { return resultData[0]; }

    /** 从四段 16 位同步数据还原最近一次交易的 ME 金额。 */
    public long resultAmount() {
        return (resultData[1] & 0xFFFFL) | ((resultData[2] & 0xFFFFL) << 16)
                | ((resultData[3] & 0xFFFFL) << 32) | ((resultData[4] & 0xFFFFL) << 48);
    }

    /** 更新界面内的交易反馈并立即同步到客户端。 */
    private void setResult(int status, long amount) {
        resultData[0] = status;
        for (int index = 0; index < 4; index++) resultData[index + 1] = (int) ((amount >>> (index * 16)) & 0xFFFFL);
        broadcastChanges();
    }

    /** 排除所有可能绕过输出槽原子交易的快捷键和物品合并操作。 */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId == OUTPUT_SLOT && (clickType != ClickType.PICKUP || !getCarried().isEmpty())) return;
        super.clicked(slotId, button, clickType, player);
    }

    /** 双击聚合物品时禁止扫描输出预览。 */
    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.index != OUTPUT_SLOT && super.canTakeItemForPickAll(stack, slot);
    }

    /** 方块消失或玩家远离后拒绝继续交易。 */
    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).is(ModBlocks.ORE_ENCHANTING_TABLE.get())
                && player.canInteractWithBlock(pos, 4.0);
    }

    /** Shift 点击时优先把容器送入支付槽，其他可附魔物品送入目标槽。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= SLOT_COUNT || index == OUTPUT_SLOT) return ItemStack.EMPTY;
        Slot slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved;
        if (index < OUTPUT_SLOT) moved = moveItemStackTo(stack, INVENTORY_START, SLOT_COUNT, true);
        else if (isMeContainer(stack)) moved = moveItemStackTo(stack, CONTAINER_SLOT, CONTAINER_SLOT + 1, false);
        else if (canPlaceTarget(stack)) moved = moveItemStackTo(stack, TARGET_SLOT, TARGET_SLOT + 1, false);
        else return ItemStack.EMPTY;
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** 关闭菜单时返还两个输入槽内的物品。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) clearContainer(player, inputs);
    }

    /**
     * 更新一条附魔的目标等级并刷新服务端预览，此时不发生 ME 交易。
     *
     * @param player 发起请求的玩家
     * @param enchantmentId 附魔注册 ID
     * @param requestedLevel 目标等级
     */
    public void select(ServerPlayer player, ResourceLocation enchantmentId, int requestedLevel) {
        if (player.containerMenu != this || !stillValid(player)) return;
        resetSelectionsIfTargetChanged();
        var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var found = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, enchantmentId));
        if (found.isEmpty() || requestedLevel < 0
                || requestedLevel > Math.min(255, found.get().value().getMaxLevel())) return;
        int currentLevel = EnchantmentHelper.getEnchantmentsForCrafting(target()).getLevel(found.get());
        // 恢复原等级时删除变更记录，避免空操作产生可复制的结果预览。
        if (requestedLevel == currentLevel) selectedLevels.remove(enchantmentId);
        else selectedLevels.put(enchantmentId, requestedLevel);
        refreshPreview();
    }

    /** 换入新目标物品时清空上一件物品尚未领取的所有等级选择。 */
    private void resetSelectionsIfTargetChanged() {
        if (ItemStack.matches(selectedTarget, target())) return;
        selectedTarget = target().copy();
        selectedLevels.clear();
    }

    /**
     * 用当前输入制作附魔结果副本。预览阶段不要求 ME 充足。
     *
     * @return 合法结果及价格；输入或规则不满足时返回 null
     */
    private Prepared prepare() {
        if (selectedLevels.isEmpty()) return null;
        var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack target = target();
        ItemStack source = container();
        if (!canPlaceTarget(target) || !isMeContainer(source)
                || target.getCount() != 1 || source.getCount() != 1) return null;
        ItemEnchantments current = EnchantmentHelper.getEnchantmentsForCrafting(target);
        ItemEnchantments effective = target.getAllEnchantments(lookup);
        ItemEnchantments.Mutable updated = new ItemEnchantments.Mutable(current);
        BigInteger difference = BigInteger.ZERO;
        for (Map.Entry<ResourceLocation, Integer> choice : selectedLevels.entrySet()) {
            var found = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, choice.getKey()));
            if (found.isEmpty()) return null;
            Holder<Enchantment> enchantment = found.get();
            int selectedLevel = choice.getValue();
            int currentLevel = current.getLevel(enchantment);
            if (selectedLevel < 0 || selectedLevel > Math.min(255, enchantment.value().getMaxLevel())
                    || selectedLevel == currentLevel || effective.getLevel(enchantment) > currentLevel) return null;
            if (selectedLevel > currentLevel && !isBookTarget(target)
                    && !target.supportsEnchantment(enchantment)) return null;
            // 先累计每条附魔的价格差，再整体结算，允许同时升级与降级。
            difference = difference.add(BigInteger.valueOf(
                    EnchantMeCostCalculator.calculateDifference(enchantment, currentLevel, selectedLevel)));
            updated.set(enchantment, selectedLevel);
        }
        ItemEnchantments finalEnchantments = updated.toImmutable();
        // 移除冲突附魔后才添加新附魔也被允许；最终组合必须整体兼容。
        List<Holder<Enchantment>> finalHolders = new ArrayList<>(finalEnchantments.keySet());
        for (Holder<Enchantment> external : effective.keySet()) {
            if (current.getLevel(external) == 0 && !finalHolders.contains(external)) finalHolders.add(external);
        }
        for (int index = 0; index < finalHolders.size(); index++) {
            for (int other = index + 1; other < finalHolders.size(); other++) {
                if (!Enchantment.areCompatible(finalHolders.get(index), finalHolders.get(other))) return null;
            }
        }
        // 多条附魔的价格差可能超出 long；无法准确结算时拒绝预览。
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        if (difference.abs().compareTo(maximum) > 0) return null;
        long amount = difference.longValue();
        ItemStack result = target.is(Items.BOOK) && !finalEnchantments.isEmpty()
                ? target.transmuteCopy(Items.ENCHANTED_BOOK, 1)
                : target.is(Items.ENCHANTED_BOOK) && finalEnchantments.isEmpty()
                ? target.transmuteCopy(Items.BOOK, 1) : target.copy();
        // 附魔书还原普通书时移除旧存储组件，防止空书残留不可见附魔数据。
        if (result.is(Items.BOOK)) result.remove(DataComponents.STORED_ENCHANTMENTS);
        EnchantmentHelper.setEnchantments(result, finalEnchantments);
        return new Prepared(result, amount);
    }

    /** 输入或选择改变时重新同步结果槽，避免展示过期的快照。 */
    private void refreshPreview() {
        resetSelectionsIfTargetChanged();
        resultData[0] = RESULT_NONE;
        Prepared prepared = prepare();
        previewDifference = prepared == null ? 0L : prepared.difference();
        preview.setItem(0, prepared == null ? ItemStack.EMPTY : prepared.result());
        broadcastChanges();
    }

    /**
     * 服务端在交付物品前重算差额、扣费或存储，并消耗输入物。
     *
     * @param player 领取结果的玩家
     * @return 付款成功的结果；失败时返回空物品且不消耗输入
     */
    private ItemStack takePreview(ServerPlayer player) {
        if (player.containerMenu != this || !stillValid(player)) return ItemStack.EMPTY;
        Prepared prepared = prepare();
        if (prepared == null || !ItemStack.matches(preview.getItem(0), prepared.result())
                || prepared.difference() != previewDifference) return ItemStack.EMPTY;
        ItemStack source = container();
        long available = availableMe(player, source);
        long difference = prepared.difference();
        if (!canSettle(player)) {
            setResult(difference > 0 ? RESULT_INSUFFICIENT : RESULT_FULL, 0);
            return ItemStack.EMPTY;
        }
        ItemStack paidContainer = source.copy();
        if (source.getItem() instanceof OreContainerItem normal) {
            normal.setStoredMe(paidContainer, available - difference);
        } else if (difference != 0) {
            // 末影账户可能已被其他操作改变；存取失败时不能交付结果。
            boolean settled = difference > 0
                    ? OreConversionSavedData.get(player).debit(player, difference)
                    : OreConversionSavedData.get(player).credit(player, -difference);
            if (!settled) {
                setResult(difference > 0 ? RESULT_INSUFFICIENT : RESULT_FULL, 0);
                return ItemStack.EMPTY;
            }
        }
        // 清空输入会触发预览刷新；先清空结果以免旧副本再次被领取。
        preview.setItem(0, ItemStack.EMPTY);
        getSlot(TARGET_SLOT).set(ItemStack.EMPTY);
        if (source.getItem() instanceof OreContainerItem) getSlot(CONTAINER_SLOT).set(paidContainer);
        broadcastChanges();
        if (source.getItem() instanceof EnderOreContainerItem) OreConversionNetwork.sendState(player, -1);
        setResult(difference > 0 ? RESULT_SPENT : difference < 0 ? RESULT_STORED : RESULT_UNCHANGED,
                Math.abs(difference));
        return prepared.result();
    }

    /** 判断当前容器是否有足够余额或空间完成净 ME 交易。 */
    private boolean canSettle(ServerPlayer player) {
        ItemStack source = container();
        long available = availableMe(player, source);
        if (previewDifference > 0) return available >= previewDifference;
        if (previewDifference == 0) return true;
        long refund = -previewDifference;
        if (source.getItem() instanceof OreContainerItem normal) return refund <= normal.capacity() - available;
        return source.getItem() instanceof EnderOreContainerItem && refund <= Long.MAX_VALUE - available;
    }

    /** 一次验证得到的结果及有符号 ME 差额。 */
    private record Prepared(ItemStack result, long difference) {}

    /** 从支付容器读取 ME；末影容器通过玩家账户取得余额。 */
    private static long availableMe(ServerPlayer player, ItemStack source) {
        if (source.getItem() instanceof OreContainerItem normal) return normal.storedMe(source);
        if (source.getItem() instanceof EnderOreContainerItem ender) return ender.accountMe(player);
        return 0L;
    }

}
