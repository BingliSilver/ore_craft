package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.PortableOreConversionInterfaceItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.OptionalLong;

/**
 * 便携接口的单向回收菜单：输入物品被回收后存入物品 NBT 中的矿质容器并写入共享学习记录。
 * 两个槽位在使用期间由服务端备份到接口物品，关闭菜单时返还剩余原料和带余额的容器并清除备份。
 * 菜单只有原料输入和容器槽，不包含目录提取、余额扣除或 ME 到物品的转换操作。
 */
public final class PortableOreConversionInterfaceMenu extends AbstractContainerMenu {
    /** 菜单内两个设备槽的索引。 */
    public static final int INPUT_SLOT = 0;
    public static final int CONTAINER_SLOT = 1;
    /** 背包槽起始位置和总长度，用于限制快速移动范围。 */
    private static final int INVENTORY_START = 2;
    private static final int INVENTORY_END = INVENTORY_START + 36;
    /** 容器数据在便携接口物品 NBT 中的键名。 */
    private static final String CONTAINER_KEY = "PortableConversionContainer";
    /** 未回收原料的保存键；容量不足时保留原物品及其完整 NBT。 */
    private static final String INPUT_KEY = "PortableConversionInput";
    /** 打开菜单的玩家背包，用于区分逻辑端并标记持有物品的数据变化。 */
    private final Inventory playerInventory;
    /** 接口本体所在的背包索引；副手为 40，菜单打开期间禁止移动该槽物品。 */
    private final int interfaceInventorySlot;
    /** 打开菜单的持有手及其物品引用，防止把已切换走的另一件接口当作目标。 */
    private final InteractionHand hand;
    private final ItemStack openedStack;
    /** 两个会话槽；使用期间监听变化进行备份，关闭时清空并返还其中的物品。 */
    private final SimpleContainer inventory = new SimpleContainer(2);

    /**
     * 根据服务端附加数据创建菜单。
     *
     * @param id 菜单编号
     * @param playerInventory 玩家背包
     * @param extra 持有手枚举
     */
    public PortableOreConversionInterfaceMenu(int id, Inventory playerInventory, FriendlyByteBuf extra) {
        this(id, playerInventory, extra.readEnum(InteractionHand.class));
    }

    /**
     * 创建输入槽、容器槽和玩家背包；从接口恢复两个槽位后注册服务端保存监听。
     *
     * @param id 菜单编号
     * @param playerInventory 玩家背包
     * @param hand 打开菜单时持有接口的手
     */
    public PortableOreConversionInterfaceMenu(int id, Inventory playerInventory, InteractionHand hand) {
        super(ModMenus.PORTABLE_ORE_CONVERSION_INTERFACE_MENU.get(), id);
        this.playerInventory = playerInventory;
        interfaceInventorySlot = hand == InteractionHand.MAIN_HAND ? playerInventory.selected : 40;
        this.hand = hand;
        openedStack = playerInventory.player.getItemInHand(hand);
        ItemStack storedContainer = readContainer(openedStack);
        inventory.setItem(CONTAINER_SLOT, storedContainer);
        if (openedStack.hasTag()) {
            inventory.setItem(INPUT_SLOT, ItemStack.of(openedStack.getTag().getCompound(INPUT_KEY)));
        }
        // 必须先恢复两个槽再监听，避免加载第一个槽时覆盖尚未恢复的另一个槽。
        inventory.addListener(changed -> saveInventory());
        // 原料槽仅限制放入规则；已保存的原料即使价格配置改变也仍可取回。
        addSlot(new Slot(inventory, INPUT_SLOT, 62, 76) {
            /** 只允许有回收价格的物品进入原料槽。 */
            @Override public boolean mayPlace(ItemStack stack) {
                return OreConversionPrices.canDeposit(stack);
            }
        });
        // 容器的替换、ME 更新与取出均由库存监听统一保存。
        addSlot(new Slot(inventory, CONTAINER_SLOT, 158, 76) {
            /** 只接受普通或末影矿质容器。 */
            @Override public boolean mayPlace(ItemStack stack) { return OreMachineEnergy.isContainer(stack); }
        });
        PlayerInventoryLayout layout = new PlayerInventoryLayout(32, 145, 32, 203, 18);
        layout.addSlots(this::addPlayerSlot);
    }

    /**
     * 添加玩家背包槽并锁定接口本体，防止持久库存的宿主被移动或放入自身。
     *
     * @param index 玩家背包中的槽位索引
     * @param x 菜单横坐标，单位为像素
     * @param y 菜单纵坐标，单位为像素
     */
    private void addPlayerSlot(int index, int x, int y) {
        // 同时限制取出和放入，覆盖普通点击、拖拽与快速移动等原版操作。
        addSlot(new Slot(playerInventory, index, x, y) {
            /** 接口本体所在槽位不接受其他物品替换。 */
            @Override public boolean mayPlace(ItemStack stack) {
                return index != interfaceInventorySlot && super.mayPlace(stack);
            }

            /** 菜单仍打开时禁止取走持有持久库存的接口。 */
            @Override public boolean mayPickup(Player player) {
                return index != interfaceInventorySlot && super.mayPickup(player);
            }
        });
    }

    /**
     * 确认玩家仍在原持有位置使用打开菜单的接口。
     *
     * @param player 当前操作者
     * @return 客户端等待同步时允许显示，服务端需匹配原物品及手持位置
     */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide() || player.getItemInHand(hand) == openedStack
                && openedStack.getItem() instanceof PortableOreConversionInterfaceItem;
    }

    /**
     * 关闭菜单时将剩余原料和矿质容器返还玩家，背包装不下时按原版规则掉落。
     * 容器保留当前完整 NBT 和 ME；死亡或断线时也由原版清理逻辑处理物品去向。
     *
     * @param player 关闭菜单的玩家
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        // 只允许服务端返还，防止客户端预测产生重复物品。
        if (player.level().isClientSide()) return;
        clearContainer(player, inventory);
        // 原版清理使用 removeItemNoUpdate，不会触发库存监听；必须显式移除两个空槽的备份，
        // 否则下次打开会重新加载已经返还的物品，造成复制。
        saveInventory();
    }

    /**
     * 在服务端备份两个槽的完整物品数据，并通知玩家背包其持有物品已变化。
     * 关闭时槽位已清空，同一入口会删除备份，确保返还后接口不再持有库存。
     * 库存监听覆盖槽位点击和直接 setItem；客户端预测或槽位同步不得写回权威数据。
     */
    private void saveInventory() {
        if (playerInventory.player.level().isClientSide()) return;
        writeStoredStack(openedStack, CONTAINER_KEY, inventory.getItem(CONTAINER_SLOT));
        writeStoredStack(openedStack, INPUT_KEY, inventory.getItem(INPUT_SLOT));
        playerInventory.setChanged();
    }

    /**
     * 输入槽收到物品后立即按容器容量结算，且仅在成功入账时消耗原料。
     *
     * @param slotId 被点击菜单槽位
     * @param button 鼠标按键
     * @param clickType 原版点击类型
     * @param player 当前玩家
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // 数字键及副手交换会直接访问背包，须额外保护作为交换目标的接口槽。
        if (clickType == ClickType.SWAP && button == interfaceInventorySlot) return;
        super.clicked(slotId, button, clickType, player);
        if (player instanceof ServerPlayer serverPlayer && slotId == INPUT_SLOT && stillValid(player)) {
            convertInput(serverPlayer);
        }
    }

    /**
     * 服务端计算价格、容量、学习上限后再一次性提交本次回收。
     *
     * @param player 当前服务端玩家
     */
    private void convertInput(ServerPlayer player) {
        ItemStack input = inventory.getItem(INPUT_SLOT);
        ItemStack container = inventory.getItem(CONTAINER_SLOT);
        if (input.isEmpty() || !OreMachineEnergy.isContainer(container)) return;
        ItemStack learningStack = new ItemStack(input.getItem());
        OptionalLong unit = OreConversionPrices.depositValue(input);
        if (unit.isEmpty() || !OreConversionPrices.canLearn(learningStack)) return;
        OreConversionSavedData data = OreConversionSavedData.get(player);
        var id = BuiltInRegistries.ITEM.getKey(input.getItem());
        boolean known = data.account(player).knows(id);
        if (!known && data.account(player).learned().size() >= OreConversionSavedData.MAX_LEARNED) return;
        int count = OreMachineEnergy.conversionCount(input.getCount(), unit.getAsLong(),
                OreMachineEnergy.remaining(player.getServer(), player.getUUID(), container));
        if (count == 0) return;
        long amount = unit.getAsLong() * count;
        ItemStack updated = OreMachineEnergy.credit(player.getServer(), player.getUUID(), container, amount);
        if (updated == null) return;
        // 先确认 ME 写入成功，再消耗输入物并更新持久容器，保证容量不足时不损失原料。
        inventory.setItem(CONTAINER_SLOT, updated);
        inventory.setItem(INPUT_SLOT, count == input.getCount()
                ? ItemStack.EMPTY : input.copyWithCount(input.getCount() - count));
        data.learn(player, id);
        OreConversionNetwork.sendState(player, -1);
        broadcastChanges();
    }

    /**
     * 背包 Shift 点击时优先将容器放入接口，其余物品进入输入槽并触发回收。
     *
     * @param player 当前玩家
     * @param slotIndex 被 Shift 点击的菜单槽索引
     * @return 移动前物品副本；无效或未移动时返回空栈
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < INVENTORY_START || slotIndex >= INVENTORY_END) return ItemStack.EMPTY;
        Slot source = getSlot(slotIndex);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();
        int target = OreMachineEnergy.isContainer(stack) ? CONTAINER_SLOT : INPUT_SLOT;
        if (!getSlot(target).mayPlace(stack) || !moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY;
        source.setChanged();
        if (stack.isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        if (target == INPUT_SLOT && player instanceof ServerPlayer serverPlayer) convertInput(serverPlayer);
        return original;
    }

    /**
     * 从接口物品 NBT 读取有效的矿质容器副本，拒绝损坏或被篡改成普通物品的数据。
     *
     * @param interfaceStack 当前便携接口
     * @return 已校验的容器副本，无有效数据时为空栈
     */
    private static ItemStack readContainer(ItemStack interfaceStack) {
        if (!interfaceStack.hasTag()) return ItemStack.EMPTY;
        ItemStack stack = ItemStack.of(interfaceStack.getTag().getCompound(CONTAINER_KEY));
        return OreMachineEnergy.isContainer(stack) ? stack : ItemStack.EMPTY;
    }

    /**
     * 将指定槽位的完整物品数据写回接口，空槽时移除对应键。
     *
     * @param interfaceStack 持有库存数据的便携接口
     * @param key 对应槽位的保存键
     * @param stack 要保存的物品栈，包含数量和完整 NBT
     */
    private static void writeStoredStack(ItemStack interfaceStack, String key, ItemStack stack) {
        if (interfaceStack.isEmpty() || !(interfaceStack.getItem() instanceof PortableOreConversionInterfaceItem)) return;
        if (stack.isEmpty()) {
            if (interfaceStack.hasTag()) interfaceStack.getTag().remove(key);
        } else {
            interfaceStack.getOrCreateTag().put(key, stack.save(new CompoundTag()));
        }
    }
}
