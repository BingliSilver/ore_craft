package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.entity.MineralTeleportPortalEntity;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.player.TeleportWaypointSavedData;
import com.lazeroX.ore_craft.register.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理权杖传送门的创建与进入结算，所有方法仅在服务端主线程调用。
 * 每名玩家最多保留一个可用入口；点击菜单只创建门，实际传送必须由实体检测到本人进入。
 * 仅支持同维度目标，进入时按距离直接扣除玩家全局 ME，不需要携带矿质容器。
 */
public final class MineralTeleportation {
    /** 入口中心位于玩家水平朝向前方 2.5 格，不跟随俯仰角埋入地面或悬在头顶。 */
    private static final double SPAWN_DISTANCE = 2.5;
    /** 当前玩家的入口引用，实体移除和服务器关闭时主动清理，不跨世界会话保留。 */
    private static final Map<UUID, MineralTeleportPortalEntity> PORTALS = new HashMap<>();

    /** 服务端生命周期工具类不创建实例。 */
    private MineralTeleportation() {}

    /**
     * 校验本人状态、目标与玩家处于同一维度及前方空间后生成传送门，此时不扣除 ME。
     * 新门成功加入世界后才收起旧门，生成失败时仍可继续使用原来的入口。
     * @param player 菜单请求对应的玩家
     * @param waypoint 已从本人存档找到的标记
     * @return 成功为 null；失败为界面提示的本地化键后缀
     */
    @Nullable
    public static String openPortal(ServerPlayer player, TeleportWaypoint waypoint) {
        if (!canTravel(player)) return "unavailable";
        if (!waypoint.dimension().equals(player.level().dimension().location())) return "wrong_dimension";
        ServerLevel level = player.serverLevel();
        Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 position = player.position().add(forward.scale(SPAWN_DISTANCE));
        AABB space = MineralTeleportPortalEntity.portalBounds(position, player.getYRot());
        if (space.minY < level.getMinBuildHeight() || space.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(space)
                || !level.noCollision(player, space) || level.containsAnyLiquid(space)) return "portal_obstructed";
        // 门前有墙时不能隔墙生成；完整门框空间已单独检查，射线只验证玩家能直接接近。
        Vec3 center = position.add(0, MineralTeleportPortalEntity.HEIGHT / 2, 0);
        if (level.clip(new ClipContext(player.getEyePosition(), center, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) return "portal_obstructed";

        MineralTeleportPortalEntity portal = new MineralTeleportPortalEntity(ModEntities.MINERAL_TELEPORT_PORTAL.get(), level);
        portal.initialize(player, waypoint.id(), position);
        if (!level.addFreshEntity(portal)) return "blocked";
        MineralTeleportPortalEntity previous = PORTALS.put(player.getUUID(), portal);
        if (previous != null && !previous.isRemoved()) previous.beginClosing();
        level.playSound(null, position.x, position.y + 1, position.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7F, 1.5F);
        return null;
    }

    /**
     * 只有当前有效入口的拥有者可以触发传送；进入时重新读取坐标、校验维度和落点并结算全局 ME。
     * 先检查落点，再按进入位置计算费用并扣款；未实际到达或发生异常时退还本次费用。
     * 无论成功还是失败，本次入口都收起，避免玩家站在门中每刻重复发送错误提示或传送。
     * @param portal 检测到玩家进入的入口
     * @param player 服务端重新查到的本人玩家
     */
    public static void enterPortal(MineralTeleportPortalEntity portal, ServerPlayer player) {
        if (PORTALS.get(player.getUUID()) != portal || !player.getUUID().equals(portal.ownerId())
                || portal.level() != player.level() || !portal.isReady()) return;
        // 在传送和扣款前关闭入口，避免重入或同一刻重复消耗全局 ME。
        portal.beginClosing();
        if (!canTravel(player)) {
            message(player, "unavailable");
            return;
        }
        TeleportWaypoint waypoint = TeleportWaypointSavedData.get(player).waypoints(player).stream()
                .filter(entry -> entry.id().equals(portal.waypointId())).findFirst().orElse(null);
        if (waypoint == null) {
            message(player, "portal_missing");
            return;
        }
        // 服务端再次限制维度，旧入口或伪造的客户端请求都不能绕过列表过滤。
        if (!waypoint.dimension().equals(player.level().dimension().location())) {
            message(player, "wrong_dimension");
            return;
        }
        ServerLevel target = player.serverLevel();
        if (!isSafeDestination(target, player, waypoint.position())) {
            message(player, "unsafe");
            return;
        }
        // 以进入瞬间的精确脚底位置计费；全局账户统一扣款，创造模式也遵守相同费用。
        long cost = waypoint.energyCost(player.position());
        OreConversionSavedData energy = OreConversionSavedData.get(player);
        if (!energy.debit(player, cost)) {
            player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter.energy_missing",
                    cost, energy.account(player).balance()), true);
            OreConversionNetwork.sendState(player, -1);
            return;
        }
        boolean arrived = false;
        try {
            // 此重载会添加目标区块票据；实际坐标到达后才认定本次付款已完成。
            player.teleportTo(target, waypoint.position().x, waypoint.position().y, waypoint.position().z,
                    Set.of(), waypoint.yaw(), waypoint.pitch());
            arrived = player.serverLevel() == target && player.position().distanceToSqr(waypoint.position()) <= 0.000001;
        } finally {
            // 扣款与传送在同一服务端主线程完成；失败或抛出异常均退回费用，并刷新客户端余额。
            if (!arrived) energy.credit(player, cost);
            OreConversionNetwork.sendState(player, -1);
        }
        if (!arrived) {
            message(player, "blocked");
            return;
        }
        // 实际到达后才清除旧速度和摔落值，传送被拒绝时不改变玩家的运动状态。
        player.stopFallFlying();
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.fallDistance = 0;
        player.closeContainer();
        target.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.8F, 1.0F);
        player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter.teleported", waypoint.name(), cost), true);
    }

    /** @param player 待传送玩家 @return 存活、未乘坐或载客且不处于睡眠或旁观状态时允许传送 */
    private static boolean canTravel(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isSleeping() && !player.isPassenger()
                && !player.isVehicle() && !SoulSeparation.isActive(player);
    }

    /**
     * 加载有限数量的目标区块后检查站立空间、支撑、液体和危险方块。
     * 沿用权杖原来的落点规则，不自动挪动坐标；校验发生在真正进入门时。
     * @param level 目的世界
     * @param player 提供站立体积和实体碰撞规则的玩家
     * @param position 保存的精确脚底位置
     * @return 世界边界内存在安全站立空间时为 true
     */
    private static boolean isSafeDestination(ServerLevel level, ServerPlayer player, Vec3 position) {
        AABB bounds = player.getDimensions(Pose.STANDING).makeBoundingBox(position);
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(bounds)) return false;
        for (int x = Mth.floor(bounds.minX) >> 4; x <= Mth.floor(bounds.maxX) >> 4; x++) {
            for (int z = Mth.floor(bounds.minZ) >> 4; z <= Mth.floor(bounds.maxZ) >> 4; z++) level.getChunk(x, z);
        }
        if (!level.noCollision(player, bounds) || level.containsAnyLiquid(bounds)
                || !level.getBlockCollisions(player, bounds.move(0, -0.05, 0)).iterator().hasNext()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(bounds.minX), Mth.floor(bounds.minY - 0.05),
                Mth.floor(bounds.minZ), Mth.floor(bounds.maxX), Mth.floor(bounds.maxY), Mth.floor(bounds.maxZ))) {
            var state = level.getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }

    /** @param player 提示接收者 @param key 本地化键后缀，错误只发给进入门的本人 */
    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("message.ore_craft.teleport_scepter." + key), true);
    }

    /** @param portal 已关闭、卸载或移除的入口；按引用删除，旧门不得移除新门的映射 */
    public static void forget(MineralTeleportPortalEntity portal) {
        PORTALS.remove(portal.ownerId(), portal);
    }

    /** @param event 实体离开世界事件，覆盖区块卸载直接 setRemoved 而不经过 remove 的路径 */
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof MineralTeleportPortalEntity portal) {
            forget(portal);
        }
    }

    /** @param event 服务器停止事件，清除实体引用以隔离单人游戏中的不同世界会话 */
    public static void onServerStopped(ServerStoppedEvent event) { PORTALS.clear(); }
}
