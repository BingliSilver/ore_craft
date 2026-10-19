package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.Ore_craft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;

/**
 * 只在物理客户端加载的模组初始化事件。
 */
@EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OreCraftClientEvents {
    /** 工具类不允许创建实例。 */
    private OreCraftClientEvents() {
    }

    /**
     * 注册采矿 TNT、灵魂身体实体以及矿质附魔书方块实体的渲染器。
     *
     * @param event MinecraftForge 实体渲染器注册事件
     */
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MINING_TNT_ENTITY.get(), MiningTntRenderer::new);
        event.registerEntityRenderer(ModEntities.SOUL_BODY.get(), SoulBodyRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.ORE_ENCHANTING_TABLE.get(), OreEnchantingBookRenderer::new);
    }

    /**
     * 注册转化桌、学习宝典、附魔台、各类机器和时间权杖的客户端菜单界面。
     * 普通及 Plus 机器共用相应屏幕实现，经验转化器使用独立单槽界面。
     * 所有屏幕继承公共缩放层，统一响应玩家的 GUI 界面尺寸设置与窗口变化。
     *
     * @param event Forge 客户端初始化事件
     */
    @SubscribeEvent
    public static void registerMenuScreens(FMLClientSetupEvent event) {
        // 屏幕工厂使用非线程安全的原版注册表，须转到客户端初始化工作队列。
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.ORE_CONVERSION_MENU.get(), OreConversionScreen::new);
            // 两种转化桌共用界面，容器版由菜单标记显示额外的交易槽。
            MenuScreens.register(ModMenus.ORE_CONTAINER_CONVERSION_MENU.get(), OreConversionScreen::new);
            MenuScreens.register(ModMenus.ORE_LEARNING_MENU.get(), OreLearningScreen::new);
            MenuScreens.register(ModMenus.ORE_ENCHANTING_MENU.get(), OreEnchantingScreen::new);
            MenuScreens.register(ModMenus.ORE_CONVERTER_MENU.get(), OreConverterScreen::new);
            MenuScreens.register(ModMenus.ORE_CONVERSION_MACHINE_MENU.get(), OreConversionMachineScreen::new);
            MenuScreens.register(ModMenus.ORE_EXPERIENCE_CONVERTER_MENU.get(), OreExperienceConverterScreen::new);
            MenuScreens.register(ModMenus.MINERAL_TIME_SCEPTER_MENU.get(), MineralTimeScepterScreen::new);
        });
    }

    /** 提示组件工厂属于 MOD 总线；实际提示事件仍在 Forge 游戏总线上处理。 */
    @SubscribeEvent
    public static void registerTooltips(RegisterClientTooltipComponentFactoriesEvent event) {
        OreConversionTooltip.registerTooltipComponent(event);
    }

    /** @param event HUD 注册事件；倒计时在旁观者物品栏上方独立绘制，不依赖生存模式热键栏 */
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("soul_return_countdown", (gui, graphics, partialTick, width, height) ->
                SoulSeparationClient.renderCountdown(graphics, width, height));
    }
}
