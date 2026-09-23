package com.lazeroX.ore_craft.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 提高矿物掉落收益的正面状态效果。
 *
 * <p>该状态效果本身不执行周期逻辑；实际的时运等级叠加和掉落重算由
 * {@code MiningFortuneEvents} 在方块掉落事件中完成。</p>
 */
public final class MiningFortuneEffect extends MobEffect {
    /** 药水液体、状态效果图标和粒子使用的浅绿色显示颜色。 */
    private static final int EFFECT_COLOR = 0x90EE90;

    /** 创建采矿时运正面效果。 */
    public MiningFortuneEffect() {
        // 效果只保存分类和显示颜色，方块掉落能力由事件处理器集中实现。
        super(MobEffectCategory.BENEFICIAL, EFFECT_COLOR);
    }
}
