package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreExperienceConverterBlockEntity;
import com.lazeroX.ore_craft.menu.OreExperienceConverterMenu;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 矿质经验转化器的世界方块：普通右键管理容器，潜行右键切换经验生产。
 * 新放置的机器默认关闭；开关由方块状态随区块保存，库存和账户归属由方块实体保存。
 * 仅放置者可以操作，以免末影容器意外消耗其他玩家的全局 ME。
 */
public final class OreExperienceConverterBlock extends Block implements EntityBlock {
    /** 用于重建方块属性的编解码器。 */
    public static final MapCodec<OreExperienceConverterBlock> CODEC = simpleCodec(OreExperienceConverterBlock::new);
    /** 生产开关；同时用于模型切换和菜单状态展示。 */
    public static final BooleanProperty ENABLED = BlockStateProperties.ENABLED;
    /** 单槽容器菜单的本地化标题。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_experience_converter");

    /**
     * 创建默认关闭的机器，拆除后重新放置也不会自动开启。
     *
     * @param properties 注册时指定的硬度、音效与亮度属性
     */
    public OreExperienceConverterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ENABLED, false));
    }

    /** 返回方块属性编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    /** 将开关加入方块状态，使服务器修改后自动同步到客户端并持久化。 */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ENABLED);
    }

    /** 为每个放置位置创建独立的容器库存、归属和轮次计时。 */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreExperienceConverterBlockEntity(pos, state);
    }

    /** 仅在服务端执行经验生产，客户端通过菜单同步获取显示数据。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.ORE_EXPERIENCE_CONVERTER.get()) return null;
        return (tickLevel, pos, tickState, entity) ->
                OreExperienceConverterBlockEntity.serverTick(tickLevel, pos, tickState,
                        (OreExperienceConverterBlockEntity) entity);
    }

    /** 放置时固定末影容器连接的玩家账户，后续开关与菜单操作不会更换账户。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof OreExperienceConverterBlockEntity converter) {
            converter.claim(player.getUUID());
        }
    }

    /**
     * 普通右键打开持久容器槽，潜行右键只切换开关并显示动作栏反馈。
     * 无归属的命令放置方块由首位操作者认领；旁观者不能更改机器状态。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && !player.isSpectator()
                && level.getBlockEntity(pos) instanceof OreExperienceConverterBlockEntity converter) {
            converter.claim(player.getUUID());
            if (!player.getUUID().equals(converter.owner())) {
                player.displayClientMessage(Component.translatable("message.ore_craft.experience.not_owner"), true);
                return InteractionResult.SUCCESS;
            }
            if (player.isShiftKeyDown()) {
                // 先重置轮次再切换方块状态，重新开启后总是等待完整一秒。
                boolean enabled = !state.getValue(ENABLED);
                converter.resetProgress();
                level.setBlock(pos, state.setValue(ENABLED, enabled), Block.UPDATE_ALL);
                player.displayClientMessage(Component.translatable(enabled
                        ? "message.ore_craft.experience.enabled" : "message.ore_craft.experience.disabled"), true);
            } else {
                serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 创建绑定真实库存的菜单；关闭界面后容器仍留在机器中继续供能。 */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreExperienceConverterMenu(id, inventory, pos), TITLE);
    }

    /** 仅在方块被替换或拆除时掉落容器；切换开关不会重复掉落库存。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreExperienceConverterBlockEntity converter) {
            Containers.dropContents(level, pos, converter.inventory());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
