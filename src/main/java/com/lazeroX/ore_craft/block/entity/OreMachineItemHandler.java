package com.lazeroX.ore_craft.block.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * 将机器已有的原版带方向库存适配为 NeoForge 管道使用的物品能力。
 * 有方向查询沿用漏斗规则；无方向查询可看到全部真实槽位，但进出仍须通过机器许可。
 * 包装器不暴露可直接改写库存的接口，避免管道绕过物品类型及方向检查。
 */
public final class OreMachineItemHandler implements IItemHandler {
    /** 被适配的机器库存，和菜单共享同一份持久物品。 */
    private final OreMachineInventory machine;
    /** NeoForge 提供的带方向槽位映射与插入、提取实现。 */
    private final SidedInvWrapper delegate;
    /** 管道查询的面；null 表示管道未指定方向。 */
    @Nullable
    private final Direction side;

    /**
     * 创建绑定指定方块实体和访问面的物品能力。
     *
     * @param machine 提供真实库存及自动化规则的机器
     * @param side 管道访问面；null 表示无方向查询
     */
    public OreMachineItemHandler(OreMachineInventory machine, @Nullable Direction side) {
        this.machine = machine;
        this.side = side;
        this.delegate = new SidedInvWrapper(machine, side);
    }

    /** 返回此面可见的真实槽位数量；无方向时包含全部真实槽位。 */
    @Override
    public int getSlots() { return delegate.getSlots(); }

    /** 返回对应真实槽位当前的物品，不包含转化器的虚拟选择图标。 */
    @Override
    public ItemStack getStackInSlot(int slot) { return delegate.getStackInSlot(slot); }

    /** 按漏斗相同的槽位和物品规则插入；无方向时拒绝绕过顶部专用入口。 */
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (side == null && !machine.canPlaceItemWithoutSide(slot, stack)) return stack;
        return delegate.insertItem(slot, stack, simulate);
    }

    /**
     * 有方向提取交由带方向库存判断；无方向提取额外执行机器限制。
     * 传输接口始终不能输出原料或容器，转化器只允许输出产物。
     */
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (side == null && !machine.canTakeItemWithoutSide(slot, delegate.getStackInSlot(slot)))
            return ItemStack.EMPTY;
        return delegate.extractItem(slot, amount, simulate);
    }

    /** 返回原版库存规定的单格上限；实际插入仍受物品自身堆叠上限限制。 */
    @Override
    public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }

    /** 判断此面能否向映射后的真实槽位放入物品，使管道预判与实际插入一致。 */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot < 0 || slot >= delegate.getSlots()) return false;
        int realSlot = SidedInvWrapper.getSlot(machine, slot, side);
        return side == null ? machine.canPlaceItemWithoutSide(realSlot, stack)
                : machine.canPlaceItemThroughFace(realSlot, stack, side);
    }
}
