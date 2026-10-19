package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.SoulBodyEntity;
import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.network.SoulSeparationNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的灵魂出窍会话管理器：一次付费、600 刻计时、身体锚点和完整回归。
 * 游戏模式暂时使用旁观者以复用原版穿墙飞行，交互则由双端额外限制。
 * 所有会话仅由服务器主线程访问；玩家持久数据保留恢复点，供异常重启后的登录恢复。
 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID)
public final class SoulSeparation {
    /** 每次出窍的一次性费用，单位 ME，创造模式也需要供能容器。 */
    public static final long COST = 4_096L;
    /** 持续 30 秒，即正常 20 TPS 下的 600 个游戏刻。 */
    public static final int DURATION_TICKS = 600;
    /** 玩家持久数据中的恢复记录；正常结束后立即删除。 */
    private static final String RECOVERY_KEY = "OreCraftSoulRecovery";
    /** 正在出窍的玩家与身体，每个玩家最多一份会话。 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** 保持身体所在区块进行实体更新，灵魂飞远时身体依然会受伤。 */
    private static final TicketType<UUID> BODY_TICKET = TicketType.create("ore_craft_soul_body",
            Comparator.comparing(UUID::toString));

    /** 静态事件管理器不创建实例。 */
    private SoulSeparation() {}

    /** @param player 待检查玩家 @return 服务端是否持有该玩家的有效出窍会话 */
    public static boolean isActive(Player player) { return SESSIONS.containsKey(player.getUUID()); }

    /**
     * 校验状态和能源后创建身体；任何生成或模式切换失败都会退还本次费用。
     * @param player 使用魂离之眼的服务端玩家
     */
    public static void start(ServerPlayer player) {
        if (isActive(player)) return;
        if (!player.isAlive() || player.isSpectator() || player.isSleeping() || player.isPassenger()
                || player.getCooldowns().isOnCooldown(ModItems.SOUL_SEPARATION_EYE.get())) {
            message(player, "unavailable");
            return;
        }
        Payment payment = withdraw(player);
        if (payment == null) {
            player.displayClientMessage(Component.translatable("message.ore_craft.soul_eye.energy_missing", COST), true);
            return;
        }
        SoulBodyEntity body = ModEntities.SOUL_BODY.get().create(player.serverLevel());
        if (body == null) {
            payment.refund();
            message(player, "unavailable");
            return;
        }
        player.stopUsingItem();
        player.closeContainer();
        body.initialize(player);
        Session session = new Session(player, body);
        if (!player.serverLevel().addFreshEntity(body)) {
            payment.refund();
            message(player, "unavailable");
            return;
        }

        // 模式改变前保存原位置与原模式，异常退出后也能在下一次登录时回到身体。
        player.getPersistentData().put(RECOVERY_KEY, session.recoveryTag());
        if (!player.setGameMode(GameType.SPECTATOR) || !player.isSpectator()) {
            body.discard();
            restoreMode(player, session.mode);
            player.getPersistentData().remove(RECOVERY_KEY);
            payment.refund();
            message(player, "unavailable");
            return;
        }
        SESSIONS.put(player.getUUID(), session);
        session.level.getChunkSource().addRegionTicket(BODY_TICKET, session.chunk, 2, player.getUUID());
        // 立即让原本追击玩家的怪物转向身体，避免模式改变使战斗突然中断。
        attractMonsters(session);
        payment.finish();
        SoulSeparationNetwork.sync(player, true, DURATION_TICKS);
        message(player, "started");
    }

    /**
     * 客户端右键请求提前回归；服务端根据现有会话定位身体，不接收客户端坐标。
     * @param player 请求回归的连接所属玩家
     */
    public static void returnToBody(ServerPlayer player) {
        if (isActive(player)) finish(player, true);
    }

    /**
     * 身体已通过原版致命伤和图腾检查，将死亡转移给真实玩家并在身体位置掉落背包。
     * @param body 被击杀的身体
     * @param source 致命伤害，保留死亡消息中的来源
     */
    public static void bodyDied(SoulBodyEntity body, DamageSource source) {
        Session session = SESSIONS.get(body.profile().getId());
        if (session == null || session.body != body) {
            body.discard();
            return;
        }
        ServerPlayer player = session.player;
        finish(player, false);
        // 身体已经处理过不死图腾；直接走真实玩家死亡流程，不能再额外救活一次。
        player.getCombatTracker().recordDamage(source, player.getHealth());
        if (source.getEntity() instanceof Player attacker) player.setLastHurtByPlayer(attacker);
        player.setHealth(0.0F);
        player.die(source);
    }

    /**
     * 结束会话并恢复控制权；先删除会话，避免恢复模式和传送再次触发限制事件。
     * @param player 会话所属玩家
     * @param alive 是否按存活回归同步身体生命，外部死亡流程传 false
     */
    private static void finish(ServerPlayer player, boolean alive) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return;
        player.removeAllEffects();
        for (MobEffectInstance effect : session.body.getActiveEffects()) player.addEffect(new MobEffectInstance(effect));
        // 药水可能改变生命上限或吸收值，必须在效果恢复后回填生命，避免额外损失生命。
        syncBodyState(session, alive);
        player.setAirSupply(session.body.getAirSupply());
        player.setRemainingFireTicks(session.body.getRemainingFireTicks());
        player.setCamera(player);
        player.teleportTo(session.level, session.origin.x, session.origin.y, session.origin.z, session.yaw, session.pitch);
        restoreMode(player, session.mode);
        player.noPhysics = false;
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.getAbilities().flying = session.flying && player.getAbilities().mayfly;
        player.onUpdateAbilities();
        session.body.discard();
        session.level.getChunkSource().removeRegionTicket(BODY_TICKET, session.chunk, 2, player.getUUID());
        player.getPersistentData().remove(RECOVERY_KEY);
        // 右键回归后短暂冷却，按住右键不会在恢复控制的下一帧重新付费出窍。
        player.getCooldowns().addCooldown(ModItems.SOUL_SEPARATION_EYE.get(), 20);
        player.inventoryMenu.broadcastChanges();
        SoulSeparationNetwork.sync(player, false, 0);
        if (alive) message(player, "returned");
    }

    /**
     * 回填真实装备槽与生命；身体装备仅为副本，磨损或图腾消耗必须反映到真实背包。
     * @param session 有效会话
     * @param health 是否同步生命，死亡处理中不覆盖玩家的零生命
     */
    private static void syncBodyState(Session session, boolean health) {
        ServerPlayer player = session.player;
        Inventory inventory = player.getInventory();
        // 主手始终写回启动时的快捷栏槽，防止灵魂滚轮改变选中槽后复制或覆盖其他物品。
        inventory.selected = session.selectedSlot;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = session.body.getItemBySlot(slot);
            if (!ItemStack.matches(player.getItemBySlot(slot), stack)) player.setItemSlot(slot, stack.copy());
        }
        if (health && session.body.isAlive()) player.setHealth(session.body.getHealth());
        player.setAbsorptionAmount(session.body.getAbsorptionAmount());
        if (session.body.getLastHurtByMob() != null) player.setLastHurtByMob(session.body.getLastHurtByMob());
        inventory.setChanged();
    }

    /**
     * 逐刻维护剩余时间与身体；最后五秒的倒计时通过状态包交给客户端固定显示在物品栏上方。
     * @param event 服务端游戏刻，仅处理 END 阶段
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Session session : List.copyOf(SESSIONS.values())) {
            ServerPlayer player = session.player;
            if (!player.isAlive()) { finish(player, false); continue; }
            if (!session.body.isAlive() || session.body.isRemoved()) {
                bodyDied(session.body, player.damageSources().genericKill());
                continue;
            }
            int remaining = DURATION_TICKS - (event.getServer().getTickCount() - session.startTick);
            if (remaining <= 0) { finish(player, true); continue; }
            syncBodyState(session, true);
            // 旁观模式允许查看其他实体和箱子，本机制只允许自由飞行，因此逐刻复位这些入口。
            if (player.getCamera() != player) player.setCamera(player);
            if (player.containerMenu != player.inventoryMenu) player.closeContainer();
            player.stopUsingItem();
            if (remaining % 20 == 0) {
                SoulSeparationNetwork.sync(player, true, remaining);
                attractMonsters(session);
            }
        }
    }

    /**
     * 常见敌对怪物继续把留在附近的身体作为目标，距离与视线限制避免穿墙吸引怪物。
     * @param session 需要保持战斗风险的身体会话
     */
    private static void attractMonsters(Session session) {
        for (Mob mob : session.level.getEntitiesOfClass(Mob.class, session.body.getBoundingBox().inflate(24.0D))) {
            if (mob.getTarget() == session.player) mob.setTarget(session.body);
            else if (mob instanceof Monster && !(mob instanceof NeutralMob) && mob.getTarget() == null
                    && mob.distanceToSqr(session.body) <= Math.pow(mob.getAttributeValue(Attributes.FOLLOW_RANGE), 2)
                    && mob.hasLineOfSight(session.body)) mob.setTarget(session.body);
        }
    }

    /**
     * 服务端阻断所有物品、方块与实体交互；右键作为回归请求，左键不产生攻击。
     * @param event 玩家交互事件，非可取消的通知事件不修改
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isActive(player)) return;
        if (event.isCancelable()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
        if (event instanceof PlayerInteractEvent.RightClickItem || event instanceof PlayerInteractEvent.RightClickBlock
                || event instanceof PlayerInteractEvent.EntityInteract || event instanceof PlayerInteractEvent.EntityInteractSpecific) {
            // 普通交互包可能包含启动点击的副手包；独立的回归请求不受此短暂去重窗口影响。
            if (player.server.getTickCount() - SESSIONS.get(player.getUUID()).startTick >= 5) returnToBody(player);
        }
    }

    /** @param event 服务端攻击请求，灵魂不能攻击或借此切换观察目标 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (isActive(event.getEntity())) event.setCanceled(true);
    }

    /** @param event 出窍期间禁止跨维度，返回身体时会话已提前删除，不受此限制 */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isActive(player)) event.setCanceled(true);
    }

    /** @param event 阻止出窍期间外部切换游戏模式破坏交互限制；正常回归先移除会话 */
    @SubscribeEvent
    public static void onGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (isActive(event.getEntity())) event.setCanceled(true);
    }

    /** @param event 玩家遭命令等外部原因死亡时，先回到身体坐标再由原版处理掉落 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isActive(player)) finish(player, false);
    }

    /** @param event 正常退出时在玩家数据保存之前回归，避免遗留旁观模式或身体 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) finish(player, player.isAlive());
    }

    /** @param event 停服保存前恢复全部玩家并释放身体的临时区块票据 */
    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) finish(session.player, session.player.isAlive());
        SESSIONS.clear();
    }

    /**
     * 崩溃后登录发现恢复记录时，返回记录中的原维度和原位置，正常登录只清除客户端残留。
     * @param event 已进入服务器的玩家
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getPersistentData().contains(RECOVERY_KEY)) {
            CompoundTag tag = player.getPersistentData().getCompound(RECOVERY_KEY);
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
            ServerLevel level = dimension == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) level = player.server.overworld();
            player.teleportTo(level, tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                    tag.getFloat("Yaw"), tag.getFloat("Pitch"));
            restoreMode(player, GameType.byId(tag.getInt("Mode")));
            player.getAbilities().flying = tag.getBoolean("Flying") && player.getAbilities().mayfly;
            player.noPhysics = false;
            player.fallDistance = 0.0F;
            player.onUpdateAbilities();
            player.getPersistentData().remove(RECOVERY_KEY);
        }
        SoulSeparationNetwork.sync(player, false, 0);
    }

    /**
     * 恢复借用前的游戏模式；第三方拦截恢复事件时仍确保不会永久留下旁观者权限。
     * @param player 待恢复玩家
     * @param mode 进入出窍前的模式
     */
    private static void restoreMode(ServerPlayer player, GameType mode) {
        if (player.gameMode.getGameModeForPlayer() == mode) return;
        player.setGameMode(mode);
        if (player.gameMode.getGameModeForPlayer() != mode) {
            player.gameMode.changeGameModeForPlayer(mode);
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mode.getId()));
            player.onUpdateAbilities();
        }
    }

    /** @param player 提示接收者 @param suffix 本地化键后缀 */
    private static void message(ServerPlayer player, String suffix) {
        player.displayClientMessage(Component.translatable("message.ore_craft.soul_eye." + suffix), true);
    }

    /**
     * 预检后一次性支付固定费用：末影容器独占全局账户通道，否则按背包顺序合并普通容器。
     * @param player 付款人，需要实际携带至少一个供能容器
     * @return 可完成或回滚的支付记录，余额不足返回 null 且不扣费
     */
    private static Payment withdraw(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof EnderOreContainerItem) {
                return OreConversionSavedData.get(player).debit(player, COST)
                        ? new Payment(player, true, List.of()) : null;
            }
        }
        long remaining = COST;
        List<Charge> charges = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof OreContainerItem container)) continue;
            long amount = Math.min(remaining, container.storedMe(stack));
            if (amount > 0) charges.add(new Charge(stack, container, amount));
            remaining -= amount;
        }
        if (remaining > 0) return null;
        for (Charge charge : charges) charge.container.setStoredMe(charge.stack, charge.container.storedMe(charge.stack) - charge.amount);
        return new Payment(player, false, charges);
    }

    /**
     * 单个普通容器的扣款记录，只在启动这一次同步调用中存活。
     * @param stack 背包内原栈
     * @param container 容器类型
     * @param amount 分摊的正数 ME
     */
    private record Charge(ItemStack stack, OreContainerItem container, long amount) {}

    /**
     * 启动事务的支付凭据；生成失败退回原渠道，成功后同步背包和全局余额。
     * @param player 付款者
     * @param global 是否使用末影账户
     * @param charges 普通容器的分摊明细
     */
    private record Payment(ServerPlayer player, boolean global, List<Charge> charges) {
        /** 同步扣款结果，结束本次支付事务。 */
        private void finish() {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            if (global) OreConversionNetwork.sendState(player, -1);
        }
        /** 启动失败时完整退回原容器或账户，然后同步客户端。 */
        private void refund() {
            if (global) OreConversionSavedData.get(player).credit(player, COST);
            else for (Charge charge : charges) charge.container.setStoredMe(charge.stack,
                    charge.container.storedMe(charge.stack) + charge.amount);
            finish();
        }
    }

    /** 一次出窍的不可变锚点和原始状态，生命周期从成功启动到回归或死亡。 */
    private static final class Session {
        /** 持有会话的真实玩家与承伤身体，二者都只由服务器主线程访问。 */
        private final ServerPlayer player;
        private final SoulBodyEntity body;
        /** 原维度、身体区块和准确脚部坐标。 */
        private final ServerLevel level;
        private final ChunkPos chunk;
        private final Vec3 origin;
        /** 原有视角方向，单位度；回归不采用灵魂最后朝向。 */
        private final float yaw;
        private final float pitch;
        /** 临时借用旁观模式之前的游戏模式与飞行状态。 */
        private final GameType mode;
        private final boolean flying;
        /** 原主手快捷栏槽和服务端起始刻，防止手持装备回填错位并提供权威计时。 */
        private final int selectedSlot;
        private final int startTick;

        /** @param player 尚未切换模式的使用者 @param body 已初始化的身体 */
        private Session(ServerPlayer player, SoulBodyEntity body) {
            this.player = player;
            this.body = body;
            level = player.serverLevel();
            chunk = body.chunkPosition();
            origin = player.position();
            yaw = player.getYRot();
            pitch = player.getXRot();
            mode = player.gameMode.getGameModeForPlayer();
            flying = player.getAbilities().flying;
            selectedSlot = player.getInventory().selected;
            startTick = player.server.getTickCount();
        }

        /** @return 写入玩家存档的最小恢复记录，不序列化临时实体或背包副本 */
        private CompoundTag recoveryTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", level.dimension().location().toString());
            tag.putDouble("X", origin.x);
            tag.putDouble("Y", origin.y);
            tag.putDouble("Z", origin.z);
            tag.putFloat("Yaw", yaw);
            tag.putFloat("Pitch", pitch);
            tag.putInt("Mode", mode.getId());
            tag.putBoolean("Flying", flying);
            return tag;
        }
    }

}
