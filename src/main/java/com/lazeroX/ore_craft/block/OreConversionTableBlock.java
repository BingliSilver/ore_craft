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

/** 打开矿质转化菜单的两种转化桌；容器版本沿用外形和交互流程，使用独立菜单类型。 */
public final class OreConversionTableBlock extends Block {
    /** 用于编码转化桌方块属性的 MapCodec。 */
    public static final MapCodec<OreConversionTableBlock> CODEC = simpleCodec(OreConversionTableBlock::new);
    /** 容器版本使用固定开启第三槽的构造方式，保证方块序列化后仍保留用途。 */
    public static final MapCodec<OreConversionTableBlock> CONTAINER_CODEC =
            simpleCodec(properties -> new OreConversionTableBlock(properties, true));
    /** 转化桌菜单标题。 */
    private static final Component TITLE = Component.translatable("container.ore_craft.ore_conversion_table");
    /** 新版转化桌的本地化菜单标题。 */
    private static final Component CONTAINER_TITLE = Component.translatable("container.ore_craft.ore_container_conversion_table");
    /** 是否打开带交易容器槽的新版菜单；由方块注册时固定。 */
    private final boolean containerBacked;
    /** 转化桌模型对应的碰撞与选择形状。 */
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

    /**
     * 创建转化桌方块。
     *
     * @param properties 方块的基础属性
     */
    public OreConversionTableBlock(Properties properties) {
        this(properties, false);
    }

    /**
     * 创建指定版本的转化桌，共用碰撞形状和基础交互。
     *
     * @param properties 方块属性
     * @param containerBacked 为 true 时交易 ME 必须由第三槽的容器结算
     */
    public OreConversionTableBlock(Properties properties, boolean containerBacked) {
        super(properties);
        this.containerBacked = containerBacked;
    }

    /** 返回该方块的序列化编解码器。 */
    @Override
    protected MapCodec<? extends Block> codec() {
        return containerBacked ? CONTAINER_CODEC : CODEC;
    }

    /** 返回转化桌自定义的外形。 */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * 玩家空手交互时打开转化桌菜单并同步价格目录与账户状态。
     *
     * @param state 当前方块状态
     * @param level 方块所在世界
     * @param pos 方块坐标
     * @param player 发起交互的玩家
     * @param hit 命中的方块位置与方向
     * @return 按客户端或服务端返回对应成功结果
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            // 菜单额外数据携带方块坐标，供服务端验证玩家仍在交互范围内。
            serverPlayer.openMenu(state.getMenuProvider(level, pos), data -> data.writeBlockPos(pos));
            if (serverPlayer.containerMenu instanceof OreConversionMenu menu) {
                OreConversionNetwork.sendPrices(serverPlayer);
                menu.sync(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 创建带本方块坐标的转化菜单提供器。
     *
     * @param state 当前方块状态
     * @param level 方块所在世界
     * @param pos 方块坐标
     * @return 创建转化桌菜单的提供器
     */
    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreConversionMenu(id, inventory, pos, containerBacked),
                containerBacked ? CONTAINER_TITLE : TITLE);
    }
}
