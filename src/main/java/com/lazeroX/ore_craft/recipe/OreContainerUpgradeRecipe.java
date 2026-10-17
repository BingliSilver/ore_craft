package com.lazeroX.ore_craft.recipe;

import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.register.ModRecipes;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/** 沿用有序配方布局，并在升级时继承原容器中的 ME。 */
public final class OreContainerUpgradeRecipe implements CraftingRecipe {
    /** 委托原版配方处理图案匹配、材料和配方书分类。 */
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

    /** 使用 1.20.1 的合成容器匹配原版有序图案。 */
    @Override
    public boolean matches(CraftingContainer input, Level level) { return base.matches(input, level); }

    /** 将中心旧容器的存储量写入升级后的容器。 */
    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        ItemStack result = base.assemble(input, registries);
        if (!(result.getItem() instanceof OreContainerItem target)) return result;
        for (int index = 0; index < input.getContainerSize(); index++) {
            ItemStack ingredient = input.getItem(index);
            if (ingredient.getItem() instanceof OreContainerItem source) {
                target.setStoredMe(result, Math.min(source.storedMe(ingredient), target.capacity()));
                break;
            }
        }
        return result;
    }

    /** 只有能够容纳原配方图案的工作台才能合成。 */
    @Override
    public boolean canCraftInDimensions(int width, int height) { return base.canCraftInDimensions(width, height); }

    /** 返回配方书使用的默认结果；真实合成在 assemble 中继承 ME。 */
    @Override
    public ItemStack getResultItem(RegistryAccess registries) { return base.getResultItem(registries); }

    /** 返回有序配方材料，供价格推导与配方查看器使用。 */
    @Override
    public NonNullList<Ingredient> getIngredients() { return base.getIngredients(); }

    /** 沿用原配方的配方书分类。 */
    @Override
    public CraftingBookCategory category() { return base.category(); }

    /** 沿用原配方的分组名称。 */
    @Override
    public String getGroup() { return base.getGroup(); }

    /** 沿用配方的解锁通知设置。 */
    @Override
    public boolean showNotification() { return base.showNotification(); }

    /** 返回保留容器 ME 的自定义序列化器。 */
    @Override
    public RecipeSerializer<?> getSerializer() { return ModRecipes.ORE_CONTAINER_UPGRADE.get(); }

    /** 1.20.1 的配方自身保存资源 ID，委托基础配方返回此标识。 */
    @Override
    public ResourceLocation getId() { return base.getId(); }

    /** 复用原版有序配方的编解码规则。 */
    public static final class Serializer implements RecipeSerializer<OreContainerUpgradeRecipe> {
        /** 无状态的原版有序配方解析器，统一处理 JSON 与配方同步。 */
        private final ShapedRecipe.Serializer delegate = new ShapedRecipe.Serializer();

        /** 由配方注册器创建一次，此后复用解析器。 */
        public Serializer() {}

        /** 从 1.20.1 数据包 JSON 读取原版图案和输出。 */
        @Override
        public OreContainerUpgradeRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new OreContainerUpgradeRecipe(delegate.fromJson(id, json));
        }

        /** 从服务端同步的配方缓冲区还原升级配方。 */
        @Override
        public OreContainerUpgradeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new OreContainerUpgradeRecipe(delegate.fromNetwork(id, buffer));
        }

        /** 同步基础图案；继承 ME 的逻辑由两端相同的配方实现保证。 */
        @Override
        public void toNetwork(FriendlyByteBuf buffer, OreContainerUpgradeRecipe recipe) {
            delegate.toNetwork(buffer, recipe.base);
        }
    }
}
