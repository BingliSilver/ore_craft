package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.entity.SpatialMissileStrikeEntity;
import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;

/**
 * 空间导弹打击的服务端入口与公共判定规则，所有方法仅在服务器主线程调用。
 * 不建立全局玩家缓存；临时打击由实体自行计时，停服和区块卸载时自然释放。
 */
public final class SpatialMissileStrikes {
    /** 以右键时玩家脚部为球心，按三维距离筛选 64 格内的已加载怪物。 */
    public static final int RADIUS = 64;
    /** 每次最多选择 64 个目标，也受背包可用导弹数量限制。 */
    public static final int MAX_TARGETS = 64;
    /** 锁定五秒，按正常游戏时间每秒二十刻计时。 */
    public static final int LOCK_TICKS = 100;
    /** 命中的基础伤害；后续仍遵循原版伤害事件、免疫和减伤规则。 */
    public static final float DAMAGE = 50.0F;

    /** 无状态工具类不需要实例。 */
    private SpatialMissileStrikes() {}

    /**
     * 在已加载实体集合中选择最近的敌对目标，创建跟随目标的红色锁定标记。
     * 不主动读取或生成区块；不在此时扣弹，给目标死亡或卸载保留无消耗取消的机会。
     * @param player 使用定位器的玩家
     */
    public static void start(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.getCooldowns().isOnCooldown(ModItems.SPATIAL_LOCATOR.get())) return;
        int available = ammunition(player);
        if (available == 0) {
            player.displayClientMessage(Component.translatable("message.ore_craft.spatial_locator.no_ammo"), true);
            return;
        }
        // getEntitiesOfClass 只查询当前已加载实体；先用包围盒粗筛，再用距离平方裁成球形。
        var targets = player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(RADIUS), target -> eligible(player, target)
                        && target.distanceToSqr(player) <= RADIUS * RADIUS);
        targets.sort(Comparator.comparingDouble(target -> target.distanceToSqr(player)));
        int limit = Math.min(Math.min(available, MAX_TARGETS), targets.size());
        int locked = 0;
        for (int index = 0; index < limit; index++) {
            SpatialMissileStrikeEntity strike = ModEntities.SPATIAL_MISSILE_STRIKE.get().create(player.serverLevel());
            if (strike == null) continue;
            strike.initialize(player, targets.get(index));
            if (player.serverLevel().addFreshEntity(strike)) locked++;
        }
        if (locked == 0) {
            player.displayClientMessage(Component.translatable("message.ore_craft.spatial_locator.no_targets"), true);
            return;
        }
        // 五秒内重复右键不再创建同一批标记，避免每刻扫描和多次叠加同一轮锁定。
        player.getCooldowns().addCooldown(ModItems.SPATIAL_LOCATOR.get(), LOCK_TICKS);
        player.displayClientMessage(Component.translatable("message.ore_craft.spatial_locator.locked", locked), true);
    }

    /**
     * 初次扫描、锁定期间及命中前共用的敌我识别规则，防止中途驯服或结盟后受到伤害。
     * Enemy 仅代表敌对类别，还需排除末影人、僵尸猪灵等实现 NeutralMob 的中立生物。
     * @param player 发起者
     * @param target 待检查的生物
     * @return 仍存活、同维度、非友军且属于非中立敌对生物时为 true
     */
    public static boolean eligible(ServerPlayer player, LivingEntity target) {
        return target.isAlive() && !target.isRemoved() && target.level() == player.level()
                && target instanceof Enemy && !(target instanceof NeutralMob)
                && !(target instanceof TamableAnimal) && !target.isAlliedTo(player) && !player.isAlliedTo(target);
    }

    /**
     * 统计主背包和快捷栏里的导弹，达到单轮上限即停止累加；不读取末影箱或嵌套容器。
     * @param player 弹药所有者
     * @return 0 至 64 的可用弹药数量
     */
    public static int ammunition(ServerPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.MINERAL_SPATIAL_GUIDED_MISSILE.get())) count += stack.getCount();
            if (count >= MAX_TARGETS) return MAX_TARGETS;
        }
        return count;
    }

    /**
     * 实际发射时扣除一枚导弹；即使创造模式也遵守背包弹药消耗规则。
     * 每个打击实体按服务端主线程依次执行，因此多个目标不会同时扣取同一枚物品。
     * @param player 弹药所有者
     * @return 找到并成功消耗一枚时为 true；不足则由调用方取消未发射打击
     */
    public static boolean consumeMissile(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.is(ModItems.MINERAL_SPATIAL_GUIDED_MISSILE.get())) continue;
            stack.shrink(1);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return true;
        }
        return false;
    }
}
