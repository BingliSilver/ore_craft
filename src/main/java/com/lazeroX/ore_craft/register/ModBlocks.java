package com.lazeroX.ore_craft.register;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.block.MiningTntBlock;
import com.lazeroX.ore_craft.block.OreConversionTableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 集中声明和注册本模组的方块。 */
public final class ModBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Ore_craft.MODID);

    /** 矿质转化桌方块。 */
    public static final DeferredBlock<OreConversionTableBlock> ORE_CONVERSION_TABLE =
            BLOCKS.register("ore_conversion_table", () -> new OreConversionTableBlock(
                    BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops()
                            .sound(SoundType.STONE).noOcclusion().lightLevel(state -> 8)));

    /** 可作为高效燃料的绿宝石煤炭块。 */
    public static final DeferredBlock<Block> EMERALD_COAL_BLOCK =
            BLOCKS.registerSimpleBlock("emerald_coal_block", BlockBehaviour.Properties.of()
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE));

    /** 点燃后收集爆炸掉落物的采矿 TNT。 */
    public static final DeferredBlock<MiningTntBlock> MINING_TNT_BLOCK =
            BLOCKS.register("mining_tnt", () -> new MiningTntBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.TNT)
            ));

    private ModBlocks() {}

    /**
     * 将方块延迟注册器挂载到模组事件总线。
     *
     * @param modEventBus 模组事件总线
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
