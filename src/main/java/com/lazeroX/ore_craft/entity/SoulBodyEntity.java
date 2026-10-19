package com.lazeroX.ore_craft.entity;

import com.lazeroX.ore_craft.event.SoulSeparation;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

import java.util.UUID;

/**
 * 出窍后留在原处的可受伤身体，保存皮肤身份和装备副本，按生物规则承受环境与战斗伤害。
 * 仅存在于一次出窍会话中，不写入区块存档、不掉落副本物品；真实背包始终属于玩家。
 */
public final class SoulBodyEntity extends Mob implements IEntityAdditionalSpawnData {
    /** 皮肤档案随生成包发送；客户端据此复用玩家的皮肤、披风和粗细手臂模型。 */
    private GameProfile profile = new GameProfile(new UUID(0, 0), "Soul");
    /** 玩家开启的皮肤外层位掩码，默认显示全部七层。 */
    private int skinParts = 127;

    /**
     * 创建无 AI、不可拾取物品的静止身体；初始化完成前不得加入世界。
     * @param type 已注册的身体实体类型
     * @param level 所在世界
     */
    public SoulBodyEntity(EntityType<? extends SoulBodyEntity> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    /** @return 玩家体型身体的基础属性，生命上限在创建会话时由玩家覆盖 */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D).add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    /**
     * 扣费成功后复制外观和战斗状态，确保手持容器副本已包含最新余额。
     * @param player 身体所属玩家
     */
    public void initialize(ServerPlayer player) {
        profile = player.getGameProfile();
        skinParts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (player.isModelPartShown(part)) skinParts |= part.getMask();
        }
        moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        setYHeadRot(player.getYHeadRot());
        setYBodyRot(player.yBodyRot);
        setLeftHanded(player.getMainArm() == HumanoidArm.LEFT);
        setShiftKeyDown(player.isShiftKeyDown());
        setPose(player.getPose());
        setCustomName(player.getName());
        // 拷贝基础属性和已有修饰符；后续装备/药水使用相同 UUID 更新，不重复叠加生命上限。
        getAttributes().assignValues(player.getAttributes());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setItemSlot(slot, player.getItemBySlot(slot).copy());
            setDropChance(slot, 0.0F);
        }
        // 同步初始药水和吸收生命，避免出窍把已有防御或中毒状态抹除。
        for (MobEffectInstance effect : player.getActiveEffects()) addEffect(new MobEffectInstance(effect));
        setHealth(player.getHealth());
        setAbsorptionAmount(player.getAbsorptionAmount());
        setAirSupply(player.getAirSupply());
        setRemainingFireTicks(player.getRemainingFireTicks());
    }

    /** @return 身体对应的玩家档案，服务端用于归属判断，客户端用于皮肤查找 */
    public GameProfile profile() { return profile; }

    /** @param part 皮肤外层部件 @return 出窍时是否开启该外层 */
    public boolean showsSkinPart(PlayerModelPart part) { return (skinParts & part.getMask()) != 0; }

    /**
     * 保持身体固定在离魂位置，同时继续处理火焰、溺水、药水和装备同步。
     * 位置不随碰撞或水流改变，保证返回的锚点不会被其他实体推入墙内。
     */
    @Override
    public void tick() {
        Vec3 anchor = position();
        super.tick();
        setPos(anchor.x, anchor.y, anchor.z);
        setDeltaMovement(Vec3.ZERO);
    }

    /** @param strength 击退力度 @param x 水平 X 方向 @param z 水平 Z 方向；身体锚点不接受击退 */
    @Override
    public void knockback(double strength, double x, double z) {}

    /** @return 身体不参与实体挤压推动，但仍保留可攻击的碰撞箱 */
    @Override
    public boolean isPushable() { return false; }

    /**
     * 保持出窍前姿势对应的玩家碰撞箱，潜行或游泳时不会凭空长高并卡入顶板。
     * @param pose 当前同步姿势
     * @return 与玩家站立、潜行及匍匐姿势对应的碰撞尺寸
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return switch (pose) {
            case CROUCHING -> EntityDimensions.scalable(0.6F, 1.5F);
            case SWIMMING, FALL_FLYING, SPIN_ATTACK -> EntityDimensions.scalable(0.6F, 0.6F);
            default -> super.getDimensions(pose);
        };
    }

    /**
     * 遵守玩家之间的 PVP 和队伍友伤限制，身体不成为绕过这些规则的入口。
     * @param source 伤害来源
     * @param amount 防御结算前伤害
     * @return 本次伤害是否被接受
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && source.getEntity() instanceof ServerPlayer attacker) {
            ServerPlayer owner = level().getServer().getPlayerList().getPlayer(profile.getId());
            if (owner != null && (!level().getServer().isPvpAllowed() || !owner.canHarmPlayer(attacker))) return false;
        }
        return super.hurt(source, amount);
    }

    /**
     * 生物默认不会磨损护甲，这里按玩家的四分之一伤害规则扣除装备耐久。
     * @param source 伤害来源，耐火装备不会因火焰损坏
     * @param amount 护甲减伤前伤害
     */
    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        int damage = Math.max(1, (int) (amount / 4.0F));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = getItemBySlot(slot);
            if (slot.getType() != EquipmentSlot.Type.ARMOR || !(stack.getItem() instanceof ArmorItem)) continue;
            if (source.is(DamageTypeTags.IS_FIRE) && stack.getItem().isFireResistant()) continue;
            stack.hurtAndBreak(damage, this, body -> body.broadcastBreakEvent(slot));
        }
    }

    /**
     * 身体死亡时将玩家带回并执行真实死亡流程，战利品和死亡统计仅由玩家生成一次。
     * @param source 已完成身体护甲、药水和不死图腾结算的致命来源
     */
    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) SoulSeparation.bodyDied(this, source);
    }

    /** @return Forge 附带生成数据的实体包，保证皮肤档案在首次渲染前可用 */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /** @param buffer 生成包缓冲区，按档案和皮肤外层顺序写入 */
    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeGameProfile(profile);
        buffer.writeByte(skinParts);
    }

    /** @param buffer 服务端生成包，读取顺序必须与写入一致 */
    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        profile = buffer.readGameProfile();
        skinParts = buffer.readUnsignedByte();
    }
}
