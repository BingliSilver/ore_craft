package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.block.entity.OreEnchantingBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中注册需要逐帧渲染或保存状态的方块实体类型。 */
public final class ModBlockEntities {
    /** 方块实体延迟注册器。 */
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Ore_craft.MODID);

    /** 矿质附魔台的书本动画状态，不保存附魔输入物品。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OreEnchantingBlockEntity>> ORE_ENCHANTING_TABLE =
            BLOCK_ENTITY_TYPES.register("ore_enchanting_table", () -> BlockEntityType.Builder.of(
                    OreEnchantingBlockEntity::new, ModBlocks.ORE_ENCHANTING_TABLE.get()).build(null));

    /** 工具类不允许创建实例。 */
    private ModBlockEntities() {
    }

    /** 将方块实体类型挂载到模组事件总线。 */
    public static void register(IEventBus eventBus) {
        BLOCK_ENTITY_TYPES.register(eventBus);
    }
}
