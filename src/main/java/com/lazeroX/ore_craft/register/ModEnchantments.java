package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.enchantment.EnderContainerEnchantment;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 管理模组附魔的注册生命周期；服务端规则与客户端显示共享同一注册 ID。 */
public final class ModEnchantments {
    /** 附魔延迟注册器，在模组构造阶段挂载事件总线后由 Forge 初始化。 */
    private static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(Registries.ENCHANTMENT, Ore_craft.MODID);

    /** 末影容器：穿戴任意一件带有此附魔的盔甲，即可为随身道具接通全局 ME。 */
    public static final RegistryObject<EnderContainerEnchantment> ENDER_CONTAINER =
            ENCHANTMENTS.register("ender_container", EnderContainerEnchantment::new);

    /** 注册工具类不创建实例。 */
    private ModEnchantments() {}

    /** @param modEventBus 模组事件总线，附魔必须在物品使用或菜单查询之前完成注册 */
    public static void register(IEventBus modEventBus) { ENCHANTMENTS.register(modEventBus); }
}
