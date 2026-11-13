package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.menu.OreCraftingMenu;
import com.lazeroX.ore_craft.menu.OreLearningMenu;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.menu.OreConverterMenu;
import com.lazeroX.ore_craft.menu.OreExperienceConverterMenu;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.menu.MineralTimeScepterMenu;
import com.lazeroX.ore_craft.menu.MineralTeleportScepterMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** 集中声明和注册本模组的菜单类型。 */
public final class ModMenus {
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, Ore_craft.MODID);

    /** 基础合成台菜单，服务端和客户端均创建交易容器槽及三阶合成区。 */
    public static final Supplier<MenuType<OreCraftingMenu>> ORE_CRAFTING_MENU =
            MENU_TYPES.register("ore_crafting_table", () -> IMenuTypeExtension.create(
                    (id, inventory, extra) -> new OreCraftingMenu(id, inventory, extra.readBlockPos(), true)));

    /** Plus 合成台菜单，通过独立注册类型固定其账户充提槽布局。 */
    public static final Supplier<MenuType<OreCraftingMenu>> ORE_CRAFTING_PLUS_MENU =
            MENU_TYPES.register("ore_crafting_table_plus", () -> IMenuTypeExtension.create(
                    (id, inventory, extra) -> new OreCraftingMenu(id, inventory, extra.readBlockPos(), false)));

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

    /** 经验转化器的单个供能槽及开关、进度、完整 ME 余额同步菜单。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreExperienceConverterMenu>> ORE_EXPERIENCE_CONVERTER_MENU =
            MENU_TYPES.register("ore_experience_converter", () -> IMenuTypeExtension.create(OreExperienceConverterMenu::new));

    /** 权杖倍率与施加时间的无物品槽设置菜单。 */
    public static final DeferredHolder<MenuType<?>, MenuType<MineralTimeScepterMenu>> MINERAL_TIME_SCEPTER_MENU =
            MENU_TYPES.register("mineral_time_scepter", () -> IMenuTypeExtension.create(MineralTimeScepterMenu::new));

    /** 传送权杖的命名及坐标列表菜单，额外数据包含持有手和服务端捕获的位置。 */
    public static final Supplier<MenuType<MineralTeleportScepterMenu>> MINERAL_TELEPORT_SCEPTER_MENU =
            MENU_TYPES.register("mineral_teleport_scepter", () -> IMenuTypeExtension.create(MineralTeleportScepterMenu::new));

    /** 菜单类型由注册生命周期管理，不创建工具类实例。 */
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
