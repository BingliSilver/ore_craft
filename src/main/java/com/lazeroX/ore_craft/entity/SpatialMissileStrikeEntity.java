package com.lazeroX.ore_craft.entity;

import com.lazeroX.ore_craft.event.SpatialMissileStrikes;
import com.lazeroX.ore_craft.item.SpatialLocatorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 一枚空间导弹的完整临时生命周期：跟随目标锁定五秒、扣弹投送、制导追踪、命中消散。
 * 服务端独立决定目标与伤害，客户端仅按同步阶段渲染光柱和导弹；不保存到区块存档。
 * 空间弹体不参与方块碰撞，也不调用爆炸 API，因此屋顶不会阻断投送且地形不会被破坏。
 */
public final class SpatialMissileStrikeEntity extends Entity {
    /** 阶段编号同步给所有观察者；命中阶段仅用于播放短暂的无伤害冲击环。 */
    public static final int LOCKING = 0;
    public static final int FLYING = 1;
    public static final int IMPACT = 2;
    /** 从目标上方 48 格投送；追踪最多五秒，命中动画持续半秒。 */
    public static final double DROP_HEIGHT = 48.0;
    private static final int MAX_FLIGHT_TICKS = 100;
    private static final int IMPACT_TICKS = 10;
    /** 弹速每刻 1.8 格；每刻更新追踪方向，用线段检测避免高速穿过小型怪物。 */
    private static final double SPEED = 1.8;
    /** 阶段与该阶段开始的世界时间，客户端晚加入追踪时仍可得到正确倒计时。 */
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(
            SpatialMissileStrikeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> PHASE_STARTED = SynchedEntityData.defineId(
            SpatialMissileStrikeEntity.class, EntityDataSerializers.LONG);
    /** 定位环根据目标体型调整；不需要把目标 UUID 或玩家信息广播到客户端。 */
    private static final EntityDataAccessor<Float> RING_RADIUS = SynchedEntityData.defineId(
            SpatialMissileStrikeEntity.class, EntityDataSerializers.FLOAT);
    /** 红色粒子只在客户端产生，避免向每位玩家逐刻发送每个尾焰粒子的数据包。 */
    private static final DustParticleOptions RED_DUST = new DustParticleOptions(new Vector3f(1, 0.04F, 0.02F), 1.15F);
    /** 服务端使用 UUID 查找当前已加载实体，不持有跨世界生物引用，也不会为追踪加载区块。 */
    private UUID ownerId;
    private UUID targetId;
    /** 锁定时复制的回收开关，发射计费与击杀结算共用，不受之后换手或修改设置影响。 */
    private int recoveryOptions;

    /** @param option 待检查的回收功能位 @return 本枚导弹是否已选择该功能 */
    public boolean hasRecoveryOption(int option) { return (recoveryOptions & option) != 0; }

    /** @param type 已注册类型 @param level 所在世界；弹体无重力、无物理碰撞、没有实体阴影 */
    public SpatialMissileStrikeEntity(EntityType<? extends SpatialMissileStrikeEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /**
     * 在生成包发出前设定归属、目标与计时，初始位置放在目标脚部供锁定环使用。
     * @param owner 发起打击的玩家
     * @param target 本次固定目标，不会在飞行中改打其他生物
     * @param options 发起定位时选定的回收开关
     */
    public void initialize(ServerPlayer owner, LivingEntity target, int options) {
        ownerId = owner.getUUID();
        targetId = target.getUUID();
        recoveryOptions = options;
        setPos(target.position());
        entityData.set(RING_RADIUS, Math.max(0.7F, target.getBbWidth() * 0.75F));
        entityData.set(PHASE_STARTED, level().getGameTime());
    }

    /** 定义客户端所需的最少状态；未收到有效开始时间时不显示锁定动画。 */
    @Override
    protected void defineSynchedData() {
        entityData.define(PHASE, LOCKING);
        entityData.define(PHASE_STARTED, -1L);
        entityData.define(RING_RADIUS, 0.7F);
    }

    /**
     * 主线程推进阶段；每刻重新检查玩家、目标和已加载区块，未发射时任何取消均不扣弹。
     * 命中动画与伤害处理分开，确保持续十刻的冲击环不会反复造成伤害。
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            spawnTrail();
            return;
        }
        if (phase() == IMPACT) {
            if (phaseAge(0) >= IMPACT_TICKS) discard();
            return;
        }
        ServerLevel server = (ServerLevel) level();
        ServerPlayer owner = ownerId == null ? null : server.getServer().getPlayerList().getPlayer(ownerId);
        // ServerLevel.getEntity(UUID) 只读已加载实体表；目标死亡、卸载或跨维度时不尝试追加载。
        Entity found = targetId == null ? null : server.getEntity(targetId);
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.level() != server
                || !(found instanceof LivingEntity target) || !SpatialMissileStrikes.eligible(owner, target)
                || !server.hasChunkAt(target.blockPosition())) {
            discard();
            return;
        }
        if (phase() == LOCKING) {
            // 锁定标记持续跟随怪物，目标移动不会把光柱留在旧坐标。
            setPos(target.position());
            if (phaseAge(0) >= SpatialMissileStrikes.LOCK_TICKS) launch(owner, target);
        } else {
            fly(owner, target);
        }
    }

    /**
     * 五秒锁定结束后尝试扣弹，并在同一已加载区块的目标上空创建投送起点。
     * @param owner 弹药所有者
     * @param target 此刻仍可攻击的原目标
     */
    private void launch(ServerPlayer owner, LivingEntity target) {
        if (!SpatialMissileStrikes.consumeMissile(owner, SpatialLocatorItem.surcharge(recoveryOptions))) {
            discard();
            return;
        }
        // 高空目标也必须从其上方落下；不读取高度图或天空方块，因此不会强行加载区块。
        setPos(target.getX(), target.getY() + DROP_HEIGHT, target.getZ());
        setXRot(90);
        setYRot(0);
        setDeltaMovement(0, -SPEED, 0);
        changePhase(FLYING);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.25F, 0.65F);
    }

    /**
     * 朝原目标身体中心制导；只检测目标包围盒，不对沿途实体或方块产生碰撞伤害。
     * @param owner 伤害归属玩家
     * @param target 当前仍有效的原目标
     */
    private void fly(ServerPlayer owner, LivingEntity target) {
        Vec3 aim = target.getBoundingBox().getCenter();
        Vec3 offset = aim.subtract(position());
        if (phaseAge(0) >= MAX_FLIGHT_TICKS || offset.lengthSqr() > 128 * 128) {
            // 极远瞬移或长时间追不上时只销毁弹体；已经发射的弹药不返还。
            discard();
            return;
        }
        Vec3 movement = offset.normalize().scale(Math.min(SPEED, offset.length()));
        Vec3 next = position().add(movement);
        if (!level().hasChunkAt(BlockPos.containing(next))) {
            discard();
            return;
        }
        AABB hitBox = target.getBoundingBox().inflate(0.25);
        if (hitBox.contains(position()) || hitBox.clip(position(), next).isPresent() || offset.lengthSqr() < 0.25) {
            // 先进入视觉阶段，再执行唯一一次单体伤害；绝不调用爆炸或范围实体遍历。
            setPos(aim);
            setDeltaMovement(Vec3.ZERO);
            changePhase(IMPACT);
            target.hurt(damageSources().thrown(this, owner), SpatialMissileStrikes.DAMAGE);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE,
                    SoundSource.PLAYERS, 0.35F, 1.65F);
            return;
        }
        setDeltaMovement(movement);
        // 同步朝向给客户端，物品模型的弹头始终指向当前制导方向。
        setYRot((float) (Mth.atan2(-movement.x, movement.z) * Mth.RAD_TO_DEG));
        setXRot((float) (Mth.atan2(-movement.y, movement.horizontalDistance()) * Mth.RAD_TO_DEG));
        setPos(next);
    }

    /** @param nextPhase 新阶段；每次切换同步独立起点，使客户端动画无需猜测实体出生时间 */
    private void changePhase(int nextPhase) {
        entityData.set(PHASE, nextPhase);
        entityData.set(PHASE_STARTED, level().getGameTime());
    }

    /** @return 当前阶段编号，供客户端选择光柱、导弹或冲击环 */
    public int phase() { return entityData.get(PHASE); }

    /** @param partialTick 帧插值 @return 当前阶段经过的游戏刻，未初始化时为零 */
    public float phaseAge(float partialTick) {
        long start = entityData.get(PHASE_STARTED);
        return start < 0 ? 0 : Math.max(0, level().getGameTime() - start + partialTick);
    }

    /** @return 目标定位环半径，单位为格 */
    public float ringRadius() { return entityData.get(RING_RADIUS); }

    /** 客户端每刻在弹体后方生成两颗红色尾迹粒子，数量不随服务器网络包倍增。 */
    private void spawnTrail() {
        if (phase() != FLYING) return;
        Vec3 backward = getLookAngle().scale(-0.65);
        for (int index = 0; index < 2; index++) {
            double spread = 0.1;
            level().addParticle(RED_DUST, getX() + backward.x + (random.nextDouble() - 0.5) * spread,
                    getY() + backward.y, getZ() + backward.z + (random.nextDouble() - 0.5) * spread, 0, 0, 0);
        }
    }

    /** @param tag 临时打击不写入存档；noSave 类型约束同时阻止正常序列化 */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    /** @param tag 不恢复未完成打击；无归属实体将在首个服务端刻删除 */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    /** @return Forge 生成包；阶段、计时和后续移动由标准实体同步完成 */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
