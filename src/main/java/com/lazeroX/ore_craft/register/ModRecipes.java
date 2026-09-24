package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.recipe.OreContainerUpgradeRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 注册需要保留矿质容器存储数据的配方类型。 */
public final class ModRecipes {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Ore_craft.MODID);

    /** 有序升级配方序列化器。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<OreContainerUpgradeRecipe>> ORE_CONTAINER_UPGRADE =
            SERIALIZERS.register("ore_container_upgrade", OreContainerUpgradeRecipe.Serializer::new);

    private ModRecipes() {}

    /**
     * 将配方序列化器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
