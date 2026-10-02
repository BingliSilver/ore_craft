package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.Ore_craft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 只在物理客户端加载的模组初始化事件。
 */
@EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT)
public final class OreCraftClientEvents {
    /** 工具类不允许创建实例。 */
    private OreCraftClientEvents() {
    }

    /**
     * 注册采矿 TNT 实体和矿质附魔书方块实体的渲染器。
     *
     * @param event NeoForge 实体渲染器注册事件
     */
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MINING_TNT_ENTITY.get(), MiningTntRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.ORE_ENCHANTING_TABLE.get(), OreEnchantingBookRenderer::new);
    }

    /**
     * 注册六种客户端菜单界面，普通及 Plus 机器共用相应屏幕实现。
     * 所有屏幕继承公共缩放层，统一响应玩家的 GUI 界面尺寸设置与窗口变化。
     *
     * @param event 客户端菜单屏幕注册事件
     */
    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ORE_CONVERSION_MENU.get(), OreConversionScreen::new);
        // 两种转化桌共用界面，容器版由菜单标记显示额外的交易槽。
        event.register(ModMenus.ORE_CONTAINER_CONVERSION_MENU.get(), OreConversionScreen::new);
        event.register(ModMenus.ORE_LEARNING_MENU.get(), OreLearningScreen::new);
        event.register(ModMenus.ORE_ENCHANTING_MENU.get(), OreEnchantingScreen::new);
        event.register(ModMenus.ORE_CONVERTER_MENU.get(), OreConverterScreen::new);
        event.register(ModMenus.ORE_CONVERSION_MACHINE_MENU.get(), OreConversionMachineScreen::new);
        event.register(ModMenus.MINERAL_TIME_SCEPTER_MENU.get(), MineralTimeScepterScreen::new);
    }
}
