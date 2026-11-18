package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.entity.SpatialMissileStrikeEntity;
import com.lazeroX.ore_craft.item.SpatialLocatorItem;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.value.CarriedContainerEnergy;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
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
    /** 每次最多选择 64 个目标，同时受背包导弹和可用 ME 的限制。 */
    public static final int MAX_TARGETS = 64;
    /** 锁定五秒，按正常游戏时间每秒二十刻计时。 */
    public static final int LOCK_TICKS = 100;
    /** 命中的基础伤害；后续仍遵循原版伤害事件、免疫和减伤规则。 */
    public static final float DAMAGE = 100.0F;

    /** 无状态工具类不需要实例。 */
    private SpatialMissileStrikes() {}

    /**
     * 在已加载实体集合中选择最近的敌对目标，创建跟随目标的红色锁定标记。
     * 不主动读取或生成区块；不在此时扣弹，给目标死亡或卸载保留无消耗取消的机会。
     * @param player 使用定位器的玩家
     * @param options 本次锁定采用的回收开关，固定到本轮每一枚导弹
     */
    public static void start(ServerPlayer player, int options) {
        if (!player.isAlive() || player.isSpectator() || player.getCooldowns().isOnCooldown(ModItems.SPATIAL_LOCATOR.get())) return;
        int available = ammunition(player, SpatialLocatorItem.surcharge(options));
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
            strike.initialize(player, targets.get(index), options);
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
     * 估算本轮能锁定的目标数。先统计主背包与快捷栏的导弹，再用可支付的整枚数量补足。
     * @param player 弹药所有者
     * @param extra 每发回收费，实体导弹也需支付
     * @return 0 至 64 的可用弹药数量
     */
    private static int ammunition(ServerPlayer player, int extra) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.MINERAL_SPATIAL_GUIDED_MISSILE.get())) count += stack.getCount();
            if (count >= MAX_TARGETS) {
                count = MAX_TARGETS;
                break;
            }
        }
        long balance = CarriedContainerEnergy.available(player);
        // 先为实体导弹预留附加费，再用剩余 ME 计算可提取并发射的整枚数；此处只预估。
        int physical = extra == 0 ? count : OreMachineEnergy.conversionCount(count, extra, balance);
        if (physical < count || physical == MAX_TARGETS) return physical;
        long price = extractionCost(player, extra);
        return physical + OreMachineEnergy.conversionCount(MAX_TARGETS - physical, price, balance - (long) physical * extra);
    }

    /**
     * 实际发射时优先扣除一枚背包导弹；耗尽后按已学导弹的当前 ME 单价付款，创造模式规则相同。
     * 每个打击实体按服务端主线程依次执行，因此多个目标不会同时扣取同一枚物品。
     * @param player 弹药所有者
     * @param extra 锁定时确定的每发附加费
     * @return 找到并成功消耗一枚时为 true；不足则由调用方取消未发射打击
     */
    public static boolean consumeMissile(ServerPlayer player, int extra) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.is(ModItems.MINERAL_SPATIAL_GUIDED_MISSILE.get())) continue;
            // 附加费先完整支付，余额不足时保留这枚实体导弹。
            if (extra > 0 && !CarriedContainerEnergy.pay(player, extra)) return false;
            stack.shrink(1);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return true;
        }
        // 锁定后玩家可能移走容器或遇到价格重载，每枚导弹发射前独立核价并完整扣款。
        long price = extractionCost(player, extra);
        return price > 0 && CarriedContainerEnergy.pay(player, price);
    }

    /**
     * 查询玩家能否按转化规则用 ME 补充导弹。
     * @param player 使用定位器的玩家
     * @param extra 回收功能的单枚附加费
     * @return 提取并发射一枚的总价；无法提取或总价超出可支付上限时为零
     */
    private static long extractionCost(ServerPlayer player, int extra) {
        var missile = ModItems.MINERAL_SPATIAL_GUIDED_MISSILE;
        if (!OreConversionSavedData.get(player).account(player).knows(missile.getId())
                || !OreConversionPrices.canExtract(missile.get())) return 0;
        long base = OreConversionPrices.price(missile.get()).orElse(0);
        return base > 0 && base <= Long.MAX_VALUE - extra ? base + extra : 0;
    }
}
