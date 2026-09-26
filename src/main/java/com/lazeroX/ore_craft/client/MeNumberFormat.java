package com.lazeroX.ore_craft.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** 为客户端有限宽度的 ME 数值提供统一的后置单位显示。 */
public final class MeNumberFormat {
    /** 工具类不允许创建实例。 */
    private MeNumberFormat() {}

    /**
     * 将非负 ME 数值缩写为转换桌使用的 k/m/b/t/q/Q 格式。
     * 小于一万时保留完整整数；大额余额只改变显示，不影响实际存储或结算。
     *
     * @param value 待显示的 ME 数值
     * @return 带千位分隔符的原值，或带一个后置单位的缩写
     */
    public static String compact(long value) {
        if (value < 10_000L) return String.format(Locale.ROOT, "%,d", value);
        if (value < 1_000_000L) return (value / 1000) + "k";
        if (value < 1_000_000_000L) return withSuffix(value, 1_000_000L, "m");
        if (value < 1_000_000_000_000L) return withSuffix(value, 1_000_000_000L, "b");
        if (value < 1_000_000_000_000_000L) return withSuffix(value, 1_000_000_000_000L, "t");
        if (value < 1_000_000_000_000_000_000L) return withSuffix(value, 1_000_000_000_000_000L, "q");
        return withSuffix(value, 1_000_000_000_000_000_000L, "Q");
    }

    /** 按指定倍率保留两位小数，再附加对应数量级的后置单位。 */
    private static String withSuffix(long value, long divisor, String suffix) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)
                .toPlainString() + suffix;
    }
}
