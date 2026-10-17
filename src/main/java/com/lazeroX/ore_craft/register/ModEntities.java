package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/** 集中声明和注册本模组的实体类型。 */
public final class ModEntities {
    /** 持有采矿 TNT 等实体类型的延迟注册器。 */
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Ore_craft.MODID);

    /** 采矿 TNT 点燃后使用的同步实体类型。 */
    public static final RegistryObject<EntityType<MiningPrimedTntEntity>> MINING_TNT_ENTITY =
            ENTITY_TYPES.register("mining_tnt", () -> EntityType.Builder
                    .<MiningPrimedTntEntity>of(MiningPrimedTntEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(0.98F, 0.98F)
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build(Ore_craft.MODID + ":mining_tnt"));

    /** 实体类型由注册生命周期管理，不创建工具类实例。 */
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
