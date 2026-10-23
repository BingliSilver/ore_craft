package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 保存矿质转化桌的单个交易容器，生命周期跟随方块和区块存档，不跟随菜单。
 * 各次打开的服务端菜单共用此库存；末影容器仍在实际交易时连接当前操作者的账户。
 * 基础版没有充值、提现槽，此库存也不提供自动化接口。
 */
public class OreContainerConversionTableBlockEntity extends BlockEntity {
    /** 交易容器在持久库存中的唯一索引。 */
    public static final int CONTAINER_SLOT = 0;
    /** 存档中保存完整容器物品 NBT的键名，包括普通容器内的 ME。 */
    private static final String CONTAINER_KEY = "TradeContainer";
    /** 单槽持久库存；放入、取出和交易写回 NBT都会标记区块需要保存。 */
    private final SimpleContainer inventory = new SimpleContainer(1) {
        /** 把菜单的库存变更传播到方块实体，确保关闭界面后仍能写入世界存档。 */
        @Override
        public void setChanged() {
            super.setChanged();
            OreContainerConversionTableBlockEntity.this.setChanged();
        }

        /** 交易槽只保存一个不可堆叠的矿质容器。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        /** 仅接受普通或末影矿质容器，保持与菜单槽位的放入规则一致。 */
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == CONTAINER_SLOT && OreMachineEnergy.isContainer(stack);
        }
    };

    /**
     * 为已放置的矿质转化桌创建空交易库存，随后可由区块存档恢复其内容。
     *
     * @param pos 转化桌坐标
     * @param state 当前方块状态
     */
    public OreContainerConversionTableBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.ORE_CONTAINER_CONVERSION_TABLE.get(), pos, state);
    }

    /**
     * 为共享交易规则的桌子创建持久供能库存，子类仅替换方块实体注册类型。
     *
     * @param type 与实际方块匹配的实体类型
     * @param pos 桌子坐标
     * @param state 当前方块状态
     */
    protected OreContainerConversionTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** 返回菜单直接操作的持久库存；关闭菜单不得将它返还或清空。 */
    public SimpleContainer inventory() {
        return inventory;
    }

    /**
     * 保存交易容器和它的完整物品 NBT；空槽不写入物品标签。
     *
     * @param tag 接收方块实体数据的标签
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ItemStack container = inventory.getItem(CONTAINER_SLOT);
        if (!container.isEmpty()) tag.put(CONTAINER_KEY, container.save(new CompoundTag()));
    }

    /**
     * 恢复交易容器；旧存档没有此数据时为空，异常类型不进入交易槽。
     *
     * @param tag 从区块读取的方块实体标签
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ItemStack container = ItemStack.of(tag.getCompound(CONTAINER_KEY));
        // 矿质容器只能单件存放；有效物品的全部 NBT 随副本保留，包含交易后的 ME。
        inventory.setItem(CONTAINER_SLOT, OreMachineEnergy.isContainer(container)
                ? container.copyWithCount(1) : ItemStack.EMPTY);
    }
}
