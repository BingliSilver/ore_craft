package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.block.OreConverterBlock;
import com.lazeroX.ore_craft.client.OreConversionClient;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 普通版和升级版矿质传输接口共用的双格菜单，服务端直接操作输入物和收款容器。
 * 客户端仅显示同步来的物品与进度；所有放入条件在服务端槽位重新核验。
 */
public final class OreConverterMenu extends AbstractContainerMenu {
    /** 新机器界面共用的背包坐标，客户端与服务端槽位保持相同布局。 */
    public static final int INVENTORY_X = 29;
    public static final int INVENTORY_Y = 148;
    public static final int HOTBAR_Y = 206;
    /** 原料格和矿质容器格位于所有玩家背包格之前。 */
    public static final int INPUT_SLOT = 0;
    public static final int CONTAINER_SLOT = 1;
    /** 玩家背包首格索引。 */
    private static final int INVENTORY_START = 2;
    /** 玩家背包和快捷栏之后的索引，不包含该位置。 */
    private static final int INVENTORY_END = 38;
    /** 菜单绑定的方块坐标。 */
    private final BlockPos pos;
    /** 当前世界，用于验证菜单与方块实体的对应关系。 */
    private final Level level;
    /** 服务端持久库存；客户端使用只供同步的临时库存。 */
    private final Container storage;
    /** 服务端方块实体，客户端为空。 */
    private final OreConverterBlockEntity converter;
    /** 客户端最近收到的轮次进度；上限由方块等级决定。 */
    private int clientProgress;

    /** 从打开菜单时的附加数据读取方块坐标并创建客户端菜单。 */
    public OreConverterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建双格菜单并绑定方块实体；玩家背包可通过 Shift 点击放入原料或容器。
     *
     * @param id 容器同步编号
     * @param inventory 操作者背包
     * @param pos 矿质传输接口的世界坐标
     */
    public OreConverterMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_CONVERTER_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        this.converter = !level.isClientSide() && level.getBlockEntity(pos) instanceof OreConverterBlockEntity blockEntity
                ? blockEntity : null;
        this.storage = converter == null ? new SimpleContainer(2) : converter.inventory();
        checkContainerSize(storage, 2);
        addSlot(new Slot(storage, OreConverterBlockEntity.INPUT_SLOT, 54, 76) {
            /** 价格来源和普通物品状态都由服务端的可转换规则决定。 */
            @Override
            public boolean mayPlace(ItemStack stack) {
                // 客户端读取服务端同步的可转换标记，服务端以实时价格和来源规则最终裁定。
                return level.isClientSide() ? OreConversionClient.canConvert(stack)
                        : OreConversionPrices.canDeposit(stack);
            }
        });
        addSlot(new Slot(storage, OreConverterBlockEntity.CONTAINER_SLOT, 150, 76) {
            /** 两种矿质容器都可接收 ME；末影容器实际写入放置者的全局账户。 */
            @Override
            public boolean mayPlace(ItemStack stack) {
                return OreMachineEnergy.isContainer(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
        // 数据槽同步短整数进度；服务端拥有真实计时，客户端只接收展示值。
        addDataSlot(new DataSlot() {
            @Override public int get() { return converter == null ? clientProgress : converter.progressTicks(); }
            @Override public void set(int value) { clientProgress = value; }
        });
    }

    /** 方块实体、所有者和交互距离均有效时才允许继续操作。 */
    @Override
    public boolean stillValid(Player player) {
        if (!(level.getBlockState(pos).getBlock() instanceof OreConverterBlock)
                || !player.canInteractWithBlock(pos, 4.0)) return false;
        // 所有权只由服务端验证；客户端方块实体没有同步 UUID，不能据此关闭界面。
        if (level.isClientSide()) return true;
        return converter != null && level.getBlockEntity(pos) == converter
                && player.getUUID().equals(converter.owner());
    }

    /** Shift 点击先把容器放入收款格；收款格已占用时可将可回收容器放入输入格。 */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < 0 || slotIndex >= INVENTORY_END) return ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex < INVENTORY_START) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
        } else {
            if (OreMachineEnergy.isContainer(stack)) {
                // 收款容器已有物品时，允许另一个矿质容器进入输入格回收。
                if (!moveItemStackTo(stack, CONTAINER_SLOT, CONTAINER_SLOT + 1, false)) {
                    boolean convertible = level.isClientSide() ? OreConversionClient.canConvert(stack)
                            : OreConversionPrices.canDeposit(stack);
                    if (!convertible || !moveItemStackTo(stack, INPUT_SLOT, INPUT_SLOT + 1, false))
                        return ItemStack.EMPTY;
                }
            } else {
                boolean convertible = level.isClientSide() ? OreConversionClient.canConvert(stack)
                        : OreConversionPrices.canDeposit(stack);
                if (!convertible
                        || !moveItemStackTo(stack, INPUT_SLOT, INPUT_SLOT + 1, false)) return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** 返回同步后的等待刻数，供界面绘制下一轮的进度条。 */
    public int progressTicks() {
        return converter == null ? clientProgress : converter.progressTicks();
    }

    /** 返回当前等级的轮次时长，客户端据此把相同进度条缩放到一秒或五秒。 */
    public int intervalTicks() {
        return isUpgraded() ? OreConverterBlockEntity.PLUS_INTERVAL_TICKS : OreConverterBlockEntity.INTERVAL_TICKS;
    }

    /** 判断菜单绑定的是否为下界合金升级版接口。 */
    public boolean isUpgraded() {
        return level.getBlockState(pos).is(ModBlocks.ORE_CONVERTER_PLUS.get());
    }
}
