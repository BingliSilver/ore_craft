package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.register.ModPotions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.brewing.IBrewingRecipe;
import net.minecraftforge.event.brewing.PotionBrewEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** 注册采矿时运药水的两级酿造，并禁止通过龙息或滞留型输入绕过形态限制。 */
public final class MiningFortuneEvents {
    /** 酿造台前面三格为药水，第四格为原料。 */
    private static final int BOTTLE_SLOTS = 3;
    private static final int INGREDIENT_SLOT = 3;

    /** 事件工具类不允许实例化。 */
    private MiningFortuneEvents() {}

    /** Forge 1.20.1 通过公共初始化队列注册酿造配方，注册表此时已就绪。 */
    public static void registerBrewingRecipes(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BrewingRecipeRegistry.addRecipe(new PotionMix(Potions.THICK, Items.EMERALD, ModPotions.MINING_FORTUNE_POTION.get()));
            BrewingRecipeRegistry.addRecipe(new PotionMix(ModPotions.MINING_FORTUNE_POTION.get(), Items.REDSTONE,
                    ModPotions.STRONG_MINING_FORTUNE_POTION.get()));
        });
    }

    /** 酿造前拒绝滞留形态；火药将瓶装转成喷溅型仍由原版处理。 */
    public static void onPotionBrew(PotionBrewEvent.Pre event) {
        ItemStack ingredient = event.getItem(INGREDIENT_SLOT);
        for (int slot = 0; slot < BOTTLE_SLOTS; slot++) {
            ItemStack stack = event.getItem(slot);
            Potion potion = PotionUtils.getPotion(stack);
            boolean mining = potion == ModPotions.MINING_FORTUNE_POTION.get()
                    || potion == ModPotions.STRONG_MINING_FORTUNE_POTION.get();
            boolean forbidden = ingredient.is(Items.DRAGON_BREATH) && mining;
            forbidden |= stack.is(Items.LINGERING_POTION) && (
                    ingredient.is(Items.EMERALD) && potion == Potions.THICK
                    || ingredient.is(Items.REDSTONE) && potion == ModPotions.MINING_FORTUNE_POTION.get());
            if (forbidden) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** 按药水 NBT 匹配的酿造配方，避免仅按物品类型把任意药水当成浓稠药水。 */
    private static final class PotionMix implements IBrewingRecipe {
        /** 输入药水内容、单件原料与输出内容，均在注册完成后固定。 */
        private final Potion input;
        private final Item ingredient;
        private final Potion output;

        /** 创建保留瓶装或喷溅外形的药水转换。 */
        private PotionMix(Potion input, Item ingredient, Potion output) {
            this.input = input;
            this.ingredient = ingredient;
            this.output = output;
        }

        /** 只匹配瓶装或喷溅型的指定药水，滞留药水始终不能作为输入。 */
        @Override
        public boolean isInput(ItemStack stack) {
            return (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)) && PotionUtils.getPotion(stack) == input;
        }

        /** 检查酿造原料的注册物品类型。 */
        @Override
        public boolean isIngredient(ItemStack stack) { return stack.is(ingredient); }

        /** 只改变副本中的药水类型，保留外形及其他自定义 NBT，不修改真实输入。 */
        @Override
        public ItemStack getOutput(ItemStack stack, ItemStack reagent) {
            return isInput(stack) && isIngredient(reagent)
                    ? PotionUtils.setPotion(stack.copyWithCount(1), output) : ItemStack.EMPTY;
        }
    }
}
