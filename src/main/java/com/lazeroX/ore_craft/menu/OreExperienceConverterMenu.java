package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.block.OreExperienceConverterBlock;
import com.lazeroX.ore_craft.block.entity.OreExperienceConverterBlockEntity;
import com.lazeroX.ore_craft.register.ModBlocks;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreMachineEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 矿质经验转化器的单槽菜单，服务端直接操作机器库存，客户端只保存同步副本。
 * 同步生产进度、开关、档位和完整 long 范围的 ME 余额；关闭菜单不清空供能槽。
 * 八个档位按钮通过原版菜单按钮包提交，服务端只接受固定编号并再次校验归属。
 */
public final class OreExperienceConverterMenu extends AbstractContainerMenu {
    /** 玩家背包左边界，单位为界面逻辑像素，与机器通用风格保持一致。 */
    public static final int INVENTORY_X = 29;
    /** 三行背包的顶部纵坐标。 */
    public static final int INVENTORY_Y = 234;
    /** 快捷栏顶部纵坐标。 */
    public static final int HOTBAR_Y = 292;
    /** 在原有进度、开关和四段余额之后同步生产倍率，数值最大为 128。 */
    private static final int MULTIPLIER_DATA = 6;
    /** 唯一机器槽之后是三行背包，共 27 格。 */
    private static final int INVENTORY_START = 1;
    /** 快捷栏起始索引；此前的背包范围不包含此位置。 */
    private static final int HOTBAR_START = 28;
    /** 所有真实槽位范围的右边界，不包含此位置。 */
    private static final int INVENTORY_END = 37;
    /** 菜单绑定的不可变方块坐标，客户端通过打开菜单的附加数据取得。 */
    private final BlockPos pos;
    /** 当前玩家世界，用于校验方块是否仍存在。 */
    private final Level level;
    /** 服务端机器实体，客户端为 null，防止客户端参与扣费或库存持久化。 */
    private final OreExperienceConverterBlockEntity converter;
    /** 下标 0 为进度、1 为开关、2～5 为余额片段、6 为倍率，客户端初始倍率为 x1。 */
    private final int[] machineData = {0, 0, 0, 0, 0, 0, 1};

    /** 从服务端打开菜单的附加数据读取机器坐标并创建客户端槽位。 */
    public OreExperienceConverterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readBlockPos());
    }

    /**
     * 创建供能槽与玩家背包；两端布局一致，供能槽只接受一件矿质容器。
     *
     * @param id 原版分配的菜单同步编号
     * @param inventory 操作者背包
     * @param pos 矿质经验转化器的世界坐标
     */
    public OreExperienceConverterMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ORE_EXPERIENCE_CONVERTER_MENU.get(), id);
        this.pos = pos.immutable();
        this.level = inventory.player.level();
        this.converter = !level.isClientSide()
                && level.getBlockEntity(pos) instanceof OreExperienceConverterBlockEntity entity ? entity : null;
        // 服务端直接绑定持久库存；客户端临时库存只用于接收原版槽位同步。
        Container storage = converter == null ? new SimpleContainer(1) : converter.inventory();
        checkContainerSize(storage, 1);
        // 专用供能槽限制种类和数量，普通与末影容器均遵循相同的支付入口。
        addSlot(new Slot(storage, OreExperienceConverterBlockEntity.CONTAINER_SLOT, 44, 76) {
            /** 只接受普通或末影矿质容器，拒绝其他物品。 */
            @Override
            public boolean mayPlace(ItemStack stack) { return OreMachineEnergy.isContainer(stack); }

            /** 每台机器一次只使用一个容器，避免异常堆叠混淆 ME 归属。 */
            @Override
            public int getMaxStackSize() { return 1; }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
        // 打开菜单时就提供已保存档位，避免重开后先显示 x1 再跳到实际倍率。
        if (converter != null) machineData[MULTIPLIER_DATA] = converter.multiplier();
        // 原版菜单数据槽按短整数传输，long 余额拆为四段以避免大额 ME 截断。
        for (int index = 0; index < machineData.length; index++) {
            addDataSlot(DataSlot.shared(machineData, index));
        }
    }

    /** 每次原版菜单广播前获取同一时刻的余额快照，并同步开关、倍率及生产进度。 */
    @Override
    public void broadcastChanges() {
        if (converter != null) {
            machineData[0] = converter.progressTicks();
            machineData[1] = converter.getBlockState().getValue(OreExperienceConverterBlock.ENABLED) ? 1 : 0;
            machineData[MULTIPLIER_DATA] = converter.multiplier();
            long balance = converter.storedMe();
            for (int index = 0; index < 4; index++) {
                machineData[index + 2] = (int) ((balance >>> (index * 16)) & 0xFFFFL);
            }
        }
        super.broadcastChanges();
    }

    /**
     * 将按钮编号转换为预设生产档位；客户端仅预测界面，服务端验证后保存真实设置。
     * 切换档位不会扣 ME，生产仍由机器每秒按固定整轮费用执行。
     *
     * @param player 正在使用本菜单的机器放置者
     * @param buttonId 八个预设按钮的编号，范围为 0 至 7
     * @return 合法操作已处理时为 true；编号、权限或距离无效时为 false
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId < 0 || buttonId >= OreExperienceConverterBlockEntity.MULTIPLIERS.size()
                || player.containerMenu != this || player.isSpectator() || !stillValid(player)) return false;
        int selectedMultiplier = OreExperienceConverterBlockEntity.MULTIPLIERS.get(buttonId);
        if (level.isClientSide()) {
            // 同档重复点击不重置进度，与服务端的实际切换规则保持一致。
            if (machineData[MULTIPLIER_DATA] != selectedMultiplier) machineData[0] = 0;
            machineData[MULTIPLIER_DATA] = selectedMultiplier;
        } else {
            if (converter == null) return false;
            converter.setMultiplier(selectedMultiplier);
            broadcastChanges();
        }
        return true;
    }

    /** 只允许机器放置者在有效距离内操作原方块实体，防止拆除后继续访问旧库存。 */
    @Override
    public boolean stillValid(Player player) {
        if (!level.getBlockState(pos).is(ModBlocks.ORE_EXPERIENCE_CONVERTER.get())
                || !player.canInteractWithBlock(pos, 4.0)) return false;
        // 客户端不掌握归属 UUID，最终权限始终由服务端判断。
        return level.isClientSide() || converter != null && level.getBlockEntity(pos) == converter
                && player.getUUID().equals(converter.owner());
    }

    /**
     * Shift 点击容器时优先放入供能槽；机器槽可快捷取回，其余物品在背包和快捷栏间移动。
     *
     * @param player 当前操作者，必须满足菜单权限和距离校验
     * @param slotIndex 被点击的菜单槽位索引
     * @return 移动前的物品副本；无效槽位或无法移动时返回空栈
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < 0 || slotIndex >= INVENTORY_END) return ItemStack.EMPTY;
        Slot slot = getSlot(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (slotIndex < INVENTORY_START) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
        } else if (OreMachineEnergy.isContainer(stack) && !getSlot(0).hasItem()) {
            if (!moveItemStackTo(stack, 0, INVENTORY_START, false)) return ItemStack.EMPTY;
        } else if (slotIndex < HOTBAR_START) {
            if (!moveItemStackTo(stack, HOTBAR_START, INVENTORY_END, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, INVENTORY_START, HOTBAR_START, false)) {
            return ItemStack.EMPTY;
        }
        // 通知真实库存变化，使机器重置轮次并保存最新的供能容器。
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }

    /** 返回已同步的本轮等待刻数，范围为 0 到 19。 */
    public int progressTicks() { return machineData[0]; }

    /** 返回已同步的生产开关，不受当前是否缺少 ME 影响。 */
    public boolean isEnabled() { return machineData[1] != 0; }

    /** 返回已同步或本地预测的生产倍率，仅允许八个预设值，异常同步值回退 x1。 */
    public int multiplier() {
        int value = machineData[MULTIPLIER_DATA];
        return OreExperienceConverterBlockEntity.MULTIPLIERS.contains(value) ? value : 1;
    }

    /** 返回当前档位的整轮消耗，供界面预览及余额不足判断使用。 */
    public long cycleCost() { return OreExperienceConverterBlockEntity.ME_PER_EXPERIENCE * multiplier(); }

    /** 将四段同步数据按无符号 16 位还原为完整 ME 余额，兼容大额末影账户。 */
    public long storedMe() {
        return (machineData[2] & 0xFFFFL) | ((machineData[3] & 0xFFFFL) << 16)
                | ((machineData[4] & 0xFFFFL) << 32) | ((machineData[5] & 0xFFFFL) << 48);
    }
}
