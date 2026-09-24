package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
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

    /** 矿质附魔台的外观预览菜单类型。 */
    public static final DeferredHolder<MenuType<?>, MenuType<OreEnchantingMenu>> ORE_ENCHANTING_MENU =
            MENU_TYPES.register("ore_enchanting_table", () -> IMenuTypeExtension.create(OreEnchantingMenu::new));

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
