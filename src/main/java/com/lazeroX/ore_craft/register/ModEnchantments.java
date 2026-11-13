package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** 1.21.1 附魔由数据包加载；此处保存稳定键，使用时从当前世界查找 Holder。 */
public final class ModEnchantments {
    /** 末影容器：穿戴任意一件带有此附魔的盔甲，即可为随身道具接通全局 ME。 */
    public static final ResourceKey<Enchantment> ENDER_CONTAINER = ResourceKey.create(
            Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "ender_container"));

    /** 注册工具类不创建实例。 */
    private ModEnchantments() {}

}
