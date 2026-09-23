package com.lazeroX.ore_craft;

import com.lazeroX.ore_craft.event.MiningFortuneEvents;
import com.lazeroX.ore_craft.gametest.OreConversionGameTests;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModCreativeTabs;
import com.lazeroX.ore_craft.register.ModEffects;
import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.register.ModPotions;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 矿石工艺模组的入口类。
 *
 * <p>负责挂载各类延迟注册器，以及游戏运行期间使用的事件监听器。</p>
 */
@Mod(Ore_craft.MODID)
public class Ore_craft {
    /** 模组在注册表和资源文件中使用的命名空间。 */
    public static final String MODID = "ore_craft";

    /**
     * 创建模组入口并挂载全部注册器与游戏事件监听器。
     *
     * @param modEventBus 当前模组专用的注册和生命周期事件总线
     */
    public Ore_craft(IEventBus modEventBus) {
        // 方块物品依赖方块注册项，先初始化方块再初始化物品。
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModEffects.register(modEventBus);
        ModPotions.register(modEventBus);
        ModEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModMenus.register(modEventBus);

        modEventBus.addListener(OreConversionNetwork::register);
        modEventBus.addListener((RegisterGameTestsEvent event) -> event.register(OreConversionGameTests.class));
        NeoForge.EVENT_BUS.addListener(OreConversionPrices::registerReloadListener);
        NeoForge.EVENT_BUS.addListener(OreConversionPrices::onServerStarted);
        NeoForge.EVENT_BUS.addListener(OreConversionPrices::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(OreConversionNetwork::onLogin);
        NeoForge.EVENT_BUS.addListener(OreConversionNetwork::onRespawn);

        // 酿造与方块掉落属于游戏运行期事件，因此监听 NeoForge 全局事件总线。
        NeoForge.EVENT_BUS.addListener(MiningFortuneEvents::registerBrewingRecipes);
        NeoForge.EVENT_BUS.addListener(MiningFortuneEvents::onPotionBrew);
        NeoForge.EVENT_BUS.addListener(MiningFortuneEvents::onBlockDrops);
    }
}
