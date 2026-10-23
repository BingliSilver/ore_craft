package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.register.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 基础矿质合成台的持久供能槽，沿用转化桌的容器保存和 ME 写回规则。
 * 生命周期跟随方块；合成材料属于打开菜单的玩家，不存入此实体。
 */
public final class OreCraftingTableBlockEntity extends OreContainerConversionTableBlockEntity {
    /**
     * 创建单槽库存，区块加载时由父类恢复容器及完整 NBT。
     *
     * @param pos 合成台坐标
     * @param state 当前方块状态
     */
    public OreCraftingTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_CRAFTING_TABLE.get(), pos, state);
    }
}
