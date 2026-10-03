package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.block.OreExperienceConverterBlock;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 使矿质经验转化器的潜行右键始终交给方块处理，包括玩家手持物品时。
 * 原版潜行交互通常会跳过方块而优先使用物品，因此客户端和服务端需要相同的路由规则。
 */
public final class OreExperienceConverterInteraction {
    /** 事件工具类只提供静态入口，不创建实例。 */
    private OreExperienceConverterInteraction() {}

    /**
     * 仅对经验转化器的潜行交互强制使用方块，避免放置手持方块或触发手持道具。
     * 方块的主手默认交互返回成功后，原版不会再次尝试副手，开关只切换一次。
     *
     * @param event 两端都会触发的方块右键事件
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() || event.getEntity().isSpectator()
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof OreExperienceConverterBlock)) return;
        event.setUseBlock(TriState.TRUE);
        event.setUseItem(TriState.FALSE);
    }
}
