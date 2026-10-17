package com.lazeroX.ore_craft.loot;

import com.lazeroX.ore_craft.register.ModEffects;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge 1.20.1 的采矿时运掉落入口，只作用于玩家用工具挖掘的方块战利品。
 * 通过同种子对照计算独立时运增量，保留原工具附魔并避免超过时运 III 后被战利品公式封顶。
 */
public final class MiningFortuneLootModifier extends LootModifier {
    /** 全局战利品修改器的 JSON 编解码器；条件由数据包提供。 */
    public static final Codec<MiningFortuneLootModifier> CODEC = RecordCodecBuilder.create(instance ->
            codecStart(instance).apply(instance, MiningFortuneLootModifier::new));

    /** 从数据包条件创建修改器，生命周期跟随战利品数据重载。 */
    public MiningFortuneLootModifier(LootItemCondition[] conditions) { super(conditions); }

    /** 返回 Forge 用来读取此修改器的编解码器。 */
    @Override
    public Codec<? extends IGlobalLootModifier> codec() { return CODEC; }

    /** 计算药水提供的时运收益，只修改生成列表，不修改玩家工具或世界方块。 */
    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generated, LootContext context) {
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (state == null || tool == null || tool.isEmpty()
                || !(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof Player player)
                || state.getBlock().getLootTable().equals(BuiltInLootTables.EMPTY)) return generated;
        // 只处理该方块的根战利品表，避免嵌套引用的子表重复应用增量。
        if (!state.getBlock().getLootTable().equals(context.getQueriedLootTableId())) return generated;
        MobEffectInstance effect = player.getEffect(ModEffects.MINING_FORTUNE_EFFECT.get());
        if (effect == null) return generated;
        LootTable table = context.getLevel().getServer().getLootData().getLootTable(state.getBlock().getLootTable());
        // Builder 将零种子视为未指定；固定替换零，保证两次对照始终使用相同随机序列。
        long randomSeed = context.getRandom().nextLong();
        long seed = randomSeed == 0L ? 1L : randomSeed;
        List<ItemStack> bonus = calculateDrops(context, withFortune(tool, effect.getAmplifier() + 2), table, seed);
        // 无时运工具也按增量修改原列表，保留其他全局战利品修改器已追加的物品。
        List<ItemStack> baseline = calculateDrops(context, withFortune(tool, 0), table, seed);
        List<ItemStack> additions = subtract(bonus, baseline);
        List<ItemStack> removals = subtract(baseline, bonus);
        // 转换型掉落必须存在足够的被替换物，才能增加新物品，避免复制。
        if (!subtract(removals, generated).isEmpty()) return generated;
        List<ItemStack> remaining = subtract(generated, removals);
        generated.clear();
        generated.addAll(remaining);
        generated.addAll(additions);
        return generated;
    }

    /** 复制原工具，仅设置或移除副本的时运，保留精准采集等其他附魔。 */
    private static ItemStack withFortune(ItemStack original, int level) {
        ItemStack copy = original.copy();
        Map<Enchantment, Integer> enchantments = new HashMap<>(EnchantmentHelper.getEnchantments(copy));
        if (level == 0) enchantments.remove(Enchantments.BLOCK_FORTUNE);
        else enchantments.put(Enchantments.BLOCK_FORTUNE, level);
        EnchantmentHelper.setEnchantments(enchantments, copy);
        return copy;
    }

    /** 使用相同随机种子重算原表；raw 接口跳过全局修改器，防止递归和重复施加其他模组加成。 */
    private static List<ItemStack> calculateDrops(LootContext context, ItemStack tool, LootTable table, long seed) {
        LootParams params = new LootParams.Builder(context.getLevel())
                .withParameter(LootContextParams.ORIGIN, context.getParam(LootContextParams.ORIGIN))
                .withParameter(LootContextParams.BLOCK_STATE, context.getParam(LootContextParams.BLOCK_STATE))
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, context.getParamOrNull(LootContextParams.THIS_ENTITY))
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, context.getParamOrNull(LootContextParams.BLOCK_ENTITY))
                .create(LootContextParamSets.BLOCK);
        List<ItemStack> drops = new ArrayList<>();
        table.getRandomItemsRaw(new LootContext.Builder(params).withOptionalRandomSeed(seed).create(null), drops::add);
        return drops;
    }

    /** 按物品及完整 NBT 计算正差集；所有结果均为副本，源列表不会被消耗。 */
    private static List<ItemStack> subtract(List<ItemStack> source, List<ItemStack> subtractions) {
        List<ItemStack> remaining = new ArrayList<>();
        source.forEach(stack -> remaining.add(stack.copy()));
        for (ItemStack subtraction : subtractions) {
            int count = subtraction.getCount();
            for (ItemStack candidate : remaining) {
                if (count == 0 || !ItemStack.isSameItemSameTags(candidate, subtraction)) continue;
                int removed = Math.min(candidate.getCount(), count);
                candidate.shrink(removed);
                count -= removed;
            }
        }
        remaining.removeIf(ItemStack::isEmpty);
        return remaining;
    }
}
