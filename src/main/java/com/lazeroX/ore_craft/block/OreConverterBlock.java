package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.menu.OreConverterMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
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
 * 普通版与升级版矿质传输接口共用的世界方块，管理归属玩家、库存和服务端定时转换。
 * 两个等级使用相同菜单；每个放置实例独立保存原料、容器和进度，挖掉时返还库存。
 */
public final class OreConverterBlock extends Block implements EntityBlock {
    /** 方块属性编解码器。 */
    public static final MapCodec<OreConverterBlock> CODEC = simpleCodec(OreConverterBlock::new);
    /** 升级版的编解码器，重建时保留方块等级。 */
    public static final MapCodec<OreConverterBlock> PLUS_CODEC = simpleCodec(properties -> new OreConverterBlock(properties, true));
    /** 打开菜单时显示的标题。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_converter");
    /** 升级版菜单标题；界面布局与普通版共用。 */
    private static final Component PLUS_TITLE = Component.translatable("container.ore_craft.ore_converter_plus");
    /** 注册时固定的设备等级，决定标题和方块编解码器。 */
    private final boolean upgraded;

    /** 根据注册器传入的硬度、音效和亮度创建传输接口方块。 */
    public OreConverterBlock(Properties properties) {
        this(properties, false);
    }

    /** 创建指定等级的接口；升级版沿用原有库存、所有权和菜单规则。 */
    public OreConverterBlock(Properties properties, boolean upgraded) {
        super(properties);
        this.upgraded = upgraded;
    }

    /** 返回方块属性编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() {
        return upgraded ? PLUS_CODEC : CODEC;
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

    /** 为末影容器固定放置者账户，后续重新打开菜单不会改变归属。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof OreConverterBlockEntity converter) {
            converter.claim(player.getUUID());
        }
    }

    /**
     * 所有者打开原料与容器库存；没有归属信息的旧方块由首次打开者认领。
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
            // 客户端输入格依赖价格目录显示可放入状态，打开时补发最新配置。
            if (serverPlayer.containerMenu instanceof OreConverterMenu) {
                OreConversionNetwork.sendPrices(serverPlayer);
                OreConversionNetwork.sendState(serverPlayer, -1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 创建绑定世界库存的菜单；关闭界面不会搬走原料或容器。 */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreConverterMenu(id, inventory, pos),
                upgraded ? PLUS_TITLE : TITLE);
    }

    /** 破坏方块时掉落原料和容器，避免旧方块实体吞掉物品。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreConverterBlockEntity converter) {
            Containers.dropContents(level, pos, converter.inventory());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
