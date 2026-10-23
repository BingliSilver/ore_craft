package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.block.entity.OreCraftingTableBlockEntity;
import com.lazeroX.ore_craft.menu.OreCraftingMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * 矿质合成台两款方块的交互入口，外形参考原版工作台。
 * 基础款保存供能容器，Plus 款直接使用操作者账户；两款均保留完整转化桌功能。
 */
public final class OreCraftingTableBlock extends Block implements EntityBlock {
    /** 注册时固定的版本；Plus 不创建方块库存。 */
    private final boolean upgraded;

    /**
     * 创建合成台方块。
     *
     * @param properties 方块硬度、音效和工具要求
     * @param upgraded 是否为使用个人账户的 Plus 款
     */
    public OreCraftingTableBlock(Properties properties, boolean upgraded) {
        super(properties);
        this.upgraded = upgraded;
    }

    /** 只为基础款创建持久供能槽；两款都不需要每刻更新方块实体。 */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return upgraded ? null : new OreCraftingTableBlockEntity(pos, state);
    }

    /** 打开菜单后同步当前价格和学习目录；结算对象始终是打开界面的玩家。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, getMenuProvider(state, level, pos), data -> data.writeBlockPos(pos));
            OreConversionNetwork.sendPrices(serverPlayer);
            OreConversionNetwork.sendState(serverPlayer, serverPlayer.containerMenu.containerId);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 为每次交互创建独立合成网格，基础款的交易容器仍由方块实体共享。 */
    @Override
    public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider((id, inventory, player) -> new OreCraftingMenu(id, inventory, pos, !upgraded),
                Component.translatable(upgraded ? "block.ore_craft.ore_crafting_table_plus"
                        : "block.ore_craft.ore_crafting_table"));
    }

    /** 拆除基础款时掉落含最新 ME 的容器，并清空旧库存以阻止旧菜单重复取出。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide() && !state.is(next.getBlock())
                && level.getBlockEntity(pos) instanceof OreCraftingTableBlockEntity table) {
            Containers.dropContents(level, pos, table.inventory());
            table.inventory().clearContent();
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
