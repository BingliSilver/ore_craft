package com.lazeroX.ore_craft.menu;

import com.lazeroX.ore_craft.item.OreLearningBookItem;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModMenus;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 只展示玩家背包的学习菜单，通过 Shift 点击登记物品类型，不消耗物品或改变 ME。
 * 与转化桌菜单相互独立，因此既有网络提取请求无法在此菜单中执行。
 */
public final class OreLearningMenu extends AbstractContainerMenu {
    /** 展开书本的逻辑画布尺寸，客户端绘制与菜单槽位共用同一坐标系。 */
    public static final int BOOK_WIDTH = 432;
    public static final int BOOK_HEIGHT = 248;
    /** 右页背包第一格的位置；九列槽位每格间隔 18 像素。 */
    public static final int INVENTORY_X = 232;
    public static final int INVENTORY_Y = 88;
    /** 快捷栏与背包三排分开绘制，留出纸面上的小标题空间。 */
    public static final int HOTBAR_Y = 158;
    public static final int SLOT_SPACING = 18;
    /** 与转化桌一致的学习记录上限，避免共享目录超过既有网络容量设计。 */
    private static final int MAX_LEARNED = 2048;
    /** 背包三排与快捷栏共 36 格，不包含防具和副手。 */
    private static final int INVENTORY_SLOTS = 36;
    /** 打开界面时持有宝典的手；切换或取走宝典后菜单失效。 */
    private final InteractionHand hand;
    /** 绑定原物品栈引用，阻止换成另一件宝典后继续使用旧菜单。 */
    private final ItemStack openedStack;
    /** 主手打开时绑定快捷栏格子，避免切换到另一格的宝典继续操作。 */
    private final int selectedSlot;

    /**
     * 读取服务端指定的持有手，构造客户端菜单。
     *
     * @param id 菜单编号
     * @param inventory 玩家背包
     * @param extra 包含主手或副手枚举的打开数据
     */
    public OreLearningMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(id, inventory, extra.readEnum(InteractionHand.class));
    }

    /**
     * 创建绑定当前宝典的菜单，槽位坐标与学习界面保持一致。
     *
     * @param id 菜单编号
     * @param inventory 玩家背包
     * @param hand 当前持有宝典的手
     */
    public OreLearningMenu(int id, Inventory inventory, InteractionHand hand) {
        super(ModMenus.ORE_LEARNING_MENU.get(), id);
        this.hand = hand;
        openedStack = inventory.player.getItemInHand(hand);
        selectedSlot = inventory.selected;
        // 菜单不创建临时库存；关闭界面无需返还任何学习样本。
        // 所有背包槽位放在展开书本的右页，左页仅显示说明和反馈。
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column,
                        INVENTORY_X + column * SLOT_SPACING, INVENTORY_Y + row * SLOT_SPACING));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * SLOT_SPACING, HOTBAR_Y));
        }
    }

    /**
     * 服务端要求原宝典仍在原持有位置；客户端等待背包同步时允许继续显示。
     *
     * @param player 操作者
     * @return 菜单仍可交互时为 true
     */
    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide()
                || player.getItemInHand(hand) == openedStack
                && openedStack.getItem() instanceof OreLearningBookItem
                && (hand == InteractionHand.OFF_HAND || player.getInventory().selected == selectedSlot);
    }

    /**
     * Shift 点击时按物品默认状态学习类型，保留样本的数量、附魔、耐久及其他组件。
     * 仅接受当前有效菜单的真实背包格子；鼠标持有物品时拒绝学习，与转化桌规则一致。
     *
     * @param player 发起操作的玩家
     * @param slotIndex 菜单中的背包格子编号，范围 0 至 35
     * @return 始终返回空栈，结束原版快速移动重试，避免无损学习反复触发
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.containerMenu != this
                || !stillValid(player) || slotIndex < 0 || slotIndex >= INVENTORY_SLOTS
                || !getCarried().isEmpty()) return ItemStack.EMPTY;
        ItemStack sample = getSlot(slotIndex).getItem();
        if (sample.isEmpty()) return ItemStack.EMPTY;

        // 目录只存物品 ID；附魔装备或装满 ME 的容器也只学习默认物品，绝不复制其组件。
        ItemStack learningStack = new ItemStack(sample.getItem());
        if (!OreConversionPrices.isPlain(learningStack)) {
            status(serverPlayer, "special_state");
            return ItemStack.EMPTY;
        }
        if (!OreConversionPrices.canLearn(learningStack)) {
            status(serverPlayer, "unpriced");
            return ItemStack.EMPTY;
        }
        OreConversionSavedData data = OreConversionSavedData.get(serverPlayer);
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(sample.getItem());
        // 已学习物品即使在目录满额时也能查询；只有新增记录受上限约束。
        if (!data.account(serverPlayer).knows(itemId)
                && data.account(serverPlayer).learned().size() >= MAX_LEARNED) {
            status(serverPlayer, "learn_limit");
            return ItemStack.EMPTY;
        }
        boolean learned = data.learn(serverPlayer, itemId);
        // 学习只写共享存档并刷新客户端提示，不调用回收、ME 转移或提取逻辑。
        OreConversionNetwork.sendState(serverPlayer, containerId);
        status(serverPlayer, learned ? "learned" : "already_learned");
        return ItemStack.EMPTY;
    }

    /**
     * 复用转化桌的学习结果提示协议，消息只在对应菜单中显示。
     *
     * @param player 消息接收者
     * @param key 学习成功、重复学习或拒绝原因对应的消息键
     */
    private void status(ServerPlayer player, String key) {
        OreConversionNetwork.sendStatus(player, containerId, key, 0);
    }
}
