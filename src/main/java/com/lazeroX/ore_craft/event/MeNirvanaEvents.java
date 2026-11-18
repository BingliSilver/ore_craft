package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.value.CarriedContainerEnergy;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

/**
 * 服务端涅槃流程：最终伤害致命时先检查余额，完整付款后取消伤害、净化有害状态并重构生命。
 * 冷却保存到玩家持久化数据，跨登录、维度和死亡保留，换核心不能绕过。
 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID)
public final class MeNirvanaEvents {
    /** 全部核心共享的下次可用世界刻号，使用主世界时间避免维度时间差。 */
    private static final String READY_KEY = "OreCraftNirvanaReady";
    /** 护盾粒子截止刻号；只提供视觉反馈，实际保护由吸收和抗性效果负责。 */
    private static final String SHIELD_KEY = "OreCraftNirvanaShield";
    /** 五分钟冷却，按服务器游戏刻计时，暂停游戏时不推进。 */
    private static final int COOLDOWN = 20 * 60 * 5;
    /** 球面护盾呈蓝色，与紫色核心爆发区分。 */
    private static final DustParticleOptions SHIELD = new DustParticleOptions(new Vector3f(0.1F, 0.55F, 1F), 1.2F);

    /** 静态事件订阅类不需要实例。 */
    private MeNirvanaEvents() {}

    /**
     * 在护甲、抗性与已有吸收结算之后判断最终生命伤害；非致命命中不收取能量。
     * 成功触发时，清除有害状态，再按净化后的最大生命重构并施加增益。
     * @param event Forge 最终伤害事件，优先让其他常规伤害处理器完成计算
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()
                || player.getHealth() <= 0 || !(event.getAmount() >= player.getHealth())) return;
        long now = player.server.overworld().getGameTime();
        if (data(player).getLong(READY_KEY) > now) return;
        // 只识别随身主背包、快捷栏及副手，不读取箱子、末影箱或嵌套容器。
        boolean carrying = player.getInventory().items.stream().anyMatch(stack -> stack.is(ModItems.ME_NIRVANA_CORE.get()))
                || player.getOffhandItem().is(ModItems.ME_NIRVANA_CORE.get());
        if (!carrying) return;

        float maximum = player.getMaxHealth();
        if (!Float.isFinite(maximum) || maximum <= 0) return;
        // 20 点为原版最大生命；每额外 2 点一档，零头向上取整，不计临时吸收生命。
        double tiers = Math.ceil(Math.max(0D, maximum - 20D) / 2D);
        if (tiers > (Long.MAX_VALUE - 50_000L) / 5_000L) return;
        long cost = 50_000L + (long) tiers * 5_000L;
        if (!CarriedContainerEnergy.pay(player, cost)) return;

        // 先写共享冷却，再取消伤害；同一刻后续攻击不能再次购买保命。
        data(player).putLong(READY_KEY, now + COOLDOWN);
        data(player).putLong(SHIELD_KEY, now + 100);
        event.setCanceled(true);

        // 先将有害效果整理成独立列表，避免遍历玩家正在使用的效果集合时同时修改它。
        for (MobEffect effect : player.getActiveEffects().stream()
                .map(MobEffectInstance::getEffect)
                .filter(activeEffect -> activeEffect.getCategory() == MobEffectCategory.HARMFUL)
                .toList()) {
            // 走原版移除流程，让效果附加的属性和客户端状态一起更新。
            player.removeEffect(effect);
        }
        // 模组的有害效果可能影响最大生命值，因此恢复量以净化后的上限计算。
        player.setHealth(player.getMaxHealth() * 0.5F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 3));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 1));
        player.getCooldowns().addCooldown(ModItems.ME_NIRVANA_CORE.get(), COOLDOWN);
        // 服务端广播爆发和声音，附近玩家也能看到现实重构发生的位置。
        player.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1,
                player.getZ(), 100, 0.6, 0.9, 0.6, 0.15);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1F, 0.8F);
        player.displayClientMessage(Component.translatable("message.ore_craft.nirvana.triggered", cost), true);
    }

    /** @param player 数据所属玩家 @return 死亡重生时需要保留的专用数据容器 */
    private static CompoundTag data(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    /** @param event 重生或离开末地时复制共享冷却，不将其他系统的数据覆盖到新玩家上 */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        data(event.getEntity()).putLong(READY_KEY, data(event.getOriginal()).getLong(READY_KEY));
    }

    /** @param event 登录后恢复客户端冷却遮罩；真正的触发资格始终由持久化时间判定 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncCooldown(player);
    }

    /** @param event 重生后同步剩余冷却，避免客户端显示可用而服务端拒绝触发 */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncCooldown(player);
    }

    /** @param player 接收客户端物品冷却遮罩的玩家；过期时不新增冷却 */
    private static void syncCooldown(ServerPlayer player) {
        long remaining = data(player).getLong(READY_KEY) - player.server.overworld().getGameTime();
        if (remaining > 0) player.getCooldowns().addCooldown(ModItems.ME_NIRVANA_CORE.get(), (int) Math.min(COOLDOWN, remaining));
    }

    /** @param event 每四刻在玩家当前位置绘制蓝色球面，护盾随玩家移动且五秒后自动停止 */
    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || player.tickCount % 4 != 0 || !player.isAlive()
                || data(player).getLong(SHIELD_KEY) <= player.server.overworld().getGameTime()) return;
        // 三条纬线组成球面轮廓；每次 48 个粒子，控制多人同时触发时的网络与渲染负担。
        for (int latitude = -1; latitude <= 1; latitude++) {
            double y = latitude * 0.7;
            double radius = Math.sqrt(1.15 * 1.15 - y * y);
            for (int point = 0; point < 16; point++) {
                double angle = point * Math.PI / 8 + player.tickCount * 0.08;
                player.serverLevel().sendParticles(SHIELD, player.getX() + Math.cos(angle) * radius,
                        player.getY() + 1 + y, player.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
            }
        }
    }
}
