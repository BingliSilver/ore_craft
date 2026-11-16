package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.PortableOreConversionInterfaceItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.registries.BuiltInRegistries;
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
    /** 打开菜单的持有手及其物品引用，防止把已切换走的另一件接口当作目标。 */
    private final InteractionHand hand;
    private final ItemStack openedStack;
    /** 输入物品是菜单临时槽；容器槽的变化立即写入接口本体。 */
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
     * 创建输入槽、容器槽和玩家背包；已有容器从接口物品数据恢复。
     *
     * @param id 菜单编号
     * @param playerInventory 玩家背包
     * @param hand 打开菜单时持有接口的手
     */
    public PortableOreConversionInterfaceMenu(int id, Inventory playerInventory, InteractionHand hand) {
        super(ModMenus.PORTABLE_ORE_CONVERSION_INTERFACE_MENU.get(), id);
        this.hand = hand;
        openedStack = playerInventory.player.getItemInHand(hand);
        ItemStack storedContainer = readContainer(openedStack);
        inventory.setItem(CONTAINER_SLOT, storedContainer);
        addSlot(new Slot(inventory, INPUT_SLOT, 62, 76) {
            @Override public boolean mayPlace(ItemStack stack) {
                return OreConversionPrices.canDeposit(stack);
            }
        });
        addSlot(new Slot(inventory, CONTAINER_SLOT, 158, 76) {
            @Override public boolean mayPlace(ItemStack stack) { return OreMachineEnergy.isContainer(stack); }
            @Override public void setChanged() {
                super.setChanged();
                // 每次替换容器后将完整 NBT 写回接口，关闭菜单也不会带走或清空余额。
                writeContainer(openedStack, getItem());
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + column,
                        32 + column * 18, 145 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 32 + column * 18, 203));
        }
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
     * 输入槽收到物品后立即按容器容量结算，且仅在成功入账时消耗原料。
     *
     * @param slotId 被点击菜单槽位
     * @param button 鼠标按键
     * @param clickType 原版点击类型
     * @param player 当前玩家
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
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
        if (!source.hasItem()) return ItemStack.EMPTY;
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
     * 将当前容器写回接口物品，空槽时移除数据键以保持物品数据紧凑。
     *
     * @param interfaceStack 持有容器数据的便携接口
     * @param container 要保存的容器栈
     */
    private static void writeContainer(ItemStack interfaceStack, ItemStack container) {
        if (interfaceStack.isEmpty() || !(interfaceStack.getItem() instanceof PortableOreConversionInterfaceItem)) return;
        if (container.isEmpty()) {
            if (interfaceStack.hasTag()) interfaceStack.getTag().remove(CONTAINER_KEY);
        } else {
            interfaceStack.getOrCreateTag().put(CONTAINER_KEY, container.save(new net.minecraft.nbt.CompoundTag()));
        }
    }
}
