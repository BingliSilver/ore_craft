package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreContainerConversionTableBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 容器结算版矿质转化桌，沿用共用外形和菜单交互，并为每张桌子保存独立的交易容器。
 * 关闭界面不会取走交易槽物品；方块被拆除或替换时掉落容器，避免丢失内部 ME。
 */
public final class OreContainerConversionTableBlock extends OreConversionTableBlock implements EntityBlock {
    /** 重建方块时保持容器版类型及其持久交易槽。 */
    public static final MapCodec<OreContainerConversionTableBlock> CODEC =
            simpleCodec(OreContainerConversionTableBlock::new);

    /**
     * 创建带持久交易槽的矿质转化桌。
     *
     * @param properties 方块的硬度、音效、亮度等基础属性
     */
    public OreContainerConversionTableBlock(Properties properties) {
        super(properties, true);
    }

    /** 返回容器版专用编解码器，避免序列化后退回没有方块实体的基础类型。 */
    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * 创建与此方块同生命周期的交易容器存储，无需服务端定时更新。
     *
     * @param pos 方块的世界坐标
     * @param state 当前方块状态
     * @return 负责保存单个交易容器的方块实体
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreContainerConversionTableBlockEntity(pos, state);
    }

    /**
     * 方块种类发生变化时，在服务端掉落持久容器；同一方块的状态更新不清空库存。
     *
     * @param state 被移除的方块状态
     * @param level 方块所在世界
     * @param pos 方块坐标
     * @param newState 替换后的方块状态
     * @param moved 是否由移动操作触发
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreContainerConversionTableBlockEntity table) {
            // 先掉落含最新 ME 组件的物品，再清空库存，防止仍打开的旧菜单重复取得容器。
            Containers.dropContents(level, pos, table.inventory());
            table.inventory().clearContent();
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
