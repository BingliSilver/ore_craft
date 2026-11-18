package com.lazeroX.ore_craft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/** 按方块实例保存机器的归属账户，供生产扣费、入账和交互鉴权共同使用。 */
public abstract class OwnedMachineBlockEntity extends BlockEntity {
    /** 区块存档中的历史字段名，已有机器依靠此键恢复归属。 */
    private static final String OWNER_KEY = "Owner";
    /** 放置或首次认领机器的玩家，尚未认领时为空。 */
    private UUID owner;

    /** 使用具体机器注册的实体类型和放置位置初始化方块实体。 */
    protected OwnedMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** @return 所属玩家的 UUID，尚未认领时返回 null */
    public final UUID owner() {
        return owner;
    }

    /** 首次放置或打开无主机器时绑定账户，后续交互沿用原账户。
     * @param playerId 发起认领的玩家 UUID
     */
    public final void claim(UUID playerId) {
        if (owner == null) {
            owner = playerId;
            // 即使机器尚未开始工作，首次认领也需要随区块保存。
            setChanged();
        }
    }

    /** 随区块保存已绑定的账户，无主机器沿用缺省字段格式。 */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (owner != null) tag.putUUID(OWNER_KEY, owner);
    }

    /** 从存档恢复归属，缺少有效 UUID 的旧机器仍可由首次使用者认领。 */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        owner = tag.hasUUID(OWNER_KEY) ? tag.getUUID(OWNER_KEY) : null;
    }
}
