package com.lazeroX.ore_craft.gametest;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 验证转化价格推导和转化桌交易行为的游戏测试。 */
@PrefixGameTestTemplate(false)
public final class OreConversionGameTests {
    /** 测试类不允许创建实例。 */
    private OreConversionGameTests() {}

    /** 检查配方价格、分数价格取整和不可获得物品的过滤规则。 */
    @GameTest(templateNamespace = Ore_craft.MODID, template = "empty")
    public static void recipePrices(GameTestHelper helper) {
        helper.assertTrue(OreConversionPrices.price(Items.IRON_BLOCK).orElse(-1) == 2304,
                "Iron block must cost nine iron ingots");
        helper.assertTrue(OreConversionPrices.price(Items.OAK_PLANKS).orElse(-1) == 8,
                "Four oak planks must divide the log price conservatively");
        helper.assertTrue(OreConversionPrices.price(Items.BEDROCK).isEmpty(),
                "Unobtainable items must stay unpriced");
        helper.succeed();
    }

    /** 检查物品输入、学习、提取及鼠标持有冲突保护。 */
    @GameTest(templateNamespace = Ore_craft.MODID, template = "empty")
    @SuppressWarnings("removal")
    public static void transactions(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Ore_craft.ORE_CONVERSION_TABLE.get());
        BlockPos pos = helper.absolutePos(relative);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        OreConversionMenu menu = new OreConversionMenu(1, player.getInventory(), pos) {
            @Override public void sync(ServerPlayer ignored) { /* 模拟玩家没有协商客户端载荷通道。 */ }
        };
        player.containerMenu = menu;
        OreConversionSavedData data = OreConversionSavedData.get(player);

        menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 2));
        menu.useInventorySlot(player, 0);
        helper.assertTrue(data.account(player).balance() == 16384, "Two diamonds must credit 16384 ME");
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "Deposit must consume the input");
        helper.assertTrue(data.account(player).knows(BuiltInRegistries.ITEM.getKey(Items.DIAMOND)),
                "Deposit must learn the item");

        menu.extract(player, BuiltInRegistries.ITEM.getKey(Items.DIAMOND), 1);
        helper.assertTrue(data.account(player).balance() == 8192, "Extraction must debit exactly 8192 ME");
        helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 1,
                "Extraction must put one diamond on the cursor");

        menu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
        menu.useInventorySlot(player, 1);
        helper.assertTrue(menu.getSlot(1).getItem().getCount() == 1, "Learning must not consume the item");
        helper.assertTrue(data.account(player).knows(BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS)),
                "Learning must record the item");

        menu.setCarried(new ItemStack(Items.DIRT));
        long before = data.account(player).balance();
        menu.extract(player, BuiltInRegistries.ITEM.getKey(Items.DIAMOND), 1);
        helper.assertTrue(data.account(player).balance() == before, "An unrelated carried item must not debit ME");
        helper.assertTrue(menu.getCarried().is(Items.DIRT), "An unrelated carried item must remain on the cursor");
        helper.succeed();
    }
}
