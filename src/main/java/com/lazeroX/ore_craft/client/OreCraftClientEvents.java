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
     * 注册采矿 TNT、灵魂身体、权杖传送门以及矿质附魔书方块实体的渲染器。
     *
     * @param event NeoForge 实体渲染器注册事件
     */
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MINING_TNT_ENTITY.get(), MiningTntRenderer::new);
        event.registerEntityRenderer(ModEntities.SOUL_BODY.get(), SoulBodyRenderer::new);
        event.registerEntityRenderer(ModEntities.MINERAL_TELEPORT_PORTAL.get(), MineralTeleportPortalRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.ORE_ENCHANTING_TABLE.get(), OreEnchantingBookRenderer::new);
    }

    /**
     * 注册转化桌、两款合成台、学习宝典、附魔台、各类机器及时间、传送权杖的客户端菜单界面。
     * 普通及 Plus 机器共用相应屏幕实现，经验转化器使用独立单槽界面。
     * 所有屏幕继承公共缩放层，统一响应玩家的 GUI 界面尺寸设置与窗口变化。
     *
     * @param event 客户端菜单屏幕注册事件
     */
    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ORE_CRAFTING_MENU.get(), OreCraftingScreen::new);
        event.register(ModMenus.ORE_CRAFTING_PLUS_MENU.get(), OreCraftingScreen::new);
        event.register(ModMenus.MINERAL_TELEPORT_SCEPTER_MENU.get(), MineralTeleportScepterScreen::new);
        event.register(ModMenus.ORE_CONVERSION_MENU.get(), OreConversionScreen::new);
        // 两种转化桌共用界面，容器版由菜单标记显示额外的交易槽。
        event.register(ModMenus.ORE_CONTAINER_CONVERSION_MENU.get(), OreConversionScreen::new);
        event.register(ModMenus.ORE_LEARNING_MENU.get(), OreLearningScreen::new);
        event.register(ModMenus.ORE_ENCHANTING_MENU.get(), OreEnchantingScreen::new);
        event.register(ModMenus.ORE_CONVERTER_MENU.get(), OreConverterScreen::new);
        event.register(ModMenus.ORE_CONVERSION_MACHINE_MENU.get(), OreConversionMachineScreen::new);
        event.register(ModMenus.ORE_EXPERIENCE_CONVERTER_MENU.get(), OreExperienceConverterScreen::new);
        event.register(ModMenus.MINERAL_TIME_SCEPTER_MENU.get(), MineralTimeScepterScreen::new);
    }

    /** @param event HUD 注册事件；倒计时在旁观者物品栏上方独立绘制，不依赖生存模式热键栏 */
    @SubscribeEvent
    public static void registerOverlays(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                Ore_craft.MODID, "soul_return_countdown"), (graphics, deltaTracker) ->
                SoulSeparationClient.renderCountdown(graphics, graphics.guiWidth(), graphics.guiHeight()));
    }
}
