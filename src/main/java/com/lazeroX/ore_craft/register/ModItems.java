package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.item.EmeraldCoalBlockItem;
import com.lazeroX.ore_craft.item.EmeraldCoalItem;
import com.lazeroX.ore_craft.item.EmeraldNuggetItem;
import com.lazeroX.ore_craft.item.MinerBadgeItem;
import com.lazeroX.ore_craft.item.MiningTntBlockItem;
import com.lazeroX.ore_craft.item.RadiantDiamondHeartItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册普通物品及方块物品。 */
public final class ModItems {
    /** 创造模式选项卡也从此注册器读取全部模组物品。 */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Ore_craft.MODID);

    /** 矿质转化桌对应的可放置物品。 */
    public static final DeferredItem<?> ORE_CONVERSION_TABLE_ITEM =
            ITEMS.registerSimpleBlockItem(ModBlocks.ORE_CONVERSION_TABLE);

    /** 绿宝石煤炭物品。 */
    public static final DeferredItem<EmeraldCoalItem> EMERALD_COAL =
            ITEMS.register("emerald_coal", () -> new EmeraldCoalItem(new Item.Properties()));

    /** 用于合成绿宝石煤炭的绿宝石粒。 */
    public static final DeferredItem<EmeraldNuggetItem> EMERALD_NUGGET =
            ITEMS.register("emerald_nugget", () -> new EmeraldNuggetItem(new Item.Properties()));

    /** 与绿宝石煤炭块关联的方块物品。 */
    public static final DeferredItem<EmeraldCoalBlockItem> EMERALD_COAL_BLOCK_ITEM =
            ITEMS.register("emerald_coal_block", () ->
                    new EmeraldCoalBlockItem(ModBlocks.EMERALD_COAL_BLOCK.get(), new Item.Properties()));

    /** 放在玩家物品栏中时定期修复装备的矿工徽章。 */
    public static final DeferredItem<MinerBadgeItem> MINER_BADGE =
            ITEMS.register("miner_badge", () -> new MinerBadgeItem(new Item.Properties().stacksTo(1)));

    /** 使用后给予玩家急迫 IV 的辉耀钻石之心。 */
    public static final DeferredItem<RadiantDiamondHeartItem> RADIANT_DIAMOND_HEART =
            ITEMS.register("radiant_diamond_heart", () ->
                    new RadiantDiamondHeartItem(new Item.Properties().durability(6).stacksTo(1)));

    /** 铜质矿质容器。 */
    public static final DeferredItem<Item> COPPER_ORE_CONTAINER =
            ITEMS.registerSimpleItem("copper_ore_container");

    /** 铁质矿质容器。 */
    public static final DeferredItem<Item> IRON_ORE_CONTAINER =
            ITEMS.registerSimpleItem("iron_ore_container");

    /** 金质矿质容器。 */
    public static final DeferredItem<Item> GOLD_ORE_CONTAINER =
            ITEMS.registerSimpleItem("gold_ore_container");

    /** 钻石矿质容器。 */
    public static final DeferredItem<Item> DIAMOND_ORE_CONTAINER =
            ITEMS.registerSimpleItem("diamond_ore_container");

    /** 末影矿质容器。 */
    public static final DeferredItem<Item> ENDER_ORE_CONTAINER =
            ITEMS.registerSimpleItem("ender_ore_container");

    /** 可以接受时运附魔的采矿 TNT 方块物品。 */
    public static final DeferredItem<MiningTntBlockItem> MINING_TNT_ITEM =
            ITEMS.register("mining_tnt", () -> new MiningTntBlockItem(
                    ModBlocks.MINING_TNT_BLOCK.get(),
                    new Item.Properties().stacksTo(64)
            ));

    private ModItems() {}

    /**
     * 将物品延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
