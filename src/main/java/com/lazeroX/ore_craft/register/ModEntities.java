package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册本模组的实体类型。 */
public final class ModEntities {
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Ore_craft.MODID);

    /** 采矿 TNT 点燃后使用的同步实体类型。 */
    public static final DeferredHolder<EntityType<?>, EntityType<MiningPrimedTntEntity>> MINING_TNT_ENTITY =
            ENTITY_TYPES.register("mining_tnt", () -> EntityType.Builder
                    .<MiningPrimedTntEntity>of(MiningPrimedTntEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(0.98F, 0.98F)
                    .eyeHeight(0.15F)
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build(Ore_craft.MODID + ":mining_tnt"));

    private ModEntities() {}

    /**
     * 将实体延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
