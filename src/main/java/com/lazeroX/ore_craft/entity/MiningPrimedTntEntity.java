package com.lazeroX.ore_craft.entity;

import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采矿 TNT 点燃后的实体。
 *
 * <p>实体爆炸时仍使用原版 TNT 的伤害、击退和破坏范围，但方块销毁及掉落生成由本类
 * 接管。掉落不经过原版爆炸衰减，并在爆炸结束后存入中心附近的箱子。</p>
 */
public class MiningPrimedTntEntity extends PrimedTnt {
    /** NBT 中保存原物品时运等级的键。 */
    private static final String TAG_FORTUNE_LEVEL = "fortune_level";

    /** 寻找额外存储箱子时允许搜索的最大半径。 */
    private static final int STORAGE_SEARCH_RADIUS = 8;

    /** 点燃该 TNT 的生物，用于伤害归属与方块掉落上下文。 */
    @Nullable
    private LivingEntity miningOwner;

    /** 原方块物品携带的时运等级；正数表示按该等级计算矿物掉落。 */
    private int fortuneLevel;

    /**
     * 实体注册表使用的构造器。
     *
     * @param entityType 采矿 TNT 实体类型
     * @param level 实体所在世界
     */
    public MiningPrimedTntEntity(
            EntityType<? extends MiningPrimedTntEntity> entityType,
            Level level
    ) {
        super(entityType, level);
        setBlockState(Ore_craft.MINING_TNT_BLOCK.get().defaultBlockState());
    }

    /**
     * 创建一个已经点燃的采矿 TNT 实体。
     *
     * @param level 实体所在世界
     * @param x 初始 X 坐标
     * @param y 初始 Y 坐标
     * @param z 初始 Z 坐标
     * @param owner 点燃 TNT 的生物
     * @param fortuneLevel 原方块物品携带的时运等级
     */
    public MiningPrimedTntEntity(
            Level level,
            double x,
            double y,
            double z,
            @Nullable LivingEntity owner,
            int fortuneLevel
    ) {
        this(Ore_craft.MINING_TNT_ENTITY.get(), level);
        setPos(x, y, z);

        // 与原版 TNT 保持一致的轻微随机水平速度和向上弹起效果。
        double angle = level.random.nextDouble() * Math.PI * 2.0;
        setDeltaMovement(-Math.sin(angle) * 0.02, 0.2F, -Math.cos(angle) * 0.02);
        setFuse(80);
        xo = x;
        yo = y;
        zo = z;
        miningOwner = owner;
        this.fortuneLevel = Math.max(0, Math.min(3, fortuneLevel));
        setBlockState(Ore_craft.MINING_TNT_BLOCK.get().defaultBlockState()
                .setValue(com.lazeroX.ore_craft.block.MiningTntBlock.FORTUNE_LEVEL, this.fortuneLevel));
    }

    /** 返回点燃实体，使爆炸伤害和掉落能正确归属玩家。 */
    @Nullable
    @Override
    public LivingEntity getOwner() {
        return miningOwner;
    }

    /**
     * 按原版 TNT 规则计算破坏范围，再由本类完整收集并销毁受影响方块。
     */
    @Override
    protected void explode() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // DESTROY 让爆炸按照原版 TNT 的抗爆性规则计算真正会被破坏的方块。
        Explosion explosion = new Explosion(
                serverLevel,
                this,
                getX(),
                getY(0.0625),
                getZ(),
                4.0F,
                false,
                Explosion.BlockInteraction.DESTROY
        );

        // 保留 NeoForge 的爆炸开始事件，使其他模组仍可以取消这次爆炸。
        if (EventHooks.onExplosionStart(serverLevel, explosion)) {
            return;
        }

        // 先执行原版范围计算、实体伤害与击退，并允许爆炸事件修改受影响方块列表。
        explosion.explode();
        List<BlockPos> affectedBlocks = List.copyOf(explosion.getToBlow());

        // 清空原版待处理列表，避免原版生成散落物或按爆炸衰减丢失物品。
        // 下方会逐个销毁方块，并把完整掉落统一写入生成的箱子。
        explosion.clearToBlow();
        explosion.finalizeExplosion(true);

        List<ItemStack> collectedDrops = new ArrayList<>();
        ItemStack fortuneTool = createFortuneTool(serverLevel);
        boolean shouldDropItems = serverLevel.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);

        // 使用计算阶段保存的坐标逐个收集并销毁方块。
        for (BlockPos pos : affectedBlocks) {
            collectAndDestroyBlock(
                    serverLevel,
                    pos,
                    explosion,
                    fortuneTool,
                    shouldDropItems,
                    collectedDrops
            );
        }

        placeStorageChests(serverLevel, BlockPos.containing(getX(), getY(), getZ()), collectedDrops);
    }

    /**
     * 创建用于矿物战利品计算的临时时运钻石镐。
     *
     * @param level 当前服务端世界
     * @return 未附魔 TNT 返回空物品栈，附魔 TNT 返回具有相同附魔等级的钻石镐
     */
    private ItemStack createFortuneTool(ServerLevel level) {
        if (fortuneLevel <= 0) {
            return ItemStack.EMPTY;
        }

        Holder<Enchantment> fortune = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        EnchantmentHelper.updateEnchantments(
                tool,
                enchantments -> enchantments.set(fortune, fortuneLevel)
        );
        return tool;
    }

    /**
     * 收集一个受影响方块的容器内容与正常掉落，然后执行方块的爆炸销毁回调。
     *
     * @param level 当前服务端世界
     * @param pos 被爆炸命中的方块坐标
     * @param explosion 当前爆炸对象
     * @param fortuneTool 时运 TNT 使用的临时镐子，未附魔时为空
     * @param shouldDropItems 是否允许方块掉落
     * @param collectedDrops 汇总全部掉落的列表
     */
    private void collectAndDestroyBlock(
            ServerLevel level,
            BlockPos pos,
            Explosion explosion,
            ItemStack fortuneTool,
            boolean shouldDropItems,
            List<ItemStack> collectedDrops
    ) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (shouldDropItems && blockEntity instanceof Container container) {
            // 先清空容器，防止方块移除回调把内容再次抛到世界中造成重复掉落。
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                addCollectedDrop(collectedDrops, container.removeItemNoUpdate(slot));
            }
            container.setChanged();
        }

        if (shouldDropItems && state.canDropFromExplosion(level, pos, explosion)) {
            // 仅对适合镐子挖掘的方块提供 TNT 实际携带的时运等级。
            ItemStack lootTool = !fortuneTool.isEmpty() && state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                    ? fortuneTool
                    : ItemStack.EMPTY;
            Block.getDrops(
                    state,
                    level,
                    pos,
                    blockEntity,
                    miningOwner,
                    lootTool
            ).forEach(drop -> addCollectedDrop(collectedDrops, drop));

            state.spawnAfterBreak(level, pos, lootTool, miningOwner instanceof Player);
        }

        // 使用方块自身的爆炸回调完成移除，并保留 TNT 连锁点燃等原版行为。
        state.onBlockExploded(level, pos, explosion);
    }

    /**
     * 将物品合并到收集列表，并按物品最大堆叠数拆分。
     *
     * @param collectedDrops 当前已经收集的物品
     * @param incoming 新加入的物品栈
     */
    private static void addCollectedDrop(List<ItemStack> collectedDrops, ItemStack incoming) {
        if (incoming.isEmpty()) {
            return;
        }

        ItemStack remaining = incoming.copy();
        for (ItemStack existing : collectedDrops) {
            if (!ItemStack.isSameItemSameComponents(existing, remaining)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(
                    remaining.getCount(),
                    existing.getMaxStackSize() - existing.getCount()
            );
            existing.grow(transferable);
            remaining.shrink(transferable);

            if (remaining.isEmpty()) {
                return;
            }
        }

        while (!remaining.isEmpty()) {
            int splitSize = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            collectedDrops.add(remaining.split(splitSize));
        }
    }

    /**
     * 把全部掉落写入爆炸中心附近的箱子。
     *
     * <p>通常只会生成一个箱子。掉落种类超过 27 格时会继续放置彼此分开的额外箱子，
     * 以保证所有物品都被保存。</p>
     *
     * @param level 当前服务端世界
     * @param center 爆炸中心方块坐标
     * @param drops 需要保存的全部掉落
     */
    private static void placeStorageChests(
            ServerLevel level,
            BlockPos center,
            List<ItemStack> drops
    ) {
        Deque<ItemStack> pending = new ArrayDeque<>(drops);
        Set<BlockPos> usedPositions = new HashSet<>();
        boolean firstChest = true;

        while (firstChest || !pending.isEmpty()) {
            firstChest = false;
            BlockPos chestPos = findStoragePosition(level, center, usedPositions);

            if (chestPos == null) {
                spawnProtectedOverflow(level, center, pending);
                return;
            }

            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            usedPositions.add(chestPos);
            BlockEntity blockEntity = level.getBlockEntity(chestPos);

            if (!(blockEntity instanceof Container chest)) {
                spawnProtectedOverflow(level, center, pending);
                return;
            }

            for (int slot = 0; slot < chest.getContainerSize() && !pending.isEmpty(); slot++) {
                chest.setItem(slot, pending.removeFirst());
            }
            chest.setChanged();
        }
    }

    /**
     * 在爆炸中心附近寻找可放置且不会与已有存储箱相连的位置。
     *
     * @param level 当前服务端世界
     * @param center 搜索中心
     * @param usedPositions 已经放置箱子的坐标
     * @return 可用坐标；找不到时返回 {@code null}
     */
    @Nullable
    private static BlockPos findStoragePosition(
            ServerLevel level,
            BlockPos center,
            Set<BlockPos> usedPositions
    ) {
        for (int radius = 0; radius <= STORAGE_SEARCH_RADIUS; radius++) {
            for (int yOffset = 0; yOffset <= 3; yOffset++) {
                for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                    for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                        BlockPos candidate = center.offset(xOffset, yOffset, zOffset);
                        if (!level.getWorldBorder().isWithinBounds(candidate)
                                || !level.getBlockState(candidate).isAir()
                                || !level.getFluidState(candidate).isEmpty()
                                || isAdjacentToUsedChest(candidate, usedPositions)) {
                            continue;
                        }

                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    /** 检查候选位置是否会与已经生成的箱子水平相邻。 */
    private static boolean isAdjacentToUsedChest(BlockPos candidate, Set<BlockPos> usedPositions) {
        for (BlockPos used : usedPositions) {
            int horizontalDistance = Math.abs(candidate.getX() - used.getX())
                    + Math.abs(candidate.getZ() - used.getZ());
            if (candidate.getY() == used.getY() && horizontalDistance <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 极端情况下无法放置箱子时，把剩余物品生成为不会自然消失的实体。
     */
    private static void spawnProtectedOverflow(
            ServerLevel level,
            BlockPos center,
            Deque<ItemStack> pending
    ) {
        while (!pending.isEmpty()) {
            ItemEntity itemEntity = new ItemEntity(
                    level,
                    center.getX() + 0.5,
                    center.getY() + 0.5,
                    center.getZ() + 0.5,
                    pending.removeFirst()
            );
            itemEntity.setDefaultPickUpDelay();
            itemEntity.setUnlimitedLifetime();
            level.addFreshEntity(itemEntity);
        }
    }

    /** 保存时运等级，使退出世界后重新加载仍保持正确爆炸行为。 */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte(TAG_FORTUNE_LEVEL, (byte) fortuneLevel);
    }

    /** 从实体 NBT 恢复时运等级和显示方块状态。 */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        fortuneLevel = Math.max(0, Math.min(3, tag.getByte(TAG_FORTUNE_LEVEL)));
        setBlockState(Ore_craft.MINING_TNT_BLOCK.get().defaultBlockState()
                .setValue(com.lazeroX.ore_craft.block.MiningTntBlock.FORTUNE_LEVEL, fortuneLevel));
    }

    /** 在跨维度复制实体时同步自定义字段。 */
    @Override
    public void restoreFrom(Entity original) {
        super.restoreFrom(original);
        if (original instanceof MiningPrimedTntEntity miningTnt) {
            miningOwner = miningTnt.miningOwner;
            fortuneLevel = miningTnt.fortuneLevel;
        }
    }
}
