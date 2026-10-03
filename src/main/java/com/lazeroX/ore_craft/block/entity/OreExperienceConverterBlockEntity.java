package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.block.OreExperienceConverterBlock;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.value.EnchantMeCostCalculator;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 保存经验转化器的供能容器、放置者、生产倍率和计时，在服务端将 ME 转为实际经验球。
 * 普通容器扣自身存储量，末影容器扣放置者账户；无需玩家在线或保持界面打开。
 * 每轮按所选倍率整批生产；关闭、缺少容器或余额不足当前档位时不扣费，并清除进度。
 */
public final class OreExperienceConverterBlockEntity extends BlockEntity {
    /** 每轮间隔为 20 游戏刻，正常刻速下是一秒。 */
    public static final int INTERVAL_TICKS = 20;
    /**
     * 每一点经验消耗五倍一级常见附魔基础价，当前为 10,240 ME；经验指点数而非等级。
     * 按原版 1.21.1 附魔池、最高附魔能力和全部兼容候选保守估算，祛魔回收上界约
     * 7,607 ME/消耗经验点；此单价保留约 35% 余量，避免低级或多重附魔循环获利。
     * 与附魔基础价共用常量，调整基础价格时经验价格也同比变化。
     */
    public static final long ME_PER_EXPERIENCE = EnchantMeCostCalculator.BASE_ENCHANT_ME * 5L;
    /** 固定八个生产档位；列表不可变，客户端按钮、服务端校验及存档恢复共用此定义。 */
    public static final List<Integer> MULTIPLIERS = List.of(1, 2, 4, 8, 16, 32, 64, 128);
    /** 唯一的持久供能槽，允许普通和末影矿质容器。 */
    public static final int CONTAINER_SLOT = 0;
    /**
     * 独立单槽库存；放入、取走、替换或修改容器后重置轮次并标记区块需要保存。
     * 生产成功写回普通容器时也会触发此通知，下一轮因此从零开始。
     */
    private final SimpleContainer inventory = new SimpleContainer(1) {
        /** 将菜单中的库存变化同时反映到机器计时和区块存档。 */
        @Override
        public void setChanged() {
            super.setChanged();
            progressTicks = 0;
            OreExperienceConverterBlockEntity.this.setChanged();
        }
    };
    /** 首位放置者或认领者，决定末影容器扣费账户；未认领时为 null。 */
    private UUID owner;
    /** 已等待的游戏刻数，保存范围为 0 到 19；暂停条件出现时重置。 */
    private int progressTicks;
    /** 当前生产倍率，同时是每轮产出的经验点数；新机器及旧存档默认使用 x1。 */
    private int multiplier = 1;

    /**
     * 创建空库存且尚未认领的机器实体；生产开关取自默认关闭的方块状态。
     *
     * @param pos 所属方块坐标
     * @param state 所属方块状态
     */
    public OreExperienceConverterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_EXPERIENCE_CONVERTER.get(), pos, state);
    }

    /** 返回持久供能槽，关闭菜单不会返还或清空其中的容器。 */
    public SimpleContainer inventory() { return inventory; }

    /** 返回固定的末影账户所属玩家，尚未认领时返回 null。 */
    public UUID owner() { return owner; }

    /**
     * 仅为无归属机器绑定账户，已有归属时保持原值并避免更换扣费对象。
     *
     * @param playerId 放置或首次使用机器的玩家 UUID
     */
    public void claim(UUID playerId) {
        if (owner == null) {
            owner = playerId;
            setChanged();
        }
    }

    /** 返回本轮进度，供服务端菜单同步到客户端。 */
    public int progressTicks() { return progressTicks; }

    /** 返回当前固定档位，每轮产出该数量的经验点，范围仅限八个预设倍率。 */
    public int multiplier() { return multiplier; }

    /** 返回当前整轮消耗，x1 为 10,240 ME，x128 为 1,310,720 ME。 */
    public long cycleCost() { return ME_PER_EXPERIENCE * multiplier; }

    /**
     * 切换生产档位并保存；实际变化时重新计时，避免旧档进度立即兑现为新档产出。
     * 不改变方块开关，也不在选择档位时扣费。
     *
     * @param value 预设列表中的倍率，必须为 1、2、4、8、16、32、64 或 128
     * @throws IllegalArgumentException 非预设倍率时抛出
     */
    public void setMultiplier(int value) {
        if (!MULTIPLIERS.contains(value)) throw new IllegalArgumentException("Invalid experience multiplier: " + value);
        if (multiplier != value) {
            multiplier = value;
            resetProgress();
            setChanged();
        }
    }

    /** 清除未完成的轮次并在确实发生变更时请求保存，不会扣除 ME。 */
    public void resetProgress() {
        if (progressTicks != 0) {
            progressTicks = 0;
            setChanged();
        }
    }

    /** 返回当前供能余额；客户端、未认领机器或无效容器均返回零。 */
    public long storedMe() {
        if (owner == null || level == null || level.getServer() == null) return 0;
        return OreMachineEnergy.stored(level.getServer(), owner, inventory.getItem(CONTAINER_SLOT));
    }

    /**
     * 开启且能支付当前档位的整轮费用时累计一秒，再扣费并喷出对应倍率的经验球。
     * 余额不足时等待补充或改选较低档位，不自动降低产量，保持所选档位的固定消耗。
     * 使用原版经验分球和合并规则，保留拾取、经验修补及球体自然运动行为。
     *
     * @param level 当前服务端世界
     * @param pos 机器坐标，经验球生成于顶面中心上方
     * @param state 本刻的方块状态，决定生产开关
     * @param converter 本次推进的机器实体
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, OreExperienceConverterBlockEntity converter) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!state.getValue(OreExperienceConverterBlock.ENABLED) || converter.storedMe() < converter.cycleCost()) {
            converter.resetProgress();
            return;
        }
        // 只有满足生产条件才推进；到期前不预扣 ME，拿走容器不会损失余额。
        converter.progressTicks++;
        converter.setChanged();
        if (converter.progressTicks < INTERVAL_TICKS) return;
        converter.resetProgress();

        // 固定档位决定费用和经验数量；扣费入口再次验证实时余额，成功后才发放整轮经验。
        int experience = converter.multiplier;
        long amount = converter.cycleCost();
        ItemStack container = converter.inventory.getItem(CONTAINER_SLOT);
        ItemStack updated = OreMachineEnergy.debit(serverLevel.getServer(), converter.owner, container, amount);
        if (updated == null) return;
        if (updated != container) converter.inventory.setItem(CONTAINER_SLOT, updated);

        // 顶面上方生成，避免经验球卡在机器实体方块内；原版初速度会向上和四周散开。
        ExperienceOrb.award(serverLevel, new Vec3(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5),
                experience);
        // 末影容器直接扣全局账户，及时刷新在线放置者其他界面使用的余额。
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(converter.owner);
        if (updated == container && player != null) OreConversionNetwork.sendState(player, -1);
    }

    /** 保存容器组件、固定账户归属、所选倍率和未完成轮次；开关随方块状态自动保存。 */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (!inventory.getItem(CONTAINER_SLOT).isEmpty()) {
            tag.put("Container", inventory.getItem(CONTAINER_SLOT).save(registries));
        }
        tag.putInt("Progress", progressTicks);
        tag.putInt("Multiplier", multiplier);
    }

    /** 恢复供能容器、账户和倍率；旧存档或非法倍率回退 x1，异常进度限制在一轮内。 */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        inventory.setItem(CONTAINER_SLOT, ItemStack.parseOptional(registries, tag.getCompound("Container")));
        int savedMultiplier = tag.getInt("Multiplier");
        multiplier = MULTIPLIERS.contains(savedMultiplier) ? savedMultiplier : 1;
        progressTicks = Math.clamp(tag.getInt("Progress"), 0, INTERVAL_TICKS - 1);
    }
}
