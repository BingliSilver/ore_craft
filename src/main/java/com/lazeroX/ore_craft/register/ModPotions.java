package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册本模组的药水类型。 */
public final class ModPotions {
    /** 采矿时运药水的基础持续时间：2 分钟。 */
    private static final int MINING_FORTUNE_DURATION_TICKS = 20 * 60 * 2;

    private static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(BuiltInRegistries.POTION, Ore_craft.MODID);

    /** I 级采矿时运药水，效果等同于额外增加时运 II。 */
    public static final DeferredHolder<Potion, Potion> MINING_FORTUNE_POTION =
            POTIONS.register("mining_fortune", () -> new Potion(
                    "ore_craft.mining_fortune",
                    new MobEffectInstance(ModEffects.MINING_FORTUNE_EFFECT, MINING_FORTUNE_DURATION_TICKS, 0)
            ));

    /** II 级采矿时运药水，效果等同于额外增加时运 III。 */
    public static final DeferredHolder<Potion, Potion> STRONG_MINING_FORTUNE_POTION =
            POTIONS.register("strong_mining_fortune", () -> new Potion(
                    "ore_craft.strong_mining_fortune",
                    new MobEffectInstance(ModEffects.MINING_FORTUNE_EFFECT, MINING_FORTUNE_DURATION_TICKS, 1)
            ));

    private ModPotions() {}

    /**
     * 将药水延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        POTIONS.register(modEventBus);
    }
}
