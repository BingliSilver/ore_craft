package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.menu.OreConverterMenu;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.Containers;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 矿质转换器的世界方块，管理归属玩家、单格菜单与服务端定时转换。
 * 每个放置实例使用独立方块实体；挖掉时返还尚未转换的输入物。
 */
public final class OreConverterBlock extends Block implements EntityBlock {
    /** 方块属性编解码器。 */
    public static final MapCodec<OreConverterBlock> CODEC = simpleCodec(OreConverterBlock::new);
    /** 打开菜单时显示的标题。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_converter");

    /** 根据注册器传入的硬度、音效和亮度创建转换器方块。 */
    public OreConverterBlock(Properties properties) {
        super(properties);
    }

    /** 返回方块属性编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** 每次放置新方块时创建独立库存和计时状态。 */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreConverterBlockEntity(pos, state);
    }

    /** 转换计时只在服务端推进，客户端通过菜单数据槽读取进度。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.ORE_CONVERTER.get()) return null;
        return (tickLevel, pos, tickState, entity) ->
                OreConverterBlockEntity.serverTick(tickLevel, pos, tickState, (OreConverterBlockEntity) entity);
    }

    /** 由放置者领取转换收益，后续重新打开菜单不会改变账户归属。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof OreConverterBlockEntity converter) {
            converter.claim(player.getUUID());
        }
    }

    /**
     * 所有者打开单格库存；没有归属信息的旧方块由首次打开者认领。
     * 其他玩家不能放入物品，避免其投入物被记入别人的账户。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof OreConverterBlockEntity converter) {
            converter.claim(player.getUUID());
            if (!player.getUUID().equals(converter.owner())) {
                serverPlayer.sendSystemMessage(Component.translatable("message.ore_craft.converter.not_owner"));
                return InteractionResult.SUCCESS;
            }
            serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 创建绑定世界库存的菜单；关闭界面不会搬走未转换物品。 */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreConverterMenu(id, inventory, pos), TITLE);
    }

    /** 破坏方块时掉落尚未转换的库存，并避免旧方块实体吞掉物品。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreConverterBlockEntity converter) {
            Containers.dropContents(level, pos, converter.inventory());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
