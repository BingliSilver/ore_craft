package com.lazeroX.ore_craft.value;

import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 按附魔定义统一计算矿质消耗。价格只由等级、权重和 Treasure 标签决定，
 * 因此数据包或其他模组注册的附魔也遵循同一规则。
 */
public final class EnchantMeCostCalculator {
    /** 一级普通常见附魔的基础价格，单位为 ME。 */
    public static final long BASE_ENCHANT_ME = 2048L;

    /** 工具类不允许创建实例。 */
    private EnchantMeCostCalculator() {
    }

    /**
     * 计算指定附魔等级的完整价格。乘法溢出时饱和到 long 最大值，
     * 不会绕回负数或意外变成低价。
     *
     * @param enchantment 当前世界注册表中的附魔 Holder
     * @param level 目标附魔等级，必须位于定义允许范围内
     * @return 所需 ME，最少为 1，溢出时为 {@link Long#MAX_VALUE}
     * @throws IllegalArgumentException 等级低于 1 或高于附魔定义上限时抛出
     */
    public static long calculateCost(Holder<Enchantment> enchantment, int level) {
        if (level < 1 || level > enchantment.value().getMaxLevel()) {
            throw new IllegalArgumentException("Invalid enchantment level: " + level);
        }
        long cost = saturatedMultiply(BASE_ENCHANT_ME, getLevelMultiplier(level));
        cost = saturatedMultiply(cost, getRarityMultiplier(enchantment.value().getWeight()));
        return Math.max(1L, saturatedMultiply(cost, getTreasureMultiplier(enchantment)));
    }

    /**
     * 计算一次等级变化的 ME 差额；零级表示没有该附魔且价格为零。
     * 正数需支付，负数可存储，未变化时为零。两项价格均为非负 long，差值不会溢出。
     *
     * @param enchantment 要修改的附魔
     * @param originalLevel 物品当前等级，允许为零
     * @param targetLevel 玩家选定等级，允许为零
     * @return 目标价格与当前价格的差额，单位为 ME
     */
    public static long calculateDifference(Holder<Enchantment> enchantment, int originalLevel, int targetLevel) {
        long original = originalLevel == 0 ? 0L : calculateCost(enchantment, originalLevel);
        long target = targetLevel == 0 ? 0L : calculateCost(enchantment, targetLevel);
        return target - original;
    }

    /** 等级每增加一级，价格翻倍；超过 long 可表示范围时饱和。 */
    private static long getLevelMultiplier(int level) {
        return level > 63 ? Long.MAX_VALUE : 1L << (level - 1);
    }

    /** 按附魔数据定义的抽取权重分配二进制稀有度倍率。 */
    private static long getRarityMultiplier(int weight) {
        if (weight >= 10) return 1L;
        if (weight >= 5) return 2L;
        if (weight >= 2) return 4L;
        return 8L;
    }

    /** 1.21.1 的 Treasure 身份由注册表标签提供，而非附魔名称判断。 */
    private static long getTreasureMultiplier(Holder<Enchantment> enchantment) {
        return enchantment.is(EnchantmentTags.TREASURE) ? 2L : 1L;
    }

    /** 对正数价格执行不会溢出的乘法。 */
    private static long saturatedMultiply(long left, long right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
