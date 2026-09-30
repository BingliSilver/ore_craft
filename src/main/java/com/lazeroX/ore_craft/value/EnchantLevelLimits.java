package com.lazeroX.ore_craft.value;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 矿质附魔台的等级选择规则，供客户端控件和服务端交易共同使用。
 * 原版附魔固定使用 Minecraft 1.21.1 的原始上限，避免神化或数据包提高定义上限后
 * 允许矿质附魔台制造超限附魔；其他模组新增的附魔遵守其定义上限。
 * 已有超限附魔可以保留，也可以降至可制作范围并回收 ME，其价值由价格计算器独立处理。
 */
public final class EnchantLevelLimits {
    /** 原版物品附魔组件能够保存的最高等级，与 ME 估值范围无关。 */
    private static final int MAX_STORED_LEVEL = 255;

    /** 工具类只提供无状态规则，不允许创建实例。 */
    private EnchantLevelLimits() {
    }

    /**
     * 返回附魔台能够主动写入的最高等级，不限制已有附魔的读取或 ME 估值。
     *
     * @param enchantment 当前世界注册表中的附魔
     * @return 可制作的最高等级，范围为 1 至 255
     */
    public static int maxCraftableLevel(Holder<Enchantment> enchantment) {
        // 原版附魔通过注册 ID 识别；本地化名称和可被模组改写的定义不能作为原版上限依据。
        ResourceLocation id = enchantment.unwrapKey().map(key -> key.location()).orElse(null);
        int maximum = enchantment.value().getMaxLevel();
        if (id != null && "minecraft".equals(id.getNamespace())) {
            // 以下覆盖 1.21.1 全部 42 种原版附魔，包含重锤新增的致密、破甲与风爆。
            maximum = switch (id.getPath()) {
                case "aqua_affinity", "binding_curse", "channeling", "flame", "infinity",
                        "mending", "multishot", "silk_touch", "vanishing_curse" -> 1;
                case "fire_aspect", "frost_walker", "knockback", "punch" -> 2;
                case "depth_strider", "fortune", "looting", "loyalty", "luck_of_the_sea",
                        "lure", "quick_charge", "respiration", "riptide", "soul_speed",
                        "sweeping_edge", "swift_sneak", "thorns", "unbreaking", "wind_burst" -> 3;
                case "blast_protection", "breach", "feather_falling", "fire_protection",
                        "piercing", "projectile_protection", "protection" -> 4;
                case "bane_of_arthropods", "density", "efficiency", "impaling", "power",
                        "sharpness", "smite" -> 5;
                // 未知 ID 沿用定义，以兼容新增附魔而不把它们误判成一级附魔。
                default -> maximum;
            };
        }
        return Math.clamp(maximum, 1, MAX_STORED_LEVEL);
    }

    /**
     * 检查目标等级是否可选；超限原等级只允许原样保留，变更后必须落在可制作范围内。
     *
     * @param enchantment 要调整的附魔
     * @param currentLevel 输入物品实际保存的等级，零表示尚未附魔
     * @param targetLevel 请求的目标等级，零表示移除并回收全部 ME
     * @return 原样保留或目标位于零至制作上限之间时为 true
     */
    public static boolean canSelectLevel(Holder<Enchantment> enchantment, int currentLevel, int targetLevel) {
        return targetLevel >= 0
                && (targetLevel == currentLevel || targetLevel <= maxCraftableLevel(enchantment));
    }
}
