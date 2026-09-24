package com.lazeroX.ore_craft.gametest;

import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.recipe.OreContainerUpgradeRecipe;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

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
        helper.setBlock(relative, ModBlocks.ORE_CONVERSION_TABLE.get());
        BlockPos pos = helper.absolutePos(relative);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        OreConversionMenu menu = new OreConversionMenu(1, player.getInventory(), pos) {
            @Override public void sync(ServerPlayer ignored) { /* 模拟玩家没有协商客户端载荷通道。 */ }
        };
        player.containerMenu = menu;
        OreConversionSavedData data = OreConversionSavedData.get(player);

        menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 2));
        menu.quickMoveStack(player, 0);
        helper.assertTrue(data.account(player).balance() == 16384, "Two diamonds must credit 16384 ME");
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "Deposit must consume the input");
        helper.assertTrue(data.account(player).knows(BuiltInRegistries.ITEM.getKey(Items.DIAMOND)),
                "Deposit must learn the item");

        menu.extract(player, BuiltInRegistries.ITEM.getKey(Items.DIAMOND), 1);
        helper.assertTrue(data.account(player).balance() == 8192, "Extraction must debit exactly 8192 ME");
        helper.assertTrue(menu.getCarried().is(Items.DIAMOND) && menu.getCarried().getCount() == 1,
                "Extraction must put one diamond on the cursor");

        menu.setCarried(ItemStack.EMPTY);
        menu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
        menu.quickMoveStack(player, 1);
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

    /** 验证容器容量、存储进度及转化桌两个交互口的 ME 守恒。 */
    @GameTest(templateNamespace = Ore_craft.MODID, template = "empty")
    @SuppressWarnings("removal")
    public static void oreContainerTransfer(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, ModBlocks.ORE_CONVERSION_TABLE.get());
        BlockPos pos = helper.absolutePos(relative);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        OreConversionMenu menu = new OreConversionMenu(2, player.getInventory(), pos) {
            @Override public void sync(ServerPlayer ignored) { /* 测试不依赖客户端网络通道。 */ }
        };
        player.containerMenu = menu;
        OreConversionSavedData data = OreConversionSavedData.get(player);
        long initial = data.account(player).balance();
        OreContainerItem copper = ModItems.COPPER_ORE_CONTAINER.get();
        OreContainerItem iron = ModItems.IRON_ORE_CONTAINER.get();
        helper.assertTrue(copper.capacity() == 10_240 && iron.capacity() == 40_960
                        && ModItems.GOLD_ORE_CONTAINER.get().capacity() == 163_840
                        && ModItems.DIAMOND_ORE_CONTAINER.get().capacity() == 655_360,
                "Container capacities must quadruple at each tier");
        helper.assertTrue(new ItemStack(copper).getMaxStackSize() == 1
                        && new ItemStack(copper).getMaxDamage() == 0
                        && new ItemStack(ModItems.ENDER_ORE_CONTAINER.get()).getMaxStackSize() == 64
                        && !new ItemStack(ModItems.ENDER_ORE_CONTAINER.get()).isBarVisible(),
                "Storage bar must not use durability, and Ender container must not show a bar");

        ItemStack filled = new ItemStack(copper);
        copper.setStoredMe(filled, 7_000);
        helper.assertTrue(copper.storedMe(filled) == 7_000, "Container must retain stored ME data");
        helper.assertTrue(menu.stillValid(player), "Container menu must be valid during transfer");
        menu.getSlot(OreConversionMenu.ME_INPUT_SLOT).set(filled);
        helper.assertTrue(copper.storedMe(menu.getSlot(OreConversionMenu.ME_INPUT_SLOT).getItem()) == 0,
                "Input port must empty the container");
        helper.assertTrue(data.account(player).balance() == initial + 7_000,
                "Input port must credit exactly the removed ME");

        data.credit(player, 40_960);
        menu.getSlot(OreConversionMenu.ME_OUTPUT_SLOT).set(new ItemStack(iron));
        ItemStack charged = menu.getSlot(OreConversionMenu.ME_OUTPUT_SLOT).getItem();
        helper.assertTrue(iron.storedMe(charged) == 40_960 && iron.getBarWidth(charged) == 13,
                "Output port must stop at capacity and show a full bar");
        helper.assertTrue(data.account(player).balance() == initial + 7_000,
                "Output port must debit exactly the stored ME");
        helper.assertTrue(ModItems.ENDER_ORE_CONTAINER.get().accountMe(player) == initial + 7_000,
                "Ender container must read the player's global account directly");
        helper.succeed();
    }

    /** 验证升级合成不会清空中心旧容器的 ME。 */
    @GameTest(templateNamespace = Ore_craft.MODID, template = "empty")
    public static void oreContainerUpgrade(GameTestHelper helper) {
        OreContainerItem copper = ModItems.COPPER_ORE_CONTAINER.get();
        ItemStack center = new ItemStack(copper);
        copper.setStoredMe(center, 8_000);
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.IRON_BLOCK), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_BLOCK),
                new ItemStack(Items.IRON_INGOT), center, new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.IRON_BLOCK), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_BLOCK)));
        var recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .map(holder -> holder.value()).orElse(null);
        helper.assertTrue(recipe instanceof OreContainerUpgradeRecipe, "Iron upgrade recipe must retain container data");
        ItemStack result = recipe.assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(ModItems.IRON_ORE_CONTAINER.get())
                        && ModItems.IRON_ORE_CONTAINER.get().storedMe(result) == 8_000,
                "Upgrade must preserve stored ME");
        helper.succeed();
    }
}
