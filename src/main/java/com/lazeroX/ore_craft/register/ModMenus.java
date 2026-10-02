package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.menu.OreLearningMenu;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.menu.OreConverterMenu;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.menu.MineralTimeScepterMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册本模组的菜单类型。 */
public final class ModMenus {
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, Ore_craft.MODID);

    /** 矿质转化桌菜单类型。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreConversionMenu>> ORE_CONVERSION_MENU =
            MENU_TYPES.register("ore_conversion_table", () -> IMenuTypeExtension.create(OreConversionMenu::new));

    /** 基础转化桌菜单；独立类型确保两端都只创建交易容器槽，共用原有交易网络处理。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreConversionMenu>> ORE_CONTAINER_CONVERSION_MENU =
            MENU_TYPES.register("ore_container_conversion_table", () -> IMenuTypeExtension.create(
                    (id, inventory, extra) -> new OreConversionMenu(id, inventory, extra.readBlockPos(), true)));

    /** 学习宝典的独立菜单类型，避免进入转化桌的提取网络处理分支。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreLearningMenu>> ORE_LEARNING_MENU =
            MENU_TYPES.register("ore_learning_book", () -> IMenuTypeExtension.create(OreLearningMenu::new));

    /** 矿质附魔台的外观预览菜单类型。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreEnchantingMenu>> ORE_ENCHANTING_MENU =
            MENU_TYPES.register("ore_enchanting_table", () -> IMenuTypeExtension.create(OreEnchantingMenu::new));

    /** 矿质传输接口的原料及容器菜单。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreConverterMenu>> ORE_CONVERTER_MENU =
            MENU_TYPES.register("ore_converter", () -> IMenuTypeExtension.create(OreConverterMenu::new));

    /** 矿质转化器的容器、虚拟选择和产物菜单。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreConversionMachineMenu>> ORE_CONVERSION_MACHINE_MENU =
            MENU_TYPES.register("ore_conversion_machine", () -> IMenuTypeExtension.create(OreConversionMachineMenu::new));

    /** 权杖倍率与施加时间的无物品槽设置菜单。 */
    public static final DeferredHolder<MenuType<?>, MenuType<MineralTimeScepterMenu>> MINERAL_TIME_SCEPTER_MENU =
            MENU_TYPES.register("mineral_time_scepter", () -> IMenuTypeExtension.create(MineralTimeScepterMenu::new));

    private ModMenus() {}

    /**
     * 将菜单延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}
