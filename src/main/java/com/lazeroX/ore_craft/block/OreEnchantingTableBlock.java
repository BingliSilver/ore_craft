package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.block.entity.OreEnchantingBlockEntity;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 矿质附魔台的界面入口和书本动画方块实体宿主。
 * 方块实例由注册器创建；附魔交易由菜单在服务端处理，书本动画只在客户端更新。
 */
public final class OreEnchantingTableBlock extends Block implements EntityBlock {
    /** 方块属性的序列化编解码器。 */
    public static final MapCodec<OreEnchantingTableBlock> CODEC = simpleCodec(OreEnchantingTableBlock::new);
    /** 菜单标题，供服务端创建菜单时使用。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_enchanting_table");
    /**
     * 阶梯底座、内收台身和台面的实体轮廓，坐标单位为 1/16 格。
     * 小晶核并入台身范围；悬浮书仅为动画，不参与碰撞和方块选择。
     * 尺寸与 artwork/generate_ore_enchanting_table.py 同步，避免台面上方出现整格空气碰撞。
     */
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(1, 0, 1, 15, 1, 15),
            Block.box(0, 1, 0, 16, 2.5, 16),
            Block.box(1, 2.5, 1, 15, 3, 15),
            Block.box(2, 3, 2, 14, 8.5, 14),
            Block.box(1, 3, 1, 3, 8.5, 3), Block.box(13, 3, 1, 15, 8.5, 3),
            Block.box(1, 3, 13, 3, 8.5, 15), Block.box(13, 3, 13, 15, 8.5, 15),
            Block.box(1.25, 3.5, 6, 14.75, 8, 10),
            Block.box(6, 3.5, 1.25, 10, 8, 14.75),
            Block.box(1, 8.5, 1, 15, 9.25, 15),
            Block.box(0, 9.25, 0, 16, 11, 16),
            Block.box(1, 11, 1, 15, 11.5, 15),
            Block.box(1, 11.5, 1, 3, 12, 3), Block.box(13, 11.5, 1, 15, 12, 3),
            Block.box(1, 11.5, 13, 3, 12, 15), Block.box(13, 11.5, 13, 15, 12, 15)
    );

    /**
     * 创建矿质附魔台方块。
     *
     * @param properties 方块强度、声音及照明等基础属性
     */
    public OreEnchantingTableBlock(Properties properties) {
        super(properties);
    }

    /** 返回本方块的属性编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * 返回静态台座的选择轮廓，默认碰撞形状也复用此结果。
     *
     * @param state 当前方块状态；外形不随菜单或书本动画变化
     * @param level 方块所在世界
     * @param pos 方块位置
     * @param context 发起形状查询的实体上下文
     * @return 缓存的台座形状，最高为 12/16 格
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** 为每个已放置的附魔台创建独立的书本动画状态。 */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreEnchantingBlockEntity(pos, state);
    }

    /** 仅客户端逐刻更新书本；服务端不需要保存动画状态。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide || type != ModBlockEntities.ORE_ENCHANTING_TABLE.get()) return null;
        return (tickerLevel, pos, tickerState, entity) ->
                OreEnchantingBlockEntity.bookAnimationTick(tickerLevel, pos, tickerState,
                        (OreEnchantingBlockEntity) entity);
    }

    /**
     * 玩家交互时在服务端打开菜单，并将当前位置传给客户端用于有效性校验。
     *
     * @return 已处理交互，客户端和服务端均无需继续执行其他方块行为
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 为当前方块位置创建执行服务端附魔校验的菜单提供器。 */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreEnchantingMenu(id, inventory, pos), TITLE);
    }

    /** 方块被替换时掉落持久化的支付容器，避免其中的 ME 随方块实体一起丢失。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreEnchantingBlockEntity table) {
            Containers.dropContents(level, pos, table.paymentContainer());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
