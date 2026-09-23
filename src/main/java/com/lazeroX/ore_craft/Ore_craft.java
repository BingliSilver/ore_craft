package com.lazeroX.ore_craft;

import com.lazeroX.ore_craft.block.MiningTntBlock;
import com.lazeroX.ore_craft.block.OreConversionTableBlock;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.gametest.OreConversionGameTests;
import com.lazeroX.ore_craft.effect.MiningFortuneEffect;
import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import com.lazeroX.ore_craft.event.MiningFortuneEvents;
import com.lazeroX.ore_craft.item.EmeraldCoalBlockItem;
import com.lazeroX.ore_craft.item.EmeraldCoalItem;
import com.lazeroX.ore_craft.item.EmeraldNuggetItem;
import com.lazeroX.ore_craft.item.MinerBadgeItem;
import com.lazeroX.ore_craft.item.MiningTntBlockItem;
import com.lazeroX.ore_craft.item.RadiantDiamondHeartItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 矿石工艺模组的入口类。
 *
 * <p>该类集中声明模组内容，并负责把方块、物品、状态效果、药水和创造模式选项卡
 * 注册到 NeoForge。游戏运行期间需要处理的酿造与掉落事件也从这里绑定。</p>
 */
@Mod(Ore_craft.MODID)
public class Ore_craft {
    /** 模组在注册表和资源文件中使用的命名空间。 */
    public static final String MODID = "ore_craft";

    /** 采矿时运药水的基础持续时间：2 分钟。 */
    private static final int MINING_FORTUNE_DURATION_TICKS = 20 * 60 * 2;

    /** 本模组方块的延迟注册器。 */
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** 本模组物品与方块物品的延迟注册器。 */
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    /** 本模组状态效果的延迟注册器。 */
    private static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, MODID);

    /** 本模组药水类型的延迟注册器。 */
    private static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(BuiltInRegistries.POTION, MODID);

    /** 本模组实体类型的延迟注册器。 */
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MODID);

    /** 本模组创造模式选项卡的延迟注册器。 */
    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    /** 本模组容器菜单的延迟注册器。 */
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);

    /** 矿质转化桌方块注册项。 */
    public static final DeferredBlock<OreConversionTableBlock> ORE_CONVERSION_TABLE =
            BLOCKS.register("ore_conversion_table", () -> new OreConversionTableBlock(
                    BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops()
                            .sound(SoundType.STONE).noOcclusion().lightLevel(state -> 8)));
    /** 矿质转化桌对应的可放置物品。 */
    public static final DeferredItem<?> ORE_CONVERSION_TABLE_ITEM =
            ITEMS.registerSimpleBlockItem(ORE_CONVERSION_TABLE);
    /** 矿质转化桌菜单类型。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreConversionMenu>> ORE_CONVERSION_MENU =
            MENU_TYPES.register("ore_conversion_table", () -> IMenuTypeExtension.create(OreConversionMenu::new));

    /** 绿宝石煤炭块，可作为方块放置并提供高效燃料。 */
    public static final DeferredBlock<Block> EMERALD_COAL_BLOCK =
            BLOCKS.registerSimpleBlock("emerald_coal_block", BlockBehaviour.Properties.of()
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE));

    /** 点燃后收集全部爆炸掉落的采矿 TNT 方块。 */
    public static final DeferredBlock<MiningTntBlock> MINING_TNT_BLOCK =
            BLOCKS.register("mining_tnt", () -> new MiningTntBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.TNT)
            ));

    /** 绿宝石煤炭物品。 */
    public static final DeferredItem<EmeraldCoalItem> EMERALD_COAL =
            ITEMS.register("emerald_coal", () -> new EmeraldCoalItem(new Item.Properties()));

    /** 用于合成绿宝石煤炭的绿宝石粒。 */
    public static final DeferredItem<EmeraldNuggetItem> EMERALD_NUGGET =
            ITEMS.register("emerald_nugget", () -> new EmeraldNuggetItem(new Item.Properties()));

    /** 与绿宝石煤炭块关联的方块物品。 */
    public static final DeferredItem<EmeraldCoalBlockItem> EMERALD_COAL_BLOCK_ITEM =
            ITEMS.register("emerald_coal_block", () ->
                    new EmeraldCoalBlockItem(EMERALD_COAL_BLOCK.get(), new Item.Properties()));

    /** 矿工徽章，放在玩家物品栏中时定期修复装备。 */
    public static final DeferredItem<MinerBadgeItem> MINER_BADGE =
            ITEMS.register("miner_badge", () -> new MinerBadgeItem(new Item.Properties().stacksTo(1)));

    /** 辉耀钻石之心，使用后给予玩家急迫 IV。 */
    public static final DeferredItem<RadiantDiamondHeartItem> RADIANT_DIAMOND_HEART =
            ITEMS.register("radiant_diamond_heart", () ->
                    new RadiantDiamondHeartItem(new Item.Properties().durability(6).stacksTo(1)));

    /** 可以接受时运附魔的采矿 TNT 方块物品。 */
    public static final DeferredItem<MiningTntBlockItem> MINING_TNT_ITEM =
            ITEMS.register("mining_tnt", () -> new MiningTntBlockItem(
                    MINING_TNT_BLOCK.get(),
                    new Item.Properties().stacksTo(64)
            ));

    /** 采矿 TNT 点燃后使用的同步实体类型。 */
    public static final DeferredHolder<EntityType<?>, EntityType<MiningPrimedTntEntity>> MINING_TNT_ENTITY =
            ENTITY_TYPES.register("mining_tnt", () -> EntityType.Builder
                    .<MiningPrimedTntEntity>of(MiningPrimedTntEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(0.98F, 0.98F)
                    .eyeHeight(0.15F)
                    .clientTrackingRange(10)
                    .updateInterval(10)
                    .build(MODID + ":mining_tnt"));

    /** 可与工具时运附魔叠加的采矿时运状态效果。 */
    public static final DeferredHolder<MobEffect, MobEffect> MINING_FORTUNE_EFFECT =
            MOB_EFFECTS.register("mining_fortune", MiningFortuneEffect::new);

    /** I 级采矿时运药水，效果等同于额外增加时运 II。 */
    public static final DeferredHolder<Potion, Potion> MINING_FORTUNE_POTION =
            POTIONS.register("mining_fortune", () -> new Potion(
                    "ore_craft.mining_fortune",
                    new MobEffectInstance(MINING_FORTUNE_EFFECT, MINING_FORTUNE_DURATION_TICKS, 0)
            ));

    /** II 级采矿时运药水，效果等同于额外增加时运 III。 */
    public static final DeferredHolder<Potion, Potion> STRONG_MINING_FORTUNE_POTION =
            POTIONS.register("strong_mining_fortune", () -> new Potion(
                    "ore_craft.strong_mining_fortune",
                    new MobEffectInstance(MINING_FORTUNE_EFFECT, MINING_FORTUNE_DURATION_TICKS, 1)
            ));

    /** “矿石工艺”创造模式选项卡，收录本模组的全部物品和药水。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ORE_CRAFT_TAB =
            CREATIVE_MODE_TABS.register("ore_craft", () -> CreativeModeTab.builder()
                    .title(Component.translatable("creativetab.ore_craft.ore_craft"))
                    .icon(() -> MINER_BADGE.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // 注册器中的普通物品和方块物品统一加入选项卡。
                        ITEMS.getEntries().forEach(item -> output.accept(item.get()));

                        // 药水不是独立物品注册项，需要按药水内容手动创建并加入选项卡。
                        output.accept(PotionContents.createItemStack(Items.POTION, MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.SPLASH_POTION, MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.POTION, STRONG_MINING_FORTUNE_POTION));
                        output.accept(PotionContents.createItemStack(Items.SPLASH_POTION, STRONG_MINING_FORTUNE_POTION));
                    })
                    .build());

    /**
     * 创建模组入口并挂载全部注册器与游戏事件监听器。
     *
     * @param modEventBus 当前模组专用的注册和生命周期事件总线
     */
    public Ore_craft(IEventBus modEventBus) {
        // 延迟注册器必须挂到模组事件总线，内容才会在正确的注册阶段提交。
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        POTIONS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
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
