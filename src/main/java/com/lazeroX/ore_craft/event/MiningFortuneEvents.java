package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.register.ModEffects;
import com.lazeroX.ore_craft.register.ModPotions;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 采矿时运效果相关的游戏事件处理器。
 *
 * <p>本类负责注册两级药水配方、限制药水只能保持瓶装或喷溅型，以及在方块掉落时
 * 将状态效果提供的额外时运收益叠加到工具原有的时运收益上。</p>
 */
public final class MiningFortuneEvents {
    /** 酿造台中药水瓶槽位的数量。 */
    private static final int BREWING_BOTTLE_SLOTS = 3;

    /** 酿造台原料槽在 {@link PotionBrewEvent.Pre} 容器中的索引。 */
    private static final int BREWING_INGREDIENT_SLOT = 3;

    /** 工具类不允许创建实例。 */
    private MiningFortuneEvents() {
    }

    /**
     * 注册采矿时运药水的两级酿造转换。
     *
     * @param event NeoForge 提供的酿造配方注册事件
     */
    public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
        // 浓稠的药水加绿宝石，酿成提供额外时运 II 的 I 级药水。
        event.getBuilder().addMix(
                Potions.THICK,
                Items.EMERALD,
                ModPotions.MINING_FORTUNE_POTION
        );

        // 红石粉只提高药水等级；火药继续沿用原版逻辑，将瓶装药水转为喷溅型。
        event.getBuilder().addMix(
                ModPotions.MINING_FORTUNE_POTION,
                Items.REDSTONE,
                ModPotions.STRONG_MINING_FORTUNE_POTION
        );
    }

    /**
     * 在酿造结果生成前约束采矿时运药水的形态。
     *
     * <p>自定义药水只允许瓶装和喷溅型，因此这里会阻止龙息生成滞留药水。
     * I 级到 II 级的升级由红石粉配方处理，火药则完全保留原版的喷溅药水转换。</p>
     *
     * @param event 酿造完成前触发的事件
     */
    public static void onPotionBrew(PotionBrewEvent.Pre event) {
        ItemStack ingredient = event.getItem(BREWING_INGREDIENT_SLOT);

        // 禁止任一级采矿时运药水通过龙息继续转化为滞留药水。
        if (ingredient.is(Items.DRAGON_BREATH) && containsMiningFortunePotion(event)) {
            event.setCanceled(true);
            return;
        }

        // 同时封住“滞留型浓稠药水 + 绿宝石”这条间接生成滞留药水的路径。
        if (ingredient.is(Items.EMERALD) && containsLingeringPotion(event, Potions.THICK)) {
            event.setCanceled(true);
            return;
        }

        // 即使通过命令等方式获得滞留型 I 级药水，也不允许用红石粉升级为 II 级。
        if (ingredient.is(Items.REDSTONE)
                && containsLingeringPotion(event, ModPotions.MINING_FORTUNE_POTION)) {
            event.setCanceled(true);
        }
    }

    /**
     * 玩家带有采矿时运效果时，在原工具掉落结果上叠加一次独立的时运增量。
     *
     * <p>I 级效果提供一次时运 II 增量，II 级效果提供一次时运 III 增量。独立计算
     * 可以避开部分原版战利品表将超过 III 的附魔等级封顶的问题，同时不会修改玩家
     * 真正持有的工具。</p>
     *
     * @param event NeoForge 的方块掉落事件
     */
    public static void onBlockDrops(BlockDropsEvent event) {
        // 仅处理玩家使用实际工具挖掘产生的掉落，避免影响爆炸等其他掉落来源。
        if (!(event.getBreaker() instanceof Player player) || event.getTool().isEmpty()) {
            return;
        }

        MobEffectInstance effect = player.getEffect(ModEffects.MINING_FORTUNE_EFFECT);
        if (effect == null) {
            return;
        }

        // 从当前世界的动态注册表中取得时运附魔，兼容数据包对注册表的管理。
        Holder<Enchantment> fortune = event.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);

        int enchantmentLevel = event.getTool().getEnchantmentLevel(fortune);

        // 放大等级从 0 开始，所以 I、II 级效果分别换算为额外时运 II、III。
        int effectFortuneLevel = effect.getAmplifier() + 2;
        ItemStack effectTool = copyWithFortuneLevel(event.getTool(), fortune, effectFortuneLevel);

        if (event.getState().getBlock().getLootTable() == BuiltInLootTables.EMPTY) {
            return;
        }

        LootTable lootTable = event.getLevel()
                .getServer()
                .reloadableRegistries()
                .getLootTable(event.getState().getBlock().getLootTable());
        long bonusSeed = event.getLevel().getRandom().nextLong();
        List<ItemStack> effectDrops = calculateDrops(event, player, effectTool, lootTable, bonusSeed);

        if (enchantmentLevel == 0) {
            // 工具没有时运时，直接采用状态效果对应的完整时运结果。
            replaceDrops(event, effectDrops);
            return;
        }

        // 工具已有时运时，保留原掉落，并只追加状态效果相对于无时运结果产生的差值。
        ItemStack baselineTool = copyWithFortuneLevel(event.getTool(), fortune, 0);
        List<ItemStack> baselineDrops = calculateDrops(event, player, baselineTool, lootTable, bonusSeed);
        applyDropDifference(event, baselineDrops, effectDrops);
    }

    /**
     * 创建工具副本并将副本的时运等级设置为指定值。
     *
     * @param originalTool 玩家实际使用的工具
     * @param fortune 时运附魔注册项
     * @param level 副本需要使用的时运等级，传入 0 时移除时运
     * @return 不会影响原工具的临时工具副本
     */
    private static ItemStack copyWithFortuneLevel(
            ItemStack originalTool,
            Holder<Enchantment> fortune,
        int level
    ) {
        ItemStack copy = originalTool.copy();
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(
                EnchantmentHelper.getEnchantmentsForCrafting(copy)
        );
        enchantments.set(fortune, level);
        EnchantmentHelper.setEnchantments(copy, enchantments.toImmutable());
        return copy;
    }

    /**
     * 使用固定随机种子运行一次方块战利品表。
     *
     * <p>基准工具和效果工具使用相同种子，得到的差异只来自时运等级，而不是两次
     * 随机抽取本身的不同。</p>
     *
     * @param event 当前方块掉落事件
     * @param player 挖掘方块的玩家
     * @param tool 用于本次计算的临时工具
     * @param lootTable 当前方块的战利品表
     * @param seed 两次对照计算共用的随机种子
     * @return 战利品表生成的物品栈
     */
    private static List<ItemStack> calculateDrops(
            BlockDropsEvent event,
            Player player,
            ItemStack tool,
            LootTable lootTable,
            long seed
    ) {
        LootParams params = new LootParams.Builder(event.getLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(event.getPos()))
                .withParameter(LootContextParams.TOOL, tool)
                .withParameter(LootContextParams.BLOCK_STATE, event.getState())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, event.getBlockEntity())
                .create(LootContextParamSets.BLOCK);

        return lootTable.getRandomItems(params, RandomSource.create(seed));
    }

    /**
     * 把效果时运相对于无时运基准产生的掉落差值应用到原始掉落中。
     *
     * @param event 当前方块掉落事件，其中已保存工具附魔生成的原始掉落
     * @param baselineDrops 无时运工具使用固定种子生成的基准掉落
     * @param effectDrops Buff 时运工具使用相同种子生成的效果掉落
     */
    private static void applyDropDifference(
            BlockDropsEvent event,
            List<ItemStack> baselineDrops,
            List<ItemStack> effectDrops
    ) {
        List<ItemStack> additions = subtractDrops(effectDrops, baselineDrops);
        List<ItemStack> removals = subtractDrops(baselineDrops, effectDrops);

        // 转换型掉落必须先确认原结果中存在待替换物，避免凭空复制物品。
        if (!canRemoveDrops(event.getDrops(), removals)) {
            return;
        }

        removeDrops(event.getDrops(), removals);
        addDrops(event, additions);
    }

    /**
     * 计算两个物品栈列表按物品及组件匹配后的正差集。
     *
     * @param source 被减列表
     * @param itemsToSubtract 需要从被减列表中扣除的物品
     * @return 扣除后仍剩余的物品栈副本
     */
    private static List<ItemStack> subtractDrops(
            List<ItemStack> source,
            List<ItemStack> itemsToSubtract
    ) {
        List<ItemStack> remaining = new ArrayList<>();
        source.forEach(stack -> remaining.add(stack.copy()));

        for (ItemStack subtraction : itemsToSubtract) {
            int countToSubtract = subtraction.getCount();

            for (ItemStack candidate : remaining) {
                if (countToSubtract == 0
                        || !ItemStack.isSameItemSameComponents(candidate, subtraction)) {
                    continue;
                }

                int removed = Math.min(candidate.getCount(), countToSubtract);
                candidate.shrink(removed);
                countToSubtract -= removed;
            }
        }

        remaining.removeIf(ItemStack::isEmpty);
        return remaining;
    }

    /**
     * 检查原始掉落实体是否足够完成差值中的移除操作。
     *
     * @param drops 当前事件中的掉落实体
     * @param removals 需要移除的物品栈
     * @return 所有待移除物品都存在时返回 {@code true}
     */
    private static boolean canRemoveDrops(List<ItemEntity> drops, List<ItemStack> removals) {
        List<ItemStack> available = new ArrayList<>();
        drops.forEach(entity -> available.add(entity.getItem().copy()));
        return subtractDrops(removals, available).isEmpty();
    }

    /**
     * 从事件的掉落实体中扣除指定物品。
     *
     * @param drops 当前事件中的掉落实体
     * @param removals 需要扣除的物品栈
     */
    private static void removeDrops(List<ItemEntity> drops, List<ItemStack> removals) {
        for (ItemStack removal : removals) {
            int countToRemove = removal.getCount();
            Iterator<ItemEntity> iterator = drops.iterator();

            while (iterator.hasNext() && countToRemove > 0) {
                ItemStack candidate = iterator.next().getItem();
                if (!ItemStack.isSameItemSameComponents(candidate, removal)) {
                    continue;
                }

                int removed = Math.min(candidate.getCount(), countToRemove);
                candidate.shrink(removed);
                countToRemove -= removed;

                if (candidate.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * 用新的物品栈完整替换当前事件的掉落。
     *
     * @param event 当前方块掉落事件
     * @param drops 新的掉落物品栈
     */
    private static void replaceDrops(BlockDropsEvent event, List<ItemStack> drops) {
        event.getDrops().clear();
        addDrops(event, drops);
    }

    /**
     * 将物品栈包装成待生成的物品实体并加入事件。
     *
     * @param event 当前方块掉落事件
     * @param drops 需要加入的物品栈
     */
    private static void addDrops(BlockDropsEvent event, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemEntity itemEntity = new ItemEntity(
                    event.getLevel(),
                    event.getPos().getX() + 0.5,
                    event.getPos().getY() + 0.5,
                    event.getPos().getZ() + 0.5,
                    drop
            );
            itemEntity.setDefaultPickUpDelay();
            event.getDrops().add(itemEntity);
        }
    }

    /**
     * 检查三个瓶槽中是否包含任一级采矿时运药水。
     *
     * @param event 当前酿造事件
     * @return 包含 I 级或 II 级采矿时运药水时返回 {@code true}
     */
    private static boolean containsMiningFortunePotion(PotionBrewEvent.Pre event) {
        for (int slot = 0; slot < BREWING_BOTTLE_SLOTS; slot++) {
            ItemStack stack = event.getItem(slot);
            if (hasPotion(stack, ModPotions.MINING_FORTUNE_POTION)
                    || hasPotion(stack, ModPotions.STRONG_MINING_FORTUNE_POTION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查三个瓶槽中是否包含指定内容的滞留药水。
     *
     * @param event 当前酿造事件
     * @param potion 要匹配的药水内容
     * @return 找到匹配的滞留药水时返回 {@code true}
     */
    private static boolean containsLingeringPotion(PotionBrewEvent.Pre event, Holder<Potion> potion) {
        for (int slot = 0; slot < BREWING_BOTTLE_SLOTS; slot++) {
            ItemStack stack = event.getItem(slot);
            if (stack.is(Items.LINGERING_POTION) && hasPotion(stack, potion)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断物品栈的数据组件是否保存了指定药水。
     *
     * @param stack 需要检查的药水物品栈
     * @param potion 目标药水
     * @return 药水内容与目标相同时返回 {@code true}
     */
    private static boolean hasPotion(ItemStack stack, Holder<Potion> potion) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(potion);
    }
}
