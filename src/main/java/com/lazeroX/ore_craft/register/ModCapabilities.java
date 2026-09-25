package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.block.entity.OreMachineItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 为矿质机器注册 NeoForge 方块物品能力，使其他模组的管道能够发现真实库存。
 * 面向不同方向的包装器沿用漏斗规则，且不会暴露虚拟学习物品槽。
 */
public final class ModCapabilities {
    /** 注册类只提供静态生命周期方法，不应创建实例。 */
    private ModCapabilities() {}

    /**
     * 在模组总线的能力注册阶段关联两种方块实体与物品处理器。
     *
     * @param event NeoForge 能力注册事件
     */
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.ORE_CONVERTER.get(),
                (converter, side) -> new OreMachineItemHandler(converter, side));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.ORE_CONVERSION_MACHINE.get(),
                (machine, side) -> new OreMachineItemHandler(machine, side));
    }
}
