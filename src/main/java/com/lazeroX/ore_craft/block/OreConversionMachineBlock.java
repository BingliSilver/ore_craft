package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreConversionMachineBlockEntity;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
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
import net.minecraft.world.phys.BlockHitResult;

/**
 * 矿质转化器的世界方块，提供只有放置者可用的选物、支付和输出界面。
 * 方块实体独立计时；破坏时返还容器与产物，虚拟选择不会作为实物掉落。
 */
public final class OreConversionMachineBlock extends Block implements EntityBlock {
    /** 方块属性编解码器，供游戏保存和复制方块状态。 */
    public static final MapCodec<OreConversionMachineBlock> CODEC = simpleCodec(OreConversionMachineBlock::new);
    /** 菜单标题使用语言文件，客户端和服务端保持一致。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_conversion_machine");

    /**
     * 使用注册时指定的硬度、音效和亮度创建方块。
     *
     * @param properties 注册器传入的方块属性
     */
    public OreConversionMachineBlock(Properties properties) { super(properties); }

    /** 返回此方块的属性编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    /**
     * 每个放置实例创建独立支付容器、输出和计时状态。
     *
     * @param pos 方块世界坐标
     * @param state 当前方块状态
     * @return 新建的矿质转化器方块实体
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreConversionMachineBlockEntity(pos, state);
    }

    /**
     * 仅由服务端推进生产，客户端通过菜单数据槽读取进度。
     *
     * @param level 方块所在世界
     * @param state 当前方块状态
     * @param type 请求的方块实体类型
     * @return 类型匹配时的服务端计时器，否则为 null
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.ORE_CONVERSION_MACHINE.get()) return null;
        return (tickLevel, pos, tickState, entity) -> OreConversionMachineBlockEntity.serverTick(
                tickLevel, pos, tickState, (OreConversionMachineBlockEntity) entity);
    }

    /** 放置时绑定所有者，避免后来打开菜单的玩家使用别人的学习目录。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof OreConversionMachineBlockEntity machine) {
            machine.claim(player.getUUID());
        }
    }

    /** 所有者打开菜单；旧方块缺少归属时由首次打开者认领。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof OreConversionMachineBlockEntity machine) {
            machine.claim(player.getUUID());
            if (!player.getUUID().equals(machine.owner())) {
                serverPlayer.sendSystemMessage(Component.translatable("message.ore_craft.machine.not_owner"));
                return InteractionResult.SUCCESS;
            }
            serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
            // 每次打开都刷新学习目录，避免登录早期的同步尚未到达时右侧列表为空。
            if (serverPlayer.containerMenu instanceof OreConversionMachineMenu)
                OreConversionNetwork.sendState(serverPlayer, -1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 创建绑定当前方块实体的菜单，关闭时库存留在方块内。 */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreConversionMachineMenu(id, inventory, pos), TITLE);
    }

    /** 破坏方块时掉落真实库存；选中物品只是目录引用，无需掉落。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof OreConversionMachineBlockEntity machine) {
            Containers.dropContents(level, pos, machine.inventory());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
