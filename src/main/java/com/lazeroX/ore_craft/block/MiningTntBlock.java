package com.lazeroX.ore_craft.block;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 点燃后生成采矿 TNT 实体的自定义 TNT 方块。
 *
 * <p>方块状态会保存物品上的时运等级，使附魔信息在放置、点燃和重新挖取之间保持。
 * 爆炸会按照实际保存的时运等级计算可受时运影响的方块掉落。</p>
 */
public class MiningTntBlock extends TntBlock {
    /** 保存放置物所携带时运等级的方块状态属性。 */
    public static final IntegerProperty FORTUNE_LEVEL = IntegerProperty.create("fortune", 0, 3);

    /**
     * 创建采矿 TNT 方块。
     *
     * @param properties 方块的基础属性
     */
    public MiningTntBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FORTUNE_LEVEL, 0));
    }

    /** 将物品上的时运等级写入刚放置的方块状态。 */
    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);

        Holder<Enchantment> fortune = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);
        int fortuneLevel = Math.min(3, stack.getEnchantmentLevel(fortune));

        if (fortuneLevel > 0) {
            level.setBlock(pos, state.setValue(FORTUNE_LEVEL, fortuneLevel), 3);
        }
    }

    /** 点燃方块并生成携带当前时运状态的采矿 TNT 实体。 */
    @Override
    public void onCaughtFire(
            BlockState state,
            Level level,
            BlockPos pos,
            @Nullable Direction face,
            @Nullable LivingEntity igniter
    ) {
        if (level.isClientSide()) {
            return;
        }

        MiningPrimedTntEntity primedTnt = new MiningPrimedTntEntity(
                level,
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                igniter,
                state.getValue(FORTUNE_LEVEL)
        );
        level.addFreshEntity(primedTnt);
        level.playSound(
                null,
                primedTnt.getX(),
                primedTnt.getY(),
                primedTnt.getZ(),
                SoundEvents.TNT_PRIMED,
                SoundSource.BLOCKS,
                1.0F,
                1.0F
        );
        level.gameEvent(igniter, GameEvent.PRIME_FUSE, pos);
    }

    /** 被其他爆炸波及时生成一个缩短引信的采矿 TNT 实体。 */
    @Override
    public void onBlockExploded(
            BlockState state,
            Level level,
            BlockPos pos,
            Explosion explosion
    ) {
        if (level.isClientSide()) {
            return;
        }

        // 在移除方块前读取自定义状态；原版默认实现会先替换为空气再调用 wasExploded。
        int fortuneLevel = state.getValue(FORTUNE_LEVEL);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        MiningPrimedTntEntity primedTnt = new MiningPrimedTntEntity(
                level,
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                explosion.getIndirectSourceEntity(),
                fortuneLevel
        );
        int normalFuse = primedTnt.getFuse();
        primedTnt.setFuse(level.random.nextInt(normalFuse / 4) + normalFuse / 8);
        level.addFreshEntity(primedTnt);
    }

    /**
     * 挖取方块时恢复对应的采矿 TNT 物品，并保留时运附魔等级。
     *
     * @param state 当前方块状态
     * @param params 战利品上下文构建器
     * @return 包含一个采矿 TNT 的掉落列表
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ItemStack result = new ItemStack(Ore_craft.MINING_TNT_ITEM.get());
        int fortuneLevel = state.getValue(FORTUNE_LEVEL);

        if (fortuneLevel > 0) {
            Holder<Enchantment> fortune = params.getLevel()
                    .registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.FORTUNE);
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(
                    EnchantmentHelper.getEnchantmentsForCrafting(result)
            );
            enchantments.set(fortune, fortuneLevel);
            EnchantmentHelper.setEnchantments(result, enchantments.toImmutable());
        }

        return List.of(result);
    }

    /** 将原版 TNT 的不稳定状态与自定义时运等级共同加入方块状态定义。 */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FORTUNE_LEVEL);
    }
}
