package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * 矿质转化器的持久状态：支付容器、单格产物、已学习物品选择和五秒计时。
 * 放置者离线时仍可运行；选择只保存物品 ID，不额外保存或掉落实物模板。
 */
public final class OreConversionMachineBlockEntity extends BlockEntity implements OreMachineInventory {
    /** 每轮间隔 100 游戏刻，正常刻速下约五秒。 */
    public static final int INTERVAL_TICKS = 100;
    /** 容器和真实输出物的库存位置；选择框由菜单单独同步。 */
    public static final int CONTAINER_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;
    /** 顶部和侧面输入支付容器，底部只暴露真实产物格。 */
    private static final int[] INPUT_SLOTS = {CONTAINER_SLOT};
    private static final int[] OUTPUT_SLOTS = {OUTPUT_SLOT};
    /** 一轮最多生成 16 件，仍受物品自身最大堆叠数量限制。 */
    private static final int ITEMS_PER_CYCLE = 16;
    /** 上一次检查时的支付容器快照，用于区分支付条件变化和单纯提取产物。 */
    private ItemStack observedContainer = ItemStack.EMPTY;
    /** 方块内的持久物品库存；只有支付容器变化才会重新开始本轮计时。 */
    private final SimpleContainer inventory = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            // 漏斗取走输出物也会触发库存通知，但不会改变本轮的支付条件。
            ItemStack currentContainer = getItem(CONTAINER_SLOT);
            if (!ItemStack.matches(observedContainer, currentContainer)) {
                observedContainer = currentContainer.copy();
                progressTicks = 0;
            }
            OreConversionMachineBlockEntity.this.setChanged();
        }
    };
    /** 放置者 UUID；已学习目录和末影 ME 账户都以此为准。 */
    private UUID owner;
    /** 当前选择的已学习物品 ID；未选择时为空。 */
    private ResourceLocation selected;
    /** 当前有效生产轮次累计的游戏刻数。 */
    private int progressTicks;

    /**
     * 创建未认领、未选择产物的新方块实体。
     *
     * @param pos 方块世界坐标
     * @param state 方块当前状态
     */
    public OreConversionMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_CONVERSION_MACHINE.get(), pos, state);
    }

    /** 返回真实库存，供菜单操作与方块破坏时掉落。 */
    public SimpleContainer inventory() { return inventory; }

    /** 顶部、侧面让漏斗插入容器，底部只提供产物；虚拟选择框不可自动化。 */
    @Override
    public int[] getSlotsForFace(Direction direction) {
        return direction == Direction.DOWN ? OUTPUT_SLOTS : INPUT_SLOTS;
    }

    /** 除矿质容器外拒绝漏斗插入，输出格只能由服务端计时逻辑写入。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == CONTAINER_SLOT && OreMachineEnergy.isContainer(stack);
    }

    /** 底面只输出，容器可由顶部或侧面漏斗输入。 */
    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return direction != Direction.DOWN && canPlaceItem(slot, stack);
    }

    /** 下方漏斗可以提取已经生成的产物，不能抽走支付容器或选择图标。 */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return direction == Direction.DOWN && slot == OUTPUT_SLOT;
    }

    /** 返回放置者 UUID；尚未认领时为空。 */
    public UUID owner() { return owner; }

    /**
     * 只允许首次放置或旧方块首次打开时设置所有者。
     *
     * @param playerId 放置者的 UUID
     */
    public void claim(UUID playerId) {
        if (owner != null) return;
        owner = playerId;
        setChanged();
    }

    /** 返回当前选择；选择的物品并不实际占用库存。 */
    public ResourceLocation selected() { return selected; }

    /**
     * 服务端菜单验证后更新选择，并重启当前生产轮次。
     *
     * @param id 已学习且可生产的物品 ID
     */
    public void select(ResourceLocation id) {
        if (id.equals(selected)) return;
        selected = id;
        progressTicks = 0;
        setChanged();
    }

    /** 返回需要同步到界面的轮次进度。 */
    public int progressTicks() { return progressTicks; }

    /**
     * 每服务端刻核对学习记录、实时价格、ME 余额和完整批次的输出空间。
     * 满五秒时先成功扣费，再写入输出；条件变化时归零等待进度。
     *
     * @param level 当前服务端世界
     * @param pos 当前方块坐标
     * @param state 当前方块状态
     * @param machine 本次推进的方块实体
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, OreConversionMachineBlockEntity machine) {
        if (level.isClientSide() || machine.owner == null || level.getServer() == null) return;
        ResourceLocation id = machine.selected;
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                || !OreConversionSavedData.get(level.getServer()).account(machine.owner).knows(id)) {
            machine.resetProgress();
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack target = new ItemStack(item);
        OptionalLong unit = OreConversionPrices.price(item);
        ItemStack container = machine.inventory.getItem(CONTAINER_SLOT);
        ItemStack output = machine.inventory.getItem(OUTPUT_SLOT);
        if (!OreConversionPrices.canExtract(item) || !OreConversionPrices.isPlain(target)
                || unit.isEmpty() || !OreMachineEnergy.isContainer(container)
                || (!output.isEmpty() && !ItemStack.isSameItemSameComponents(output, target))) {
            machine.resetProgress();
            return;
        }
        // 单格输出必须容得下一整个批次；不可堆叠物品每轮只生产一件。
        int count = Math.min(ITEMS_PER_CYCLE, target.getMaxStackSize());
        long unitPrice = unit.getAsLong();
        if (output.getCount() > target.getMaxStackSize() - count || unitPrice > Long.MAX_VALUE / count) {
            machine.resetProgress();
            return;
        }
        long amount = unitPrice * count;
        if (OreMachineEnergy.stored(level.getServer(), machine.owner, container) < amount) {
            machine.resetProgress();
            return;
        }
        machine.progressTicks++;
        if (machine.progressTicks < INTERVAL_TICKS) {
            machine.setChanged();
            return;
        }
        machine.resetProgress();
        // 容器槽改动只能发生在服务端主线程；扣费后本轮立即产出对应的物品数。
        ItemStack updated = OreMachineEnergy.debit(level.getServer(), machine.owner, container, amount);
        if (updated == null) return;
        if (updated != container) machine.inventory.setItem(CONTAINER_SLOT, updated);
        machine.inventory.setItem(OUTPUT_SLOT, target.copyWithCount(output.getCount() + count));
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(machine.owner);
        if (player != null && updated == container) OreConversionNetwork.sendState(player, -1);
    }

    /** 在材料或支付条件不足时停止当前轮次，避免条件恢复后立即产出。 */
    private void resetProgress() {
        if (progressTicks == 0) return;
        progressTicks = 0;
        setChanged();
    }

    /** 将容器、输出、拥有者、选择和进度写入区块存档。 */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (selected != null) tag.putString("Selected", selected.toString());
        if (!inventory.getItem(CONTAINER_SLOT).isEmpty())
            tag.put("Container", inventory.getItem(CONTAINER_SLOT).save(registries));
        if (!inventory.getItem(OUTPUT_SLOT).isEmpty())
            tag.put("Output", inventory.getItem(OUTPUT_SLOT).save(registries));
        tag.putInt("Progress", progressTicks);
    }

    /** 从区块存档恢复状态，损坏或不存在的选择会被清空。 */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Selected"));
        selected = id != null && BuiltInRegistries.ITEM.containsKey(id) ? id : null;
        inventory.setItem(CONTAINER_SLOT, ItemStack.parseOptional(registries, tag.getCompound("Container")));
        inventory.setItem(OUTPUT_SLOT, ItemStack.parseOptional(registries, tag.getCompound("Output")));
        progressTicks = Math.clamp(tag.getInt("Progress"), 0, INTERVAL_TICKS - 1);
    }
}
