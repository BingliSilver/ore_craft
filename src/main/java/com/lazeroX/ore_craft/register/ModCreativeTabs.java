package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册本模组的创造模式选项卡。 */
public final class ModCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Ore_craft.MODID);

    /** 收录本模组全部物品和采矿时运药水的选项卡。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ORE_CRAFT_TAB =
            CREATIVE_MODE_TABS.register("ore_craft", () -> CreativeModeTab.builder()
                    .title(Component.translatable("creativetab.ore_craft.ore_craft"))
                    .icon(() -> ModItems.MINER_BADGE.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // 普通物品和方块物品共用一个注册器，因此可以统一加入。
                        ModItems.ITEMS.getEntries().forEach(item -> output.accept(item.get()));

                        // 药水不是独立物品注册项，需要按药水内容手动创建。
                        output.accept(PotionContents.createItemStack(Items.POTION, ModPotions.MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.SPLASH_POTION, ModPotions.MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.POTION, ModPotions.STRONG_MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.SPLASH_POTION, ModPotions.STRONG_MINING_FORTUNE_POTION));
                    })
                    .build());

    private ModCreativeTabs() {}

    /**
     * 将创造模式选项卡延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
