package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.item.MineralTimeScepterItem;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3f;

/**
 * 在服务端为权杖指定的单个方块追加方块实体刻、随机刻和顶部白色粒子。
 *
 * <p>每次施加时按权杖选择的倍率和时长一次性扣除全部 ME，随后持续所选游戏刻数。
 * 目标变化或区块卸载时立即结束；已支付的费用不会在持续期间再次扣除。
 * 效果只保留在运行中的服务器内存中，重启服务器后不会继续生效。</p>
 */
public final class MineralTimeAcceleration {
    /** 正常每秒的游戏刻数。 */
    private static final int TICKS_PER_SECOND = 20;
    /** 区块中一个固定位置被随机选中的概率分母：16³。 */
    private static final int RANDOM_TICK_POSITIONS = 4_096;
    /** 防止异常高的 randomTickSpeed 在单个服务器刻内触发无界工作量。 */
    private static final int MAX_EXTRA_RANDOM_TICKS_PER_GAME_TICK = 256;
    /** 每隔 5 刻向附近玩家同步一组粒子，避免每刻发送大量视觉数据。 */
    private static final int PARTICLE_INTERVAL_TICKS = 5;
    /** 白色粉尘粒子；颜色固定为 RGB (1, 1, 1)，不借用有色的魔法粒子。 */
    private static final DustParticleOptions WHITE_DUST = new DustParticleOptions(new Vector3f(1.0F, 1.0F, 1.0F), 1.0F);
    /** 每个维度与位置至多有一个有效加速，所有访问都在服务器主线程。 */
    private static final Map<Target, ActiveEffect> EFFECTS = new HashMap<>();

    /** 工具类不创建实例；状态由全局事件处理器统一持有。 */
    private MineralTimeAcceleration() {}

    /**
     * 对玩家点击的方块创建新效果，并立即扣除所选时长的全部 ME。
     * 同一玩家重新施加会重启计时并再次付费；其他玩家已有的效果不会被覆盖。
     *
     * @param player 使用权杖的服务端玩家，也是全局 ME 的付款人
     * @param pos 被点击的方块位置
     * @param multiplier 权杖当前选择的倍率
     * @param durationSeconds 权杖当前选择的持续秒数
     */
    public static void apply(ServerPlayer player, BlockPos pos, int multiplier, int durationSeconds) {
        if (!MineralTimeScepterItem.isValidConfiguration(multiplier, durationSeconds)) return;
        ServerLevel level = player.serverLevel();
        Target target = new Target(level.dimension(), pos.immutable());
        ActiveEffect existing = EFFECTS.get(target);
        if (existing != null && !existing.owner.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.ore_craft.mineral_time_scepter.occupied"), true);
            return;
        }

        // 普通静态方块没有可推进的计时逻辑，不允许先扣费再显示无效效果。
        BlockState state = level.getBlockState(pos);
        if (!canAccelerate(level, pos, state)) {
            player.displayClientMessage(Component.translatable("message.ore_craft.mineral_time_scepter.unsupported"), true);
            return;
        }

        long cost = MineralTimeScepterItem.totalCost(multiplier, durationSeconds);
        if (!OreConversionSavedData.get(player).debit(player, cost)) {
            player.displayClientMessage(Component.translatable("message.ore_craft.mineral_time_scepter.insufficient",
                    cost), true);
            return;
        }

        // 保存当前方块及方块实体的身份，避免破坏后在同一位置重放的方块继承效果。
        EFFECTS.put(target, new ActiveEffect(player.getUUID(), state.getBlock(), level.getBlockEntity(pos),
                multiplier, durationSeconds));
        player.displayClientMessage(Component.translatable("message.ore_craft.mineral_time_scepter.applied",
                multiplier, durationSeconds), true);
    }

    /**
     * 在原版完成本刻更新后执行额外计时并同步顶部粒子；费用已在施加时付清。
     *
     * @param event 当前服务端刻的结束事件
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (EFFECTS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<Map.Entry<Target, ActiveEffect>> iterator = EFFECTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Target, ActiveEffect> entry = iterator.next();
            Target target = entry.getKey();
            ActiveEffect effect = entry.getValue();
            ServerLevel level = server.getLevel(target.dimension);

            // 不加载离开的区块；方块被替换或方块实体重建后也不再操作旧目标。
            if (effect.remainingTicks <= 0 || level == null || !isStillValid(level, target.pos, effect)) {
                iterator.remove();
                continue;
            }

            // 粒子只在效果有效期间从服务端发送，所有附近客户端会看到同一目标提示。
            if (effect.remainingTicks % PARTICLE_INTERVAL_TICKS == 0) {
                spawnTopParticles(level, target.pos);
            }
            accelerate(level, target.pos, effect);
            effect.remainingTicks--;
            if (effect.remainingTicks == 0) iterator.remove();
        }
    }

    /**
     * 服务器关闭时清空临时效果，避免单机重新进入世界后沿用旧坐标和账户。
     *
     * @param event 当前服务器关闭事件
     */
    public static void onServerStopped(ServerStoppedEvent event) {
        EFFECTS.clear();
    }

    /**
     * 判断目标是否有服务端方块实体计时器或可用的随机刻逻辑。
     *
     * @param level 目标所在维度
     * @param pos 目标位置
     * @param state 当前方块状态
     * @return 至少存在一种能被额外推进的计时方式时为 true
     */
    private static boolean canAccelerate(ServerLevel level, BlockPos pos, BlockState state) {
        boolean randomTicks = state.isRandomlyTicking()
                && level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) > 0;
        return randomTicks || ticker(level, pos, state) != null;
    }

    /**
     * 核对区块、方块类型和原有方块实体是否仍然有效。
     *
     * @param level 目标维度
     * @param pos 目标位置
     * @param effect 施加时记录的目标身份
     * @return 仍可在原位置继续加速时为 true
     */
    private static boolean isStillValid(ServerLevel level, BlockPos pos, ActiveEffect effect) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == effect.block
                && (effect.blockEntity == null || level.getBlockEntity(pos) == effect.blockEntity)
                && canAccelerate(level, pos, state);
    }

    /**
     * 在方块当前轮廓顶部生成少量白色粒子；作物高度变化时粒子随生长位置上移。
     *
     * @param level 目标维度，负责把粒子同步给附近玩家
     * @param pos 目标方块坐标
     */
    private static void spawnTopParticles(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        double top = shape.isEmpty() ? 1.0D : shape.max(Direction.Axis.Y);
        level.sendParticles(WHITE_DUST, pos.getX() + 0.5D, pos.getY() + top + 0.08D,
                pos.getZ() + 0.5D, 3, 0.28D, 0.03D, 0.28D, 0.01D);
    }

    /**
     * 每游戏刻最多追加倍率减一轮方块实体计时，并按原版随机刻选点概率推进植物等方块。
     * 每轮都重新读取状态；目标消失或成熟后不继续执行旧状态的计时器。
     *
     * @param level 目标维度
     * @param pos 目标位置
     * @param effect 施加时已付费的倍率和目标身份
     */
    private static void accelerate(ServerLevel level, BlockPos pos, ActiveEffect effect) {
        int randomTicksExecuted = 0;
        for (int extra = 1; extra < effect.multiplier; extra++) {
            if (!isStillValid(level, pos, effect)) return;
            BlockState state = level.getBlockState(pos);
            BlockEntityTicker<BlockEntity> ticker = ticker(level, pos, state);
            if (ticker != null) ticker.tick(level, pos, state, level.getBlockEntity(pos));

            // 原版随机刻每轮在 16³ 个位置中按 randomTickSpeed 次选取；商表示必然命中次数，余数表示概率。
            // 默认随机刻速度通常只会进入余数分支；极高的 gamerule 值仍受单刻调用上限保护。
            if (!isStillValid(level, pos, effect)) return;
            state = level.getBlockState(pos);
            int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
            if (state.isRandomlyTicking() && randomTickSpeed > 0
                    && randomTicksExecuted < MAX_EXTRA_RANDOM_TICKS_PER_GAME_TICK) {
                int hits = randomTickSpeed / RANDOM_TICK_POSITIONS;
                if (level.getRandom().nextInt(RANDOM_TICK_POSITIONS)
                        < randomTickSpeed % RANDOM_TICK_POSITIONS) hits++;
                for (int hit = 0; hit < hits && randomTicksExecuted < MAX_EXTRA_RANDOM_TICKS_PER_GAME_TICK; hit++) {
                    if (!isStillValid(level, pos, effect)) return;
                    state = level.getBlockState(pos);
                    if (!state.isRandomlyTicking()) break;
                    state.randomTick(level, pos, level.getRandom());
                    randomTicksExecuted++;
                }
            }
        }
    }

    /**
     * 取得当前方块实体与类型匹配的服务端计时器；普通方块返回 null。
     * 类型由当前方块实体提供，因此此处的泛型转换不会把计时器用于其他类型。
     *
     * @param level 目标维度
     * @param pos 目标位置
     * @param state 当前方块状态
     * @return 当前服务端计时器，或 null
     */
    @SuppressWarnings("unchecked")
    private static BlockEntityTicker<BlockEntity> ticker(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock block)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity.isRemoved()) return null;
        return (BlockEntityTicker<BlockEntity>) block.getTicker(level, state, entity.getType());
    }

    /**
     * 一个维度中的单个方块位置；位置会在创建时复制，避免可变坐标破坏映射键。
     *
     * @param dimension 目标维度
     * @param pos 目标方块坐标
     */
    private record Target(ResourceKey<Level> dimension, BlockPos pos) {}

    /** 一次加速的服务端临时状态；所有计数只在主线程更新。 */
    private static final class ActiveEffect {
        /** 已一次性支付 ME 的玩家 UUID，用于阻止其他玩家覆盖其效果。 */
        private final UUID owner;
        /** 施加时的方块类型，用于发现方块被替换。 */
        private final Block block;
        /** 施加时的方块实体；纯随机刻方块没有方块实体。 */
        private final BlockEntity blockEntity;
        /** 此效果固定使用的倍率，后续切换权杖不会修改已施加的效果。 */
        private final int multiplier;
        /** 尚可运行的游戏刻数，初始值由界面所选秒数计算。 */
        private int remainingTicks;

        /**
         * 创建已付清所选时长费用的加速效果。
         *
         * @param owner 付款人 UUID
         * @param block 施加时的方块类型
         * @param blockEntity 施加时的方块实体，允许为 null
         * @param multiplier 本轮固定倍率
         * @param durationSeconds 本轮固定持续秒数
         */
        private ActiveEffect(UUID owner, Block block, BlockEntity blockEntity,
                             int multiplier, int durationSeconds) {
            this.owner = owner;
            this.block = block;
            this.blockEntity = blockEntity;
            this.multiplier = multiplier;
            this.remainingTicks = durationSeconds * TICKS_PER_SECOND;
        }
    }
}
