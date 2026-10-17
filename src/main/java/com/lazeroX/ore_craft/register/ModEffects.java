package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.effect.MiningFortuneEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/** 集中声明和注册本模组的状态效果。 */
public final class ModEffects {
    /** 持有本模组状态效果的延迟注册器。 */
    private static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Ore_craft.MODID);

    /** 可与工具时运附魔叠加的采矿时运效果。 */
    public static final RegistryObject<MobEffect> MINING_FORTUNE_EFFECT =
            MOB_EFFECTS.register("mining_fortune", MiningFortuneEffect::new);

    /** 状态效果由注册生命周期管理，不创建工具类实例。 */
    private ModEffects() {}

    /**
     * 将状态效果延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        MOB_EFFECTS.register(modEventBus);
    }
}
