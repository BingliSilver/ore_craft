package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.loot.MiningFortuneLootModifier;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 注册采矿时运的 Forge 全局战利品修改器，数据包决定其启用和执行顺序。 */
public final class ModLootModifiers {
    /** Forge 专用编解码器注册表。 */
    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Ore_craft.MODID);
    /** 与 loot_modifiers/mining_fortune.json 对应的修改器类型。 */
    public static final RegistryObject<Codec<MiningFortuneLootModifier>> MINING_FORTUNE =
            MODIFIERS.register("mining_fortune", () -> MiningFortuneLootModifier.CODEC);

    /** 静态注册工具类不允许实例化。 */
    private ModLootModifiers() {}

    /** 将注册器绑定到模组生命周期总线。 */
    public static void register(IEventBus bus) { MODIFIERS.register(bus); }
}
