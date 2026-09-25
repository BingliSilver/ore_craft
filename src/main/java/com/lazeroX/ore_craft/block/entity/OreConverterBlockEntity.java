package com.lazeroX.ore_craft.block.entity;

import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.register.ModBlockEntities;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 保存矿质传输接口的物品输入、矿质容器、放置者和转换计时。
 * 普通容器收取生成的 ME；末影容器连接放置者账户，离线时也可运行。
 */
public final class OreConverterBlockEntity extends BlockEntity implements OreMachineInventory {
    /** 两次转换间隔为 100 游戏刻，即正常刻速下约五秒。 */
    public static final int INTERVAL_TICKS = 100;
    /** 单次最多消耗 16 件，剩余物品留待下一轮。 */
    private static final int ITEMS_PER_CYCLE = 16;
    /** 原料和矿质容器在持久库存中的位置。 */
    public static final int INPUT_SLOT = 0;
    public static final int CONTAINER_SLOT = 1;
    /** 顶部只收原料，侧面可补原料或容器；底部不暴露任何库存。 */
    private static final int[] TOP_SLOTS = {INPUT_SLOT};
    private static final int[] SIDE_SLOTS = {INPUT_SLOT, CONTAINER_SLOT};
    private static final int[] BOTTOM_SLOTS = {};
    /** 持久化库存；内容变化会使计时重新开始并标记方块实体待保存。 */
    private final SimpleContainer inventory = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            progressTicks = 0;
            OreConverterBlockEntity.this.setChanged();
        }
    };
    /** 末影容器账户所属玩家；首位放置者固定此 UUID。 */
    private UUID owner;
    /** 当前物品已等待的游戏刻数，范围为 0 到 99。 */
    private int progressTicks;

    /** 创建一个尚未绑定玩家的新传输接口，放置或首次打开时绑定账户。 */
    public OreConverterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ORE_CONVERTER.get(), pos, state);
    }

    /** 返回持久化的原料格和容器格，关闭菜单后两者仍留在方块内。 */
    public SimpleContainer inventory() {
        return inventory;
    }

    /** 顶部输入原料，侧面补充原料或容器；底面不向漏斗开放库存。 */
    @Override
    public int[] getSlotsForFace(Direction direction) {
        return direction == Direction.UP ? TOP_SLOTS
                : direction == Direction.DOWN ? BOTTOM_SLOTS : SIDE_SLOTS;
    }

    /** 按槽位限制漏斗可插入的物品，与玩家菜单采用相同的服务端规则。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == INPUT_SLOT ? OreConversionPrices.canDeposit(stack)
                : slot == CONTAINER_SLOT && OreMachineEnergy.isContainer(stack);
    }

    /** 底面不允许回填，其他面只接受其可见槽位对应的物品。 */
    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return direction != Direction.DOWN && (direction != Direction.UP || slot == INPUT_SLOT)
                && canPlaceItem(slot, stack);
    }

    /** 容器和原料始终不能被漏斗抽出，避免自动化系统取走支付容器或抢走未处理原料。 */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }

    /** 返回末影容器账户 UUID；未认领的旧方块返回 null。 */
    public UUID owner() {
        return owner;
    }

    /** 为未绑定的传输接口记录放置者，已绑定时不允许其他玩家覆盖。 */
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
     * 每服务端刻检查原料和容器；五秒后最多消耗 16 件，将对应 ME 存入容器。
     * 容量不足时按可容纳的整件数量结算，存入成功后才消耗原料。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, OreConverterBlockEntity converter) {
        if (level.isClientSide() || converter.owner == null || level.getServer() == null) return;
        ItemStack stack = converter.inventory.getItem(INPUT_SLOT);
        ItemStack container = converter.inventory.getItem(CONTAINER_SLOT);
        OptionalLong unit = OreConversionPrices.price(stack.getItem());
        if (!OreConversionPrices.canDeposit(stack) || !OreMachineEnergy.isContainer(container)
                || unit.isEmpty() || OreMachineEnergy.remaining(level.getServer(), converter.owner, container) < unit.getAsLong()) {
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

        // 按当前数据包价格重新计算本轮可转化数量，容量不足一整件时保持输入不变。
        int count = (int) Math.min(Math.min(ITEMS_PER_CYCLE, stack.getCount()),
                OreMachineEnergy.remaining(level.getServer(), converter.owner, container) / unit.getAsLong());
        if (count <= 0) return;
        long amount = unit.getAsLong() * count;
        ItemStack updated = OreMachineEnergy.credit(level.getServer(), converter.owner, container, amount);
        if (updated == null) return;
        if (updated != container) converter.inventory.setItem(CONTAINER_SLOT, updated);
        converter.inventory.setItem(INPUT_SLOT, count == stack.getCount()
                ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - count));

        // 末影容器走全局账户，在线拥有者需要及时收到余额同步。
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(converter.owner);
        if (player != null && updated == container) OreConversionNetwork.sendState(player, -1);
    }

    /** 将两格库存、账户归属和剩余等待进度写入区块存档。 */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (!inventory.getItem(INPUT_SLOT).isEmpty()) tag.put("Input", inventory.getItem(INPUT_SLOT).save(registries));
        if (!inventory.getItem(CONTAINER_SLOT).isEmpty())
            tag.put("Container", inventory.getItem(CONTAINER_SLOT).save(registries));
        tag.putInt("Progress", progressTicks);
    }

    /** 从区块存档恢复原料、容器与进度；旧存档缺少容器时保持容器格为空。 */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        inventory.setItem(INPUT_SLOT, ItemStack.parseOptional(registries, tag.getCompound("Input")));
        inventory.setItem(CONTAINER_SLOT, ItemStack.parseOptional(registries, tag.getCompound("Container")));
        progressTicks = Math.clamp(tag.getInt("Progress"), 0, INTERVAL_TICKS - 1);
    }
}
