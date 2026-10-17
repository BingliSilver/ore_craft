package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.block.entity.OreMachineInventory;
import com.lazeroX.ore_craft.block.entity.OreMachineItemHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 为机器附加 Forge 物品能力，使管道和漏斗遵守相同的方向与槽位规则。 */
public final class ModCapabilities {
    /** 注册工具类不允许创建实例。 */
    private ModCapabilities() {}

    /** 在方块实体创建时附加能力；卸载时失效全部缓存，避免管道引用已销毁库存。 */
    public static void register(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!(event.getObject() instanceof OreMachineInventory inventory)) return;
        MachineProvider provider = new MachineProvider(inventory);
        event.addCapability(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "machine_inventory"), provider);
        event.addListener(provider::invalidate);
    }

    /** 每个实体独立的能力提供者，缓存六个方向和无方向的处理器。 */
    private static final class MachineProvider implements ICapabilityProvider {
        /** 前六项对应 Direction 序号，第七项为无方向访问。 */
        private final List<LazyOptional<IItemHandler>> handlers = new ArrayList<>(7);

        /** 只包装真实库存，不创建额外槽位或暴露虚拟学习物品。 */
        private MachineProvider(OreMachineInventory inventory) {
            for (Direction side : Direction.values()) {
                handlers.add(LazyOptional.of(() -> new OreMachineItemHandler(inventory, side)));
            }
            handlers.add(LazyOptional.of(() -> new OreMachineItemHandler(inventory, null)));
        }

        /** 仅响应物品能力查询；同一方向始终返回相同的可失效引用。 */
        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
            return capability == ForgeCapabilities.ITEM_HANDLER
                    ? handlers.get(side == null ? 6 : side.ordinal()).cast() : LazyOptional.empty();
        }

        /** 通知所有物流设备丢弃旧引用；方块实体重建时重新附加能力。 */
        private void invalidate() { handlers.forEach(LazyOptional::invalidate); }
    }
}
