package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;

public final class OreConversionTableBlock extends Block {
    public static final MapCodec<OreConversionTableBlock> CODEC = simpleCodec(OreConversionTableBlock::new);
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_conversion_table");
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(2, 9, 2, 14, 14, 14),
            Block.box(0, 0, 0, 4, 2, 4), Block.box(12, 0, 0, 16, 2, 4),
            Block.box(0, 0, 12, 4, 2, 16), Block.box(12, 0, 12, 16, 2, 16),
            Block.box(1, 2, 1, 3, 12, 3), Block.box(13, 2, 1, 15, 12, 3),
            Block.box(1, 2, 13, 3, 12, 15), Block.box(13, 2, 13, 15, 12, 15),
            Block.box(0, 12, 0, 4, 16, 4), Block.box(12, 12, 0, 16, 16, 4),
            Block.box(0, 12, 12, 4, 16, 16), Block.box(12, 12, 12, 16, 16, 16),
            Block.box(4, 0, 2, 12, 2, 4), Block.box(4, 0, 12, 12, 2, 14),
            Block.box(2, 0, 4, 4, 2, 12), Block.box(12, 0, 4, 14, 2, 12)
    );

    public OreConversionTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
            if (serverPlayer.containerMenu instanceof OreConversionMenu menu) {
                OreConversionNetwork.sendPrices(serverPlayer);
                menu.sync(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreConversionMenu(id, inventory, pos), TITLE);
    }
}
