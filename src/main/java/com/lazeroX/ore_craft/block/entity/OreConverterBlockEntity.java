package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * 保存矿质转换器的单格库存、放置者账户及转换计时。
 * 方块放置后独立运行；玩家离线时仍可向其存档账户记入 ME。
 */
public final class OreConverterBlockEntity extends BlockEntity {
    /** 两次转换间隔为 100 游戏刻，即正常刻速下约五秒。 */
    public static final int INTERVAL_TICKS = 100;
    /** 单次最多消耗 16 件，剩余物品留待下一轮。 */
    private static final int ITEMS_PER_CYCLE = 16;
    /** 持久化库存；内容变化会使计时重新开始并标记方块实体待保存。 */
    private final SimpleContainer inventory = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            progressTicks = 0;
            OreConverterBlockEntity.this.setChanged();
        }
    };
    /** 转换收益所属玩家；首位放置者固定此 UUID。 */
    private UUID owner;
    /** 当前物品已等待的游戏刻数，范围为 0 到 99。 */
    private int progressTicks;

    /** 创建一个尚未绑定玩家的新转换器，放置或首次打开时绑定账户。 */
    public OreConverterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_CONVERTER.get(), pos, state);
    }

    /** 返回唯一的持久化存储格，菜单关闭后物品仍留在方块内。 */
    public SimpleContainer inventory() {
        return inventory;
    }

    /** 返回收益账户 UUID；未认领的旧方块返回 null。 */
    public UUID owner() {
        return owner;
    }

    /** 为未绑定账户的转换器记录放置者，已绑定时不允许其他玩家覆盖。 */
    public void claim(UUID playerId) {
        if (owner != null) return;
        owner = playerId;
        setChanged();
    }

    /** 返回本轮已等待的刻数，供菜单同步进度显示。 */
    public int progressTicks() {
        return progressTicks;
    }

    /**
     * 每服务端刻检查一次输入；仅在物品仍可转换、账户可记账时按 16 件上限执行交易。
     * 钱款先写入账户，再减少库存，避免余额溢出时吞掉物品。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, OreConverterBlockEntity converter) {
        if (level.isClientSide() || converter.owner == null || level.getServer() == null) return;
        ItemStack stack = converter.inventory.getItem(0);
        if (!OreConversionPrices.canDeposit(stack)) {
            if (converter.progressTicks != 0) {
                converter.progressTicks = 0;
                converter.setChanged();
            }
            return;
        }
        converter.progressTicks++;
        if (converter.progressTicks < INTERVAL_TICKS) {
            converter.setChanged();
            return;
        }
        converter.progressTicks = 0;
        converter.setChanged();

        // 价格表可能被数据包重载，结算时使用当前价格而非放入时的价格。
        OptionalLong unit = OreConversionPrices.price(stack.getItem());
        if (unit.isEmpty()) return;
        int count = Math.min(ITEMS_PER_CYCLE, stack.getCount());
        if (unit.getAsLong() > Long.MAX_VALUE / count) return;
        long amount = unit.getAsLong() * count;
        if (!OreConversionSavedData.get(level.getServer()).credit(converter.owner, amount)) return;
        converter.inventory.setItem(0, count == stack.getCount()
                ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - count));

        // 在线拥有者及时看到全局余额更新；离线时账户存档仍照常累积。
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(converter.owner);
        if (player != null) {
            int menuId = player.containerMenu instanceof OreConversionMenu menu ? menu.containerId : -1;
            OreConversionNetwork.sendState(player, menuId);
        }
    }

    /** 将库存、账户归属和剩余等待进度写入区块存档。 */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (!inventory.getItem(0).isEmpty()) tag.put("Input", inventory.getItem(0).save(registries));
        tag.putInt("Progress", progressTicks);
    }

    /** 从区块存档恢复输入物与进度；加载库存引发的计时重置之后再还原计时。 */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        inventory.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("Input")));
        progressTicks = Math.clamp(tag.getInt("Progress"), 0, INTERVAL_TICKS - 1);
    }
}
