package com.lazeroX.ore_craft.entity;

import com.lazeroX.ore_craft.event.MineralTeleportation;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 权杖生成的临时入口：固定位置、无实体阻挡，成形后仅允许创建者进入一次。
 * 服务端决定生命周期和触发，客户端只按同步时间绘制动画；入口不保存进区块存档。
 */
public final class MineralTeleportPortalEntity extends Entity {
    /** 外框的世界尺寸，单位为格；实体追踪范围与绘制包围盒覆盖整扇门。 */
    public static final float WIDTH = 2.2F;
    public static final float HEIGHT = 2.8F;
    /** 20 刻展开、最长 600 刻存活、12 刻收起，均使用游戏时间而非墙上时间。 */
    public static final int OPENING_TICKS = 20;
    public static final int LIFETIME_TICKS = 600;
    public static final int CLOSING_TICKS = 12;
    /** 入口平面两侧各 0.15 格有效厚度，不使用宽实体包围盒直接触发传送。 */
    private static final double HALF_DEPTH = 0.15;
    /** 开始和关闭的服务端游戏时间；负的关闭时间表示尚未进入收起阶段。 */
    private static final EntityDataAccessor<Long> OPENED_AT = SynchedEntityData.defineId(
            MineralTeleportPortalEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> CLOSED_AT = SynchedEntityData.defineId(
            MineralTeleportPortalEntity.class, EntityDataSerializers.LONG);
    /** 与权杖图标一致的青绿色边缘粒子，不包含决定传送的逻辑。 */
    private static final DustParticleOptions ENERGY_DUST = new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.75F), 1.0F);
    /** 仅服务端保存所有者和目的标记编号，不向旁观入口的其他玩家发送坐标记录。 */
    @Nullable
    private UUID ownerId;
    @Nullable
    private UUID waypointId;
    /** 上一刻的玩家身体中心，用于检测疾跑穿过薄入口的连续移动。 */
    @Nullable
    private Vec3 previousCenter;
    /** 必须先观察到玩家在入口外，防止出生重叠或原地生成导致自动传送。 */
    private boolean observedOutside;

    /** @param type 已注册的入口类型 @param level 所在世界；不启用物理移动、重力或碰撞阻挡 */
    public MineralTeleportPortalEntity(EntityType<? extends MineralTeleportPortalEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** @param player 入口所有者 @param waypointId 本人坐标编号 @param position 水平前方的门脚位置 */
    public void initialize(ServerPlayer player, UUID waypointId, Vec3 position) {
        ownerId = player.getUUID();
        this.waypointId = waypointId;
        moveTo(position.x, position.y, position.z, player.getYRot(), 0);
        entityData.set(OPENED_AT, level().getGameTime());
        previousCenter = player.getBoundingBox().getCenter();
    }

    /** 注册同步时间；新进入追踪范围的客户端也能显示正确动画进度，而不是重新播放展开。 */
    @Override
    protected void defineSynchedData() {
        entityData.define(OPENED_AT, -1L);
        entityData.define(CLOSED_AT, -1L);
    }

    /**
     * 服务端处理超时、所有者离线与进入；客户端只追加少量能量粒子。
     * 成形期间不触发传送，收起阶段不可再次使用，不需要玩家继续手持权杖。
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            spawnParticles();
            return;
        }
        if (isClosing()) {
            if (level().getGameTime() - entityData.get(CLOSED_AT) >= CLOSING_TICKS) discard();
            return;
        }
        if (age(0) >= LIFETIME_TICKS - CLOSING_TICKS) {
            beginClosing();
            return;
        }
        ServerPlayer player = ownerId == null ? null : ((ServerLevel) level()).getServer().getPlayerList().getPlayer(ownerId);
        if (player == null || !player.isAlive() || player.level() != level() || waypointId == null) {
            beginClosing();
            return;
        }
        Vec3 center = player.getBoundingBox().getCenter();
        double radius = player.getBbWidth() / 2.0;
        double halfHeight = player.getBbHeight() / 2.0;
        boolean inside = touchesPassage(center, radius, halfHeight);
        if (!inside) observedOutside = true;
        if (isReady() && observedOutside && (inside || crossedPassage(previousCenter, center, radius, halfHeight))) {
            MineralTeleportation.enterPortal(this, player);
        }
        // 即使门仍在展开，也逐刻更新采样，不能把成形前的整段移动误判为刚刚穿门。
        previousCenter = center;
    }

    /** @return 所有者编号；客户端无须获知归属，未初始化实体为 null */
    @Nullable
    public UUID ownerId() { return ownerId; }

    /** @return 目的标记编号，仅服务端进入结算使用 */
    @Nullable
    public UUID waypointId() { return waypointId; }

    /** @param partialTick 绘制插值 @return 自创建起经过的非负游戏刻 */
    public float age(float partialTick) {
        long openedAt = entityData.get(OPENED_AT);
        // 客户端生成包与首份实体数据之间先保持不可见，避免首帧误画成完整展开的门。
        return openedAt < 0 ? 0 : Math.max(0, level().getGameTime() - openedAt + partialTick);
    }

    /** @return 完成展开且尚未关闭时可进入 */
    public boolean isReady() { return age(0) >= OPENING_TICKS && !isClosing() && !isRemoved(); }

    /** @return 是否已经开始收起动画 */
    public boolean isClosing() { return entityData.get(CLOSED_AT) >= 0; }

    /**
     * 立即禁用入口并同步收起起点，保留十二刻供客户端绘制缩小消散。
     * 重复调用不会重置计时；旧入口被替换时只删除属于自己的管理器引用。
     */
    public void beginClosing() {
        if (level().isClientSide() || isClosing()) return;
        entityData.set(CLOSED_AT, level().getGameTime());
        MineralTeleportation.forget(this);
        level().playSound(null, getX(), getY() + 1, getZ(), SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.5F, 1.6F);
    }

    /** @param partialTick 插值比例 @return 0 至 1 的平滑开合比例，渲染和粒子使用同一轮廓 */
    public float openness(float partialTick) {
        float progress = Mth.clamp(age(partialTick) / OPENING_TICKS, 0, 1);
        if (isClosing()) {
            float remaining = 1 - (level().getGameTime() - entityData.get(CLOSED_AT) + partialTick) / CLOSING_TICKS;
            progress = Math.min(progress, Mth.clamp(remaining, 0, 1));
        }
        return progress * progress * (3 - 2 * progress);
    }

    /** @param position 门脚位置 @param yaw 水平朝向 @return 包含整扇门的薄包围盒，供生成空间检查 */
    public static AABB portalBounds(Vec3 position, float yaw) {
        Vec3 normal = Vec3.directionFromRotation(0, yaw);
        double x = Math.abs(normal.z) * WIDTH / 2 + Math.abs(normal.x) * HALF_DEPTH;
        double z = Math.abs(normal.x) * WIDTH / 2 + Math.abs(normal.z) * HALF_DEPTH;
        return new AABB(position.x - x, position.y + 0.02, position.z - z,
                position.x + x, position.y + HEIGHT, position.z + z);
    }

    /** @return 入口平面的水平法线，与玩家开启时的朝向一致 */
    private Vec3 normal() { return Vec3.directionFromRotation(0, getYRot()); }

    /** @param center 玩家身体中心 @param radius 玩家半宽 @param halfHeight 玩家半高 @return 身体是否触碰门洞 */
    private boolean touchesPassage(Vec3 center, double radius, double halfHeight) {
        Vec3 direction = normal();
        Vec3 delta = center.subtract(position().add(0, HEIGHT / 2, 0));
        double depth = Math.abs(delta.dot(direction));
        double projectedRadius = radius * (Math.abs(direction.x) + Math.abs(direction.z));
        return depth <= HALF_DEPTH + projectedRadius && insideOpening(delta, direction, radius, halfHeight);
    }

    /**
     * 检查相邻两刻间跨越门平面的线段，避免薄门被疾跑跳过。
     * 超过四格的单刻瞬移不视为走入，防止其他模组的远距离移动误触沿途入口。
     * @param previous 上一刻身体中心，可为空
     * @param current 当前身体中心
     * @param radius 玩家半宽
     * @param halfHeight 玩家半高
     * @return 连续移动穿过有效门洞时为 true
     */
    private boolean crossedPassage(@Nullable Vec3 previous, Vec3 current, double radius, double halfHeight) {
        if (previous == null || previous.distanceToSqr(current) > 16) return false;
        Vec3 direction = normal();
        Vec3 center = position().add(0, HEIGHT / 2, 0);
        double before = previous.subtract(center).dot(direction);
        double after = current.subtract(center).dot(direction);
        if (before * after > 0 || Math.abs(before - after) < 0.000001) return false;
        Vec3 crossing = previous.lerp(current, before / (before - after));
        return insideOpening(crossing.subtract(center), direction, radius, halfHeight);
    }

    /** @param delta 相对门中心的位置 @param direction 门法线 @param radius 玩家半宽
     * @param halfHeight 玩家半高 @return 玩家体积是否触及椭圆门洞，门框外围不会触发 */
    private static boolean insideOpening(Vec3 delta, Vec3 direction, double radius, double halfHeight) {
        double horizontal = delta.x * direction.z - delta.z * direction.x;
        double x = Math.max(0, Math.abs(horizontal) - radius) / (WIDTH * 0.39);
        double y = Math.max(0, Math.abs(delta.y) - halfHeight) / (HEIGHT * 0.39);
        return x * x + y * y <= 1;
    }

    /** 客户端沿正在展开或收缩的门框添加青色粒子和少量白色闪光，所有玩家看到同一开合进度。 */
    private void spawnParticles() {
        float open = openness(0);
        if (open <= 0 || (isReady() && tickCount % 3 != 0)) return;
        Vec3 direction = normal();
        for (int index = 0; index < 2; index++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double horizontal = Math.cos(angle) * WIDTH * 0.47 * open;
            double vertical = Math.sin(angle) * HEIGHT * 0.47 * (0.35 + 0.65 * open);
            double x = getX() + direction.z * horizontal;
            double y = getY() + HEIGHT / 2 + vertical;
            double z = getZ() - direction.x * horizontal;
            level().addParticle(ENERGY_DUST, x, y, z, 0, 0.01, 0);
            if (tickCount % 8 == 0) level().addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.01, 0);
        }
    }

    /** @param reason 移除原因；区块卸载、超时和正常销毁都释放管理器中的实体引用 */
    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) MineralTeleportation.forget(this);
        super.remove(reason);
    }

    /** @param tag 临时入口不写入区块数据；实体类型同时声明 noSave */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    /** @param tag 入口不从磁盘恢复；异常加载的无归属实体会在下一服务端刻自行关闭 */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    /** @return Forge 实体生成包，包含固定位置、朝向及之后同步的开合时间 */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
}
