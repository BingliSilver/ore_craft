package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.GeologicalDetectorItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.network.GeologicalDetectorNetwork;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.GeologicalOreCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端分批搜索管理器：仅访问已加载区块，在球形范围中比较所有候选矿石的三维距离。
 * 所有玩家共享每刻扫描预算并轮流推进；每人最多一个任务，搜索失败或取消均不扣费。
 */
public final class GeologicalDetection {
    /** 每刻最多细查 32,768 个方块；无矿石区段通过调色板快速跳过，允许最多筛选 512 段。 */
    private static final int BLOCK_BUDGET = 32_768;
    private static final int SECTION_BUDGET = 512;
    /** 单次推进的软时间上限为两毫秒；在区段间检查，避免快速跳过预算拖长服务器刻。 */
    private static final long SCAN_TIME_BUDGET_NANOS = 2_000_000L;
    /** 插入有序队列用于公平轮询，键为玩家 UUID；只在服务器主线程读写。 */
    private static final Map<UUID, Search> SEARCHES = new LinkedHashMap<>();

    /** 无实例事件工具；任务在服务器关闭时统一释放。 */
    private GeologicalDetection() {}

    /**
     * 按当前手持配置创建一次搜索快照；不预扣费、不加载或生成额外区块。
     * @param player 发起者
     * @param hand 使用探测器的手
     */
    public static void start(ServerPlayer player, InteractionHand hand) {
        if (SEARCHES.containsKey(player.getUUID())) {
            message(player, "busy");
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        Block ore = GeologicalDetectorItem.selectedOre(stack);
        if (!GeologicalOreCatalog.isOre(ore)) {
            message(player, "invalid");
            return;
        }
        SEARCHES.put(player.getUUID(), new Search(player, hand, stack, ore));
        message(player, "searching");
    }

    /** @param event 服务端刻；每刻轮询一个任务，全服预算不会随在线人数倍增 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SEARCHES.isEmpty()) return;
        var iterator = SEARCHES.entrySet().iterator();
        var entry = iterator.next();
        Search search = entry.getValue();
        iterator.remove();
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        // 扫描以按下右键时的位置为原点；移动超过一格、换手持物品或换维度后取消，不收费。
        if (player.serverLevel() != search.level || player.getItemInHand(search.hand) != search.stack
                || player.position().distanceToSqr(search.origin) > 1.0
                || GeologicalDetectorItem.rangeIndex(search.stack) != search.rangeIndex
                || GeologicalDetectorItem.selectedOre(search.stack) != search.ore) {
            message(player, "cancelled");
            return;
        }
        if (search.advance()) search.finish(player);
        else SEARCHES.put(entry.getKey(), search);
    }

    /** @param event 服务器关闭事件；防止集成服务器切换存档后保留旧世界和任务引用 */
    public static void onServerStopped(ServerStoppedEvent event) { SEARCHES.clear(); }

    /** @param player 接收者 @param suffix 探测器消息的本地化后缀 */
    private static void message(ServerPlayer player, String suffix) {
        player.displayClientMessage(Component.translatable("message.ore_craft.detector." + suffix), true);
    }

    /**
     * 从随身容器一次性支付费用；普通容器可合并付款，先确认总余额以免余额不足时部分扣费。
     * 携带实体末影容器时仅从玩家共享账户付款，与普通容器渠道分开，避免多次计算同一账户。
     * @param player 支付者
     * @param cost 已按搜索距离和范围倍率算出的正数费用
     * @return 完整扣款成功时为 true
     */
    private static boolean pay(ServerPlayer player, long cost) {
        var inventory = player.getInventory();
        boolean ender = false;
        long needed = cost;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof EnderOreContainerItem) ender = true;
            if (stack.getItem() instanceof OreContainerItem container) {
                // 用递减的需求计算余额，避免叠加大量容器时 long 求和溢出。
                needed -= Math.min(needed, container.storedMe(stack));
            }
        }
        if (ender) {
            if (!OreConversionSavedData.get(player).debit(player, cost)) return false;
            OreConversionNetwork.sendState(player, -1);
        } else {
            if (needed != 0) return false;
            needed = cost;
            for (int slot = 0; slot < inventory.getContainerSize() && needed > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!(stack.getItem() instanceof OreContainerItem container)) continue;
                long amount = Math.min(needed, container.storedMe(stack));
                container.setStoredMe(stack, container.storedMe(stack) - amount);
                needed -= amount;
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    /** 一次搜索的游标与最近结果；对象只在本次服务器运行期间存在，不跨线程读取世界。 */
    private static final class Search {
        /** 世界、起点和手持快照用于限制搜索生命周期并固定距离计费原点。 */
        private final ServerLevel level;
        private final Vec3 origin;
        private final InteractionHand hand;
        private final ItemStack stack;
        /** 精确方块目标及费用配置；半径以矿石中心到使用位置的三维距离判断。 */
        private final Block ore;
        private final int rangeIndex;
        private final int radius;
        /** 与搜索球体相交的区段，按可能出现的最近方块中心距离排序，不持有区块引用。 */
        private final List<SectionCandidate> sections = new ArrayList<>();
        /** 当前区段及区段内 0～4095 的方块游标，可在下一刻接着搜索。 */
        private int sectionIndex;
        private int blockIndex;
        /** 当前最小距离平方及位置；找不到候选时位置为空。 */
        private double nearestSquared;
        private BlockPos nearest;

        /** @param player 发起者 @param hand 持有手 @param stack 原物品 @param ore 目标矿石 */
        private Search(ServerPlayer player, InteractionHand hand, ItemStack stack, Block ore) {
            this.level = player.serverLevel();
            this.origin = player.position();
            this.hand = hand;
            this.stack = stack;
            this.ore = ore;
            rangeIndex = GeologicalDetectorItem.rangeIndex(stack);
            radius = GeologicalDetectorItem.RADII.get(rangeIndex);
            nearestSquared = (double) radius * radius;
            BlockPos center = player.blockPosition();
            int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
            // 限制在世界高度内；水平坐标覆盖完整球体，之后按精确距离排除立方体角落。
            for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
                for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) {
                    for (int y = minY >> 4; y <= maxY >> 4; y++) {
                        SectionPos pos = SectionPos.of(x, y, z);
                        double lowerBound = minimumDistanceSquared(pos);
                        // 下界已经超过球形半径的区段不可能命中，无需查询区块或调色板。
                        if (lowerBound <= nearestSquared) sections.add(new SectionCandidate(pos, lowerBound));
                    }
                }
            }
            // 缓存距离下界，排序和后续剪枝不必反复计算区段到玩家的距离。
            sections.sort(Comparator.comparingDouble(SectionCandidate::minimumDistanceSquared));
        }

        /**
         * 计算起点到区段内所有方块中心包围盒的距离下界，不把连续空间边界误当方块中心。
         * 下界只用于排除不可能更近的区段；最终命中仍逐个检查真实矿石。
         * @param pos 区段坐标
         * @return 最小可能三维距离的平方，起点位于中心包围盒内时为零
         */
        private double minimumDistanceSquared(SectionPos pos) {
            double dx = Math.max(0.0, Math.max(pos.minBlockX() + 0.5 - origin.x,
                    origin.x - (pos.minBlockX() + 15.5)));
            double dy = Math.max(0.0, Math.max(pos.minBlockY() + 0.5 - origin.y,
                    origin.y - (pos.minBlockY() + 15.5)));
            double dz = Math.max(0.0, Math.max(pos.minBlockZ() + 0.5 - origin.z,
                    origin.z - (pos.minBlockZ() + 15.5)));
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * 由近到远推进有界扫描；调色板跳过无目标的区段，距离下界保证提前结束仍返回最近矿石。
         * @return 已检查全部候选，或剩余区段已不可能包含更近矿石时为 true
         */
        private boolean advance() {
            int blocksLeft = BLOCK_BUDGET;
            int sectionsLeft = SECTION_BUDGET;
            long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NANOS;
            while (sectionIndex < sections.size() && blocksLeft > 0 && sectionsLeft-- > 0) {
                SectionCandidate candidateSection = sections.get(sectionIndex);
                // 列表按下界升序排列：当前段都不可能更近，后面的所有区段也无需继续搜索。
                // 只有完成上一段后才能判断；当前段扫描到一半时必须继续，避免漏掉段内更近矿石。
                if (blockIndex == 0 && nearest != null
                        && candidateSection.minimumDistanceSquared() >= nearestSquared) return true;
                if (System.nanoTime() >= deadline) break;
                SectionPos pos = candidateSection.position();
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                // getChunkNow 只读已加载的完整区块，不因探测触发磁盘加载或世界生成。
                LevelChunkSection section = chunk == null ? null : chunk.getSection(level.getSectionIndex(pos.minBlockY()));
                if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.is(ore))) {
                    sectionIndex++;
                    blockIndex = 0;
                    continue;
                }
                while (blockIndex < 4096 && blocksLeft-- > 0) {
                    int index = blockIndex++;
                    int x = index & 15;
                    int z = (index >> 4) & 15;
                    int y = (index >> 8) & 15;
                    if (!section.getBlockState(x, y, z).is(ore)) continue;
                    // 先用基本数值比较距离，仅为改善当前结果的矿石分配坐标对象。
                    int blockX = pos.minBlockX() + x;
                    int blockY = pos.minBlockY() + y;
                    int blockZ = pos.minBlockZ() + z;
                    double dx = blockX + 0.5 - origin.x;
                    double dy = blockY + 0.5 - origin.y;
                    double dz = blockZ + 0.5 - origin.z;
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance > nearestSquared) continue;
                    BlockPos candidate = new BlockPos(blockX, blockY, blockZ);
                    if (level.getWorldBorder().isWithinBounds(candidate)) {
                        nearestSquared = distance;
                        nearest = candidate;
                    }
                }
                if (blockIndex == 4096) {
                    sectionIndex++;
                    blockIndex = 0;
                }
            }
            return sectionIndex == sections.size();
        }

        /** @param player 搜索完成时仍持有原探测器的玩家；先复核矿石存在，再完整扣款并发送粒子端点 */
        private void finish(ServerPlayer player) {
            if (nearest == null || !level.hasChunkAt(nearest) || !level.getBlockState(nearest).is(ore)) {
                message(player, "not_found");
                return;
            }
            long cost = GeologicalDetectorItem.energyCost(origin, Vec3.atCenterOf(nearest), rangeIndex);
            if (!pay(player, cost)) {
                player.displayClientMessage(Component.translatable("message.ore_craft.detector.insufficient", cost), true);
                return;
            }
            GeologicalDetectorNetwork.sendTrace(player, nearest);
            player.displayClientMessage(Component.translatable("message.ore_craft.detector.found",
                    ore.getName(), (int) Math.ceil(Math.sqrt(nearestSquared)), cost,
                    GeologicalDetectorItem.GUIDE_DURATION_SECONDS), true);
        }
    }

    /**
     * 不可变的区段及其距离下界快照，按搜索原点计算，生命周期与一次搜索相同。
     * @param position 16³ 方块区段的坐标
     * @param minimumDistanceSquared 起点到区段方块中心包围盒的距离平方
     */
    private record SectionCandidate(SectionPos position, double minimumDistanceSquared) {
        /** 创建仅供本次排序和剪枝使用的快照，不读取或加载区块。 */
        private SectionCandidate {}
    }
}
