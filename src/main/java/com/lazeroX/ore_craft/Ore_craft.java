package com.lazeroX.ore_craft;

import com.lazeroX.ore_craft.command.OreConversionCommands;
import com.lazeroX.ore_craft.event.MiningFortuneEvents;
import com.lazeroX.ore_craft.event.MineralTimeAcceleration;
import com.lazeroX.ore_craft.event.MineralTeleportation;
import com.lazeroX.ore_craft.event.OreExperienceConverterInteraction;
import com.lazeroX.ore_craft.gametest.OreConversionGameTests;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.network.OreEnchantingNetwork;
import com.lazeroX.ore_craft.network.SoulSeparationNetwork;
import com.lazeroX.ore_craft.network.MineralTeleportScepterNetwork;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.register.ModCapabilities;
import com.lazeroX.ore_craft.register.ModCreativeTabs;
import com.lazeroX.ore_craft.register.ModEffects;
import com.lazeroX.ore_craft.register.ModEnchantments;
import com.lazeroX.ore_craft.register.ModEntities;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.register.ModPotions;
import com.lazeroX.ore_craft.register.ModRecipes;
import com.lazeroX.ore_craft.register.ModLootModifiers;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.world.level.block.entity.BlockEntity;

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
     * @param context Forge 在模组构造阶段注入的加载上下文，用于取得本模组的事件总线
     */
    public Ore_craft(FMLJavaModLoadingContext context) {
        // 最低支持的 Forge 47.4.0 已支持注入加载上下文，避免依赖已弃用的静态 get() 入口。
        IEventBus modEventBus = context.getModEventBus();
        // 方块物品依赖方块注册项，先初始化方块再初始化物品。
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModEnchantments.register(modEventBus);
        ModEffects.register(modEventBus);
        ModPotions.register(modEventBus);
        ModEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModMenus.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModLootModifiers.register(modEventBus);

        // 管道物流通过 MinecraftForge 物品能力访问机器，与原版漏斗共用方向和槽位规则。
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, ModCapabilities::register);

        modEventBus.addListener(OreConversionNetwork::register);
        modEventBus.addListener(OreEnchantingNetwork::register);
        modEventBus.addListener(SoulSeparationNetwork::register);
        modEventBus.addListener(MineralTeleportScepterNetwork::register);
        modEventBus.addListener((RegisterGameTestsEvent event) -> event.register(OreConversionGameTests.class));
        MinecraftForge.EVENT_BUS.addListener(OreConversionPrices::registerReloadListener);
        MinecraftForge.EVENT_BUS.addListener(OreConversionPrices::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(OreConversionPrices::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener(OreConversionNetwork::onLogin);
        MinecraftForge.EVENT_BUS.addListener(OreConversionNetwork::onRespawn);
        // 服务端管理命令使用全局事件总线，单人世界与专用服务器共用同一注册入口。
        MinecraftForge.EVENT_BUS.addListener(OreConversionCommands::register);

        // 配方在模组初始化时注册；酿造拦截和逐刻更新挂载到 Forge 游戏事件总线。
        modEventBus.addListener(MiningFortuneEvents::registerBrewingRecipes);
        MinecraftForge.EVENT_BUS.addListener(MiningFortuneEvents::onPotionBrew);
        MinecraftForge.EVENT_BUS.addListener(MineralTimeAcceleration::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(MineralTimeAcceleration::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(MineralTeleportation::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(MineralTeleportation::onEntityLeaveLevel);
        // 两端统一将经验转化器的潜行右键交给方块，确保手持物品时也能切换开关。
        MinecraftForge.EVENT_BUS.addListener(OreExperienceConverterInteraction::onRightClickBlock);
    }
}
