package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.client.OreConversionClient;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
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
 * 矿质转换器的单格菜单，服务端直接操作方块实体库存。
 * 客户端仅显示同步来的物品与进度；所有放入条件在服务端槽位重新核验。
 */
public final class OreConverterMenu extends AbstractContainerMenu {
    /** 唯一输入格位于所有玩家背包格之前。 */
    public static final int INPUT_SLOT = 0;
    /** 玩家背包首格索引。 */
    private static final int INVENTORY_START = 1;
    /** 玩家背包和快捷栏之后的索引，不包含该位置。 */
    private static final int INVENTORY_END = 37;
    /** 菜单绑定的方块坐标。 */
    private final BlockPos pos;
    /** 当前世界，用于验证菜单与方块实体的对应关系。 */
    private final Level level;
    /** 服务端持久库存；客户端使用只供同步的临时库存。 */
    private final Container input;
    /** 服务端方块实体，客户端为空。 */
    private final OreConverterBlockEntity converter;
    /** 客户端最近收到的 0 到 99 刻进度。 */
    private int clientProgress;

    /** 从打开菜单时的附加数据读取方块坐标并创建客户端菜单。 */
    public OreConverterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建单格菜单并绑定方块实体；玩家背包可通过 Shift 点击快速放入合格物品。
     *
     * @param id 容器同步编号
     * @param inventory 操作者背包
     * @param pos 矿质转换器的世界坐标
     */
    public OreConverterMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_CONVERTER_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        this.converter = !level.isClientSide() && level.getBlockEntity(pos) instanceof OreConverterBlockEntity blockEntity
                ? blockEntity : null;
        this.input = converter == null ? new SimpleContainer(1) : converter.inventory();
        checkContainerSize(input, 1);
        addSlot(new Slot(input, 0, 80, 35) {
            /** 价格来源和普通物品状态都由服务端的可转换规则决定。 */
            @Override
            public boolean mayPlace(ItemStack stack) {
                // 客户端读取服务端同步的可转换标记，服务端以实时价格和来源规则最终裁定。
                return level.isClientSide() ? OreConversionClient.canConvert(stack)
                        : OreConversionPrices.canDeposit(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
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
        if (!level.getBlockState(pos).is(ModBlocks.ORE_CONVERTER.get())
                || !player.canInteractWithBlock(pos, 4.0)) return false;
        // 所有权只由服务端验证；客户端方块实体没有同步 UUID，不能据此关闭界面。
        if (level.isClientSide()) return true;
        return converter != null && level.getBlockEntity(pos) == converter
                && player.getUUID().equals(converter.owner());
    }

    /** Shift 点击只会把符合转化规则的物品放进唯一输入格，或将输入物取回背包。 */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < 0 || slotIndex >= INVENTORY_END) return ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex == INPUT_SLOT) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
        } else {
            boolean convertible = level.isClientSide() ? OreConversionClient.canConvert(stack)
                    : OreConversionPrices.canDeposit(stack);
            if (!convertible
                    || !moveItemStackTo(stack, INPUT_SLOT, INPUT_SLOT + 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** 返回同步后的等待刻数，供界面绘制下一轮的进度条。 */
    public int progressTicks() {
        return converter == null ? clientProgress : converter.progressTicks();
    }
}
