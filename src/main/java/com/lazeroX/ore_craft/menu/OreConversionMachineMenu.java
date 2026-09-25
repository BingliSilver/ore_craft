package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.block.entity.OreConversionMachineBlockEntity;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 矿质转化器菜单：真实库存只有支付容器和产物，中央选择框只是已学习目录的预览。
 * 服务端验证学习权限与价格后记录选择，客户端不能直接改写虚拟框或拿走其物品。
 */
public final class OreConversionMachineMenu extends AbstractContainerMenu {
    /** 三个机器格在菜单中的固定顺序。 */
    public static final int CONTAINER_SLOT = 0;
    public static final int SELECTION_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    /** 玩家背包起点及末尾的半开区间索引。 */
    private static final int INVENTORY_START = 3;
    private static final int INVENTORY_END = 39;
    /** 绑定的方块坐标及世界，用于拒绝过期菜单。 */
    private final BlockPos pos;
    private final Level level;
    /** 服务端持久库存；客户端只使用同步显示的临时库存。 */
    private final Container storage;
    /** 只用于同步图标的虚拟物品格，不随方块掉落。 */
    private final SimpleContainer selection = new SimpleContainer(1);
    /** 客户端没有真实方块实体，服务端在这里保留绑定对象。 */
    private final OreConversionMachineBlockEntity machine;
    /** 最近同步到客户端的五秒轮次进度。 */
    private int clientProgress;

    /** 从打开菜单时的附加数据读取方块坐标。 */
    public OreConversionMachineMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建真实支付和产物格、虚拟选择格及玩家背包。
     *
     * @param id 菜单同步编号
     * @param inventory 操作者背包
     * @param pos 矿质转化器的世界坐标
     */
    public OreConversionMachineMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_CONVERSION_MACHINE_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        this.machine = !level.isClientSide() && level.getBlockEntity(pos) instanceof OreConversionMachineBlockEntity entity
                ? entity : null;
        this.storage = machine == null ? new SimpleContainer(2) : machine.inventory();
        checkContainerSize(storage, 2);
        if (machine != null && machine.selected() != null) {
            selection.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(machine.selected())));
        }
        addSlot(new Slot(storage, OreConversionMachineBlockEntity.CONTAINER_SLOT, 26, 35) {
            /** 普通和末影矿质容器都可作为支付来源。 */
            @Override public boolean mayPlace(ItemStack stack) { return OreMachineEnergy.isContainer(stack); }
        });
        addSlot(new Slot(selection, 0, 80, 35) {
            /** 选择框的图标由服务端目录选择更新，不能放入或拿走真实物品。 */
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        addSlot(new Slot(storage, OreConversionMachineBlockEntity.OUTPUT_SLOT, 134, 35) {
            /** 产物只能由机器生成，玩家可以正常取走。 */
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        // 原版数据槽同步短整数进度；选择图标经真实菜单槽位单独同步。
        addDataSlot(new DataSlot() {
            @Override public int get() { return machine == null ? clientProgress : machine.progressTicks(); }
            @Override public void set(int value) { clientProgress = value; }
        });
    }

    /** 仅允许放置者在交互范围内操作当前方块。 */
    @Override
    public boolean stillValid(Player player) {
        if (!level.getBlockState(pos).is(ModBlocks.ORE_CONVERSION_MACHINE.get())
                || !player.canInteractWithBlock(pos, 4.0)) return false;
        if (level.isClientSide()) return true;
        return machine != null && level.getBlockEntity(pos) == machine
                && player.getUUID().equals(machine.owner());
    }

    /** Shift 点击仅移动真实容器或产物；虚拟选择框始终不参与物品转移。 */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < 0 || slotIndex >= INVENTORY_END
                || slotIndex == SELECTION_SLOT) return ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex == CONTAINER_SLOT || slotIndex == OUTPUT_SLOT) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
        } else if (!OreMachineEnergy.isContainer(stack)
                || !moveItemStackTo(stack, CONTAINER_SLOT, CONTAINER_SLOT + 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /**
     * 响应目录点击；只有放置者已学习、仍可提取且为普通物品的目标才能被选中。
     * 选择不会消耗模板，已有输出属于旧物品时需先取走才会生产新物品。
     *
     * @param player 发起选择的服务端玩家
     * @param id 目录中的目标物品 ID
     */
    public void select(ServerPlayer player, ResourceLocation id) {
        if (player.containerMenu != this || !stillValid(player) || id == null
                || !BuiltInRegistries.ITEM.containsKey(id)
                || !OreConversionSavedData.get(player).account(player).knows(id)) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack template = new ItemStack(item);
        if (!OreConversionPrices.canExtract(item) || !OreConversionPrices.isPlain(template)) return;
        machine.select(id);
        selection.setItem(0, template);
        broadcastChanges();
    }

    /** 返回当前轮次进度，供客户端绘制进度条。 */
    public int progressTicks() { return machine == null ? clientProgress : machine.progressTicks(); }
}
