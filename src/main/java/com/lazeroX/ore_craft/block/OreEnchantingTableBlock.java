package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.block.entity.OreEnchantingBlockEntity;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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
}
