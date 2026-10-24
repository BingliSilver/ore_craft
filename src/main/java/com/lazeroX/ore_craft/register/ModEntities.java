package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import com.lazeroX.ore_craft.entity.SoulBodyEntity;
import com.lazeroX.ore_craft.entity.MineralTeleportPortalEntity;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
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

    /** 出窍期间的临时身体：使用玩家体型、不保存到区块，装备与皮肤由服务端同步。 */
    public static final RegistryObject<EntityType<SoulBodyEntity>> SOUL_BODY =
            ENTITY_TYPES.register("soul_body", () -> EntityType.Builder
                    .<SoulBodyEntity>of(SoulBodyEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .noSave()
                    .noSummon()
                    .build(Ore_craft.MODID + ":soul_body"));

    /** 权杖生成的一次性入口，无重力、不写入区块存档，外观和生命周期由实体同步。 */
    public static final RegistryObject<EntityType<MineralTeleportPortalEntity>> MINERAL_TELEPORT_PORTAL =
            ENTITY_TYPES.register("mineral_teleport_portal", () -> EntityType.Builder
                    .<MineralTeleportPortalEntity>of(MineralTeleportPortalEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(MineralTeleportPortalEntity.WIDTH, MineralTeleportPortalEntity.HEIGHT)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .noSave()
                    .noSummon()
                    .build(Ore_craft.MODID + ":mineral_teleport_portal"));

    /** 实体类型由注册生命周期管理，不创建工具类实例。 */
    private ModEntities() {}

    /**
     * 将实体延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
    }

    /** @param event 模组生物属性创建事件，身体必须具备生命和防御属性才能安全生成 */
    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(SOUL_BODY.get(), SoulBodyEntity.createAttributes().build());
    }
}
