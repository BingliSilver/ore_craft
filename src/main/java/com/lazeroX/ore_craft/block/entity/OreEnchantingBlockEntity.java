package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.register.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 保存矿质附魔台支付容器，并维护书本翻页、开合和朝向的客户端动画状态。
 * 容器保存在世界中以便关闭菜单后继续使用；动画值仅影响显示，无需持久化。
 */
public final class OreEnchantingBlockEntity extends BlockEntity {
    /** 支付槽最多保存一个矿质容器；库存变化时标记方块实体待保存。 */
    private final SimpleContainer paymentContainer = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            OreEnchantingBlockEntity.this.setChanged();
        }
    };
    /** 与原版附魔书一致的随机翻页来源。 */
    private static final RandomSource RANDOM = RandomSource.create();
    /** 已经过的客户端刻数，用于轻微上下漂浮。 */
    public int time;
    /** 当前和上一帧的翻页位置。 */
    public float flip;
    public float previousFlip;
    /** 翻页目标及速度。 */
    public float flipTarget;
    public float flipVelocity;
    /** 当前和上一帧的打开程度，范围为 0 到 1。 */
    public float open;
    public float previousOpen;
    /** 当前、上一帧及目标朝向，单位为弧度。 */
    public float rotation;
    public float previousRotation;
    public float targetRotation;

    /** 为世界中放置的矿质附魔台创建动画状态。 */
    public OreEnchantingBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_ENCHANTING_TABLE.get(), pos, state);
    }

    /** 返回持久化的单格支付槽，供菜单共享及方块破坏时掉落物品。 */
    public SimpleContainer paymentContainer() {
        return paymentContainer;
    }

    /** 将支付容器写入区块存档，空槽不写入物品数据。 */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ItemStack stack = paymentContainer.getItem(0);
        if (!stack.isEmpty()) tag.put("PaymentContainer", stack.save(registries));
    }

    /** 恢复上次放入的支付容器；旧存档缺少此字段时保持空槽。 */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        paymentContainer.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("PaymentContainer")));
    }

    /**
     * 按原版附魔台的节奏更新书本动画：靠近玩家时打开并转向，离开后缓慢合上。
     *
     * @param level 所在客户端世界
     * @param pos 附魔台位置
     * @param state 当前方块状态；动画不修改此状态
     * @param book 当前书本动画实体
     */
    public static void bookAnimationTick(Level level, BlockPos pos, BlockState state, OreEnchantingBlockEntity book) {
        book.previousOpen = book.open;
        book.previousRotation = book.rotation;
        Player player = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                3.0, false);
        if (player != null) {
            double dx = player.getX() - (pos.getX() + 0.5);
            double dz = player.getZ() - (pos.getZ() + 0.5);
            book.targetRotation = (float) Mth.atan2(dz, dx);
            book.open += 0.1F;
            if (book.open < 0.5F || RANDOM.nextInt(40) == 0) {
                float previousTarget = book.flipTarget;
                do {
                    book.flipTarget += RANDOM.nextInt(4) - RANDOM.nextInt(4);
                } while (previousTarget == book.flipTarget);
            }
        } else {
            book.targetRotation += 0.02F;
            book.open -= 0.1F;
        }
        // 角度归一化使跨越 ±π 时仍沿最近方向转动。
        while (book.rotation >= Math.PI) book.rotation -= (float) (Math.PI * 2);
        while (book.rotation < -Math.PI) book.rotation += (float) (Math.PI * 2);
        while (book.targetRotation >= Math.PI) book.targetRotation -= (float) (Math.PI * 2);
        while (book.targetRotation < -Math.PI) book.targetRotation += (float) (Math.PI * 2);
        float difference = book.targetRotation - book.rotation;
        while (difference >= Math.PI) difference -= (float) (Math.PI * 2);
        while (difference < -Math.PI) difference += (float) (Math.PI * 2);
        book.rotation += difference * 0.4F;
        book.open = Mth.clamp(book.open, 0.0F, 1.0F);
        book.time++;
        book.previousFlip = book.flip;
        float delta = Mth.clamp((book.flipTarget - book.flip) * 0.4F, -0.2F, 0.2F);
        book.flipVelocity += (delta - book.flipVelocity) * 0.9F;
        book.flip += book.flipVelocity;
    }
}
