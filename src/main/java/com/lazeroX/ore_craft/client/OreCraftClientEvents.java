package com.lazeroX.ore_craft.client;

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
     * 注册采矿 TNT 点燃实体的渲染器。
     *
     * @param event NeoForge 实体渲染器注册事件
     */
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Ore_craft.MINING_TNT_ENTITY.get(), MiningTntRenderer::new);
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Ore_craft.ORE_CONVERSION_MENU.get(), OreConversionScreen::new);
    }
}
