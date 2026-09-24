package com.lazeroX.ore_craft.recipe;

import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.register.ModRecipes;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/** 沿用有序配方布局，并在升级时继承原容器中的 ME。 */
public final class OreContainerUpgradeRecipe implements CraftingRecipe {
    private final ShapedRecipe base;

    /**
     * 用普通有序配方创建可保留 ME 的升级配方。
     *
     * @param base 包含原版图案、材料和输出的配方
     */
    public OreContainerUpgradeRecipe(ShapedRecipe base) {
        this.base = base;
    }

    /** 返回内部的普通有序配方，供序列化使用。 */
    public ShapedRecipe base() { return base; }

    @Override
    public boolean matches(CraftingInput input, Level level) { return base.matches(input, level); }

    /** 将中心旧容器的存储量写入升级后的容器。 */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = base.assemble(input, registries);
        if (!(result.getItem() instanceof OreContainerItem target)) return result;
        for (int index = 0; index < input.size(); index++) {
            ItemStack ingredient = input.getItem(index);
            if (ingredient.getItem() instanceof OreContainerItem source) {
                target.setStoredMe(result, Math.min(source.storedMe(ingredient), target.capacity()));
                break;
            }
        }
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) { return base.canCraftInDimensions(width, height); }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) { return base.getResultItem(registries); }

    @Override
    public NonNullList<Ingredient> getIngredients() { return base.getIngredients(); }

    @Override
    public CraftingBookCategory category() { return base.category(); }

    @Override
    public String getGroup() { return base.getGroup(); }

    @Override
    public boolean showNotification() { return base.showNotification(); }

    @Override
    public RecipeSerializer<?> getSerializer() { return ModRecipes.ORE_CONTAINER_UPGRADE.get(); }

    /** 复用原版有序配方的编解码规则。 */
    public static final class Serializer implements RecipeSerializer<OreContainerUpgradeRecipe> {
        private static final MapCodec<OreContainerUpgradeRecipe> CODEC =
                ShapedRecipe.Serializer.CODEC.xmap(OreContainerUpgradeRecipe::new, OreContainerUpgradeRecipe::base);
        private static final StreamCodec<RegistryFriendlyByteBuf, OreContainerUpgradeRecipe> STREAM_CODEC = StreamCodec.of(
                (buf, recipe) -> ShapedRecipe.Serializer.STREAM_CODEC.encode(buf, recipe.base),
                buf -> new OreContainerUpgradeRecipe(ShapedRecipe.Serializer.STREAM_CODEC.decode(buf)));

        @Override
        public MapCodec<OreContainerUpgradeRecipe> codec() { return CODEC; }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, OreContainerUpgradeRecipe> streamCodec() { return STREAM_CODEC; }
    }
}
