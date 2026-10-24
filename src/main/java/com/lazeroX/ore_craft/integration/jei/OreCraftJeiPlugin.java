package com.lazeroX.ore_craft.integration.jei;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.client.OreCraftingScreen;
import com.lazeroX.ore_craft.menu.OreCraftingMenu;
import com.lazeroX.ore_craft.menu.OreCraftingPlanner;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 可选 JEI 整合入口，仅由已安装 JEI 的客户端发现和加载。
 * 常规注册、菜单及网络代码不引用本类，专用服务器和未安装 JEI 的客户端也能使用合成台。
 */
@JeiPlugin
public final class OreCraftJeiPlugin implements IModPlugin {
    /** JEI 通过无参构造创建整合实例，不持有世界或玩家数据。 */
    public OreCraftJeiPlugin() {}

    /** 返回本整合在 JEI 中的稳定标识。 */
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "crafting_table");
    }

    /** 两款合成台均能执行原版及模组的三阶合成配方，注册为合成分类的工作站。 */
    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModItems.ORE_CRAFTING_TABLE_ITEM.get()), RecipeTypes.CRAFTING);
        registration.addRecipeCatalyst(new ItemStack(ModItems.ORE_CRAFTING_TABLE_PLUS_ITEM.get()), RecipeTypes.CRAFTING);
    }

    /** 使用自定义填充处理器，让 JEI 将已学习且可支付的原料计入材料可用性。 */
    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new TransferHandler(registration.getTransferHelper()), RecipeTypes.CRAFTING);
    }

    /**
     * 为 JEI 提供缩放后的真实界面边界及物品命中，兼容目录和全部实际槽位的 R/U 查询。
     * 布局只在界面初始化后生成不可变快照，供 JEI 正确检测打开界面与窗口缩放后的变化。
     * 物品命中使用实际 GUI 坐标，避免游戏 GUI 较大时查询对象偏移。
     */
    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(OreCraftingScreen.class, ScreenProperties::create);
        // 目录不是原版 Slot，通过只读命中返回真实物品，不赋予客户端任何创建物品的能力。
        registration.addGuiContainerHandler(OreCraftingScreen.class, new IGuiContainerHandler<OreCraftingScreen>() {
            /** 统一返回目录或真实槽位下的物品，并以实际鼠标位置限定点击区域。 */
            @Override
            public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(OreCraftingScreen screen,
                                                                                       double mouseX, double mouseY) {
                ItemStack stack = screen.ingredientAt(mouseX, mouseY);
                if (stack.isEmpty()) return Optional.empty();
                return registration.getJeiHelpers().getIngredientManager().createClickableIngredient(
                        VanillaTypes.ITEM_STACK, stack, new Rect2i((int) mouseX, (int) mouseY, 1, 1), false)
                        .map(ingredient -> ingredient);
            }
        });
    }

    /**
     * JEI 配方填充适配器：预检复用库存规划，真正操作只提交服务端配方 ID。
     * 同时匹配基础和 Plus 菜单，ME 来源由菜单本身决定。
     */
    private static final class TransferHandler implements IRecipeTransferHandler<OreCraftingMenu, CraftingRecipe> {
        /** JEI 提供的错误提示工厂，只在当前客户端插件生命周期内使用。 */
        private final IRecipeTransferHandlerHelper helper;

        /** 绑定 JEI 的本地化错误提示接口。 */
        private TransferHandler(IRecipeTransferHandlerHelper helper) {
            this.helper = helper;
        }

        /** 两款合成台共用同一个菜单类。 */
        @Override
        public Class<? extends OreCraftingMenu> getContainerClass() { return OreCraftingMenu.class; }

        /** 不限制单个 MenuType，使同类的两种结算版本都能填充。 */
        @Override
        public Optional<MenuType<OreCraftingMenu>> getMenuType() { return Optional.empty(); }

        /** 只处理三阶工作台配方，不接管熔炉、切石或机器配方。 */
        @Override
        public RecipeType<CraftingRecipe> getRecipeType() { return RecipeTypes.CRAFTING; }

        /**
         * JEI 悬停加号时只检查材料及 ME；点击后由服务端重新检查并提交交易。
         * 背包材料无需学习，只有确实缺少的默认物品才计入转化费用。
         */
        @Override
        public IRecipeTransferError transferRecipe(OreCraftingMenu menu, CraftingRecipe recipe, IRecipeSlotsView slots,
                                                    Player player, boolean maximum, boolean transfer) {
            Map<ResourceLocation, Long> prices = new HashMap<>();
            for (var entry : menu.clientCatalog()) prices.put(entry.id(), entry.price());
            OreCraftingPlanner.Evaluation evaluation = menu.preview(recipe, menu.clientBalance(), stack -> {
                ItemStack plain = new ItemStack(stack.getItem());
                if (!ItemStack.isSameItemSameTags(plain, stack) || !OreConversionPrices.isPlain(plain)) return -1;
                return prices.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()), -1L);
            }, maximum);
            if (evaluation.plan() == null) {
                return helper.createUserErrorWithTooltip(Component.translatable("message.ore_craft.crafting." + evaluation.error()));
            }
            if (transfer) OreConversionNetwork.sendToServer(new OreConversionNetwork.CraftingPayload(
                    menu.containerId, recipe.getId(), maximum));
            return null;
        }
    }

    /**
     * JEI 单次查询的不可变布局快照；旧快照在界面初始化、缩放后仍保留原值。
     * JEI 通过比较前后属性决定是否重新校验和排列侧栏，因此不能在 getter 中读取可变屏幕。
     *
     * @param guiLeft 已缩放的画布左边界，单位为 Minecraft GUI 像素
     * @param guiTop 已缩放的画布上边界
     * @param guiWidth 已缩放的画布宽度，始终为正数
     * @param guiHeight 已缩放的画布高度，始终为正数
     * @param screenWidth 查询时的窗口 GUI 宽度，始终为正数
     * @param screenHeight 查询时的窗口 GUI 高度，始终为正数
     */
    private record ScreenProperties(int guiLeft, int guiTop, int guiWidth, int guiHeight,
                                    int screenWidth, int screenHeight) implements IGuiProperties {
        /** 保存已通过 create 校验的坐标和尺寸，不持有会随初始化或窗口变化的屏幕引用。 */
        private ScreenProperties {}

        /**
         * 界面初始化后生成完整布局，初始化之前按 JEI 接口约定返回 null。
         *
         * @param screen JEI 正在查询的矿质合成台界面
         * @return 当前布局的独立快照；窗口或画布尚无有效尺寸时返回 null
         */
        @Nullable
        private static ScreenProperties create(OreCraftingScreen screen) {
            // JEI 的打开事件早于 Screen.init，此时窗口宽高为零，不能缓存为有效界面。
            // 初始化完成后的下一次查询会从 null 变为快照，触发 JEI 重新校验并显示两侧栏。
            if (screen.width <= 0 || screen.height <= 0) return null;
            Rect2i area = screen.guiArea();
            if (area.getWidth() <= 0 || area.getHeight() <= 0) return null;
            return new ScreenProperties(area.getX(), area.getY(), area.getWidth(), area.getHeight(),
                    screen.width, screen.height);
        }

        /** 返回目标屏幕类型。 */
        @Override public Class<? extends Screen> getScreenClass() { return OreCraftingScreen.class; }
        /** 返回快照中的已缩放 GUI 左边界。 */
        @Override public int getGuiLeft() { return guiLeft; }
        /** 返回快照中的已缩放 GUI 上边界。 */
        @Override public int getGuiTop() { return guiTop; }
        /** 返回快照中的已缩放 GUI 宽度。 */
        @Override public int getGuiXSize() { return guiWidth; }
        /** 返回快照中的已缩放 GUI 高度。 */
        @Override public int getGuiYSize() { return guiHeight; }
        /** 返回查询时的窗口 GUI 宽度，之后调整窗口不会修改该值。 */
        @Override public int getScreenWidth() { return screenWidth; }
        /** 返回查询时的窗口 GUI 高度，之后调整窗口不会修改该值。 */
        @Override public int getScreenHeight() { return screenHeight; }
    }
}
