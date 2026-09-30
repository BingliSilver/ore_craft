package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 以玩家随身矿质容器为能源的照明核心，提供移动时自动放火把及手动制造火把的能力。
 *
 * <p>每件核心默认开启自动照明，潜行右键切换的状态随物品保存。所有扣费、放置和物品
 * 发放只在服务端执行；末影容器存在时只使用全局账户，否则合并扣除普通容器内的 ME。
 * 玩家移动跨入新方块且有效光照低于 8 时，尝试在脚下或水平相邻位置放置一根普通火把。
 * 手动制造的火把优先放入背包，放不下的部分掉落到玩家身边，并使用相同的成功提示。</p>
 */
public final class MineralIlluminationCoreItem extends Item {
    /** 自动照明阈值；光照等级范围为 0～15，低于此值才放置。 */
    private static final int LIGHT_THRESHOLD = 8;
    /** 每次普通右键完整制造的火把数量。 */
    private static final int TORCH_BATCH_SIZE = 16;
    /** 物品自定义数据中的关闭标记；未写入时默认开启。 */
    private static final String DISABLED_KEY = "OreCraftIlluminationDisabled";
    /**
     * 仅服务端使用的移动记录，不写入物品或存档；弱引用使离线玩家的记录可自动释放。
     * 值不持有玩家引用，且通过连续刻号防止重新获得核心时把旧位置误判成移动。
     */
    private final Map<ServerPlayer, MovementState> movement = new WeakHashMap<>();

    /**
     * 创建照明核心；移动记录随本次服务器生命周期存在，开关则随物品永久保存。
     *
     * @param properties 物品属性，注册时应设置最大堆叠数为 1
     */
    public MineralIlluminationCoreItem(Properties properties) {
        super(properties);
    }

    /**
     * 读取自动照明开关，不改变物品组件。
     *
     * @param stack 待读取的核心
     * @return 没有关闭标记或标记为 false 时返回 true
     */
    private static boolean isEnabled(ItemStack stack) {
        return !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBoolean(DISABLED_KEY);
    }

    /**
     * 显示持久化开关、触发阈值、操作方式以及供能优先级。
     *
     * @param stack 当前核心
     * @param context 提示上下文
     * @param tooltip 待追加的提示列表
     * @param flag 提示显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.illumination.state",
                Component.translatable(isEnabled(stack) ? "message.ore_craft.illumination.enabled"
                        : "message.ore_craft.illumination.disabled")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.ore_craft.illumination.auto", LIGHT_THRESHOLD)
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.ore_craft.illumination.toggle").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.illumination.create", TORCH_BATCH_SIZE)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.illumination.energy").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 对空气右键：潜行时切换开关，否则付费制造 16 根火把，背包放不下的部分掉落到地上。
     *
     * @param level 使用物品的世界
     * @param player 持有者
     * @param hand 使用的手，支持主手和副手
     * @return 消费此次交互，客户端仅预测成功，实际结果由服务端提示
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) handleUse(serverPlayer, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 在方块交互之前执行核心操作，使对着箱子等方块右键也能制造火把或切换开关。
     *
     * @param stack 正在使用的核心
     * @param context 方块右键上下文
     * @return 消费交互，避免方块继续打开界面或重复执行物品操作
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) handleUse(player, stack);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /**
     * 处理两种服务端右键操作；切换开关不需要容器，也不收取 ME。
     * 制造火把按完整批次扣费，背包空间不足时仍正常生成并掉落剩余部分。
     *
     * @param player 使用核心的服务端玩家
     * @param stack 当前手持核心
     */
    private static void handleUse(ServerPlayer player, ItemStack stack) {
        if (player.isSpectator()) return;
        if (player.isShiftKeyDown()) {
            boolean enabled = !isEnabled(stack);
            // 仅修改专用键，保留其他模组或命令附加的自定义数据。
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(DISABLED_KEY, !enabled));
            player.getInventory().setChanged();
            player.displayClientMessage(Component.translatable(enabled
                    ? "message.ore_craft.illumination.enabled" : "message.ore_craft.illumination.disabled"), true);
            return;
        }

        long unitCost = OreConversionPrices.price(Items.TORCH).orElse(0);
        if (unitCost <= 0 || unitCost > Long.MAX_VALUE / TORCH_BATCH_SIZE) {
            message(player, "price_missing");
            return;
        }
        EnergyPayment payment = withdraw(player, unitCost * TORCH_BATCH_SIZE);
        if (payment == null) {
            message(player, "energy_missing");
            return;
        }

        // 原版回填入口逐槽合并并同步物品，无法放入的剩余火把自动掉落在玩家身边。
        // 不使用 Inventory.add 后直接检查余量，避免创造模式在背包满时清空余量而吞掉产物。
        player.getInventory().placeItemBackInInventory(new ItemStack(Items.TORCH, TORCH_BATCH_SIZE));
        payment.finish();
        player.displayClientMessage(Component.translatable("message.ore_craft.illumination.created",
                TORCH_BATCH_SIZE, payment.cost()), true);
    }

    /**
     * 每刻记录玩家位置，只有连续携带期间跨入新方块时才检测自动照明。
     *
     * @param stack 正在轮询的核心
     * @param level 当前世界
     * @param entity 持有物品的实体
     * @param slotId 全局背包槽位
     * @param isSelected 是否为选中快捷栏槽位，自动照明不要求手持
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!(entity instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
        Inventory inventory = player.getInventory();
        boolean enabled = false;
        boolean first = true;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack core = inventory.getItem(slot);
            if (!core.is(this)) continue;
            // 第一个核心统一轮询；只要任意一件开启就生效，多件核心不会在同一刻重复扣费。
            if (first && core != stack) return;
            first = false;
            enabled |= isEnabled(core);
        }

        BlockPos feet = player.blockPosition();
        MovementState previous = movement.put(player, new MovementState(feet, level.dimension(), player.tickCount));
        if (!enabled || previous == null || previous.tick() != player.tickCount - 1
                || !previous.dimension().equals(level.dimension()) || previous.position().equals(feet)) return;
        // 有效光照包含方块光与经昼夜衰减后的天空光，白天露天环境不会误放火把。
        if (!player.getAbilities().mayBuild || !level.hasChunkAt(feet)
                || level.getMaxLocalRawBrightness(feet) >= LIGHT_THRESHOLD) return;
        long cost = OreConversionPrices.price(Items.TORCH).orElse(0);
        if (cost <= 0) return;

        // 优先放脚下，其次检查四个水平相邻方块；一次移动最多成功放置一根。
        if (tryPlaceTorch(player, feet, cost)) return;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (tryPlaceTorch(player, feet.relative(direction), cost)) return;
        }
    }

    /**
     * 尝试在一个空气位置付费放置火把；支持地面火把和墙壁火把，失败会返还预扣费用。
     *
     * @param player 放置者，用于权限及领地事件判断
     * @param position 玩家脚边的候选位置
     * @param cost 一根火把当前的数据包 ME 单价
     * @return 实际放置成功并完成扣费时返回 true
     */
    private static boolean tryPlaceTorch(ServerPlayer player, BlockPos position, long cost) {
        Level level = player.level();
        if (!level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)
                || !level.getBlockState(position).isAir() || !level.mayInteract(player, position)) return false;
        ItemStack torch = new ItemStack(Items.TORCH);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
        BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, torch, hit);
        if (!player.mayUseItemAt(position, Direction.UP, torch)) return false;
        // 先按普通火把的放置规则检查支撑；不能在悬空或水中凭空生成光源。
        boolean groundSupported = Blocks.TORCH.defaultBlockState().canSurvive(level, position);
        var wallState = Blocks.WALL_TORCH.getStateForPlacement(context);
        if (!groundSupported && wallState == null) return false;
        EnergyPayment payment = withdraw(player, cost);
        if (payment == null) return false;

        // 使用 ItemStack 的标准入口，保留 NeoForge 放置事件、领地取消与方块快照回滚。
        boolean placed = false;
        try {
            InteractionResult result = torch.useOn(context);
            placed = result.consumesAction() && (level.getBlockState(position).is(Blocks.TORCH)
                    || level.getBlockState(position).is(Blocks.WALL_TORCH));
        } finally {
            // 领地取消、支撑检查失败或放置回调抛错时都退回预扣款，不吞掉异常。
            if (!placed) payment.refund();
        }
        if (!placed) return false;
        payment.finish();
        return true;
    }

    /**
     * 先验证余额再一次性扣款，普通容器不足时不会留下部分扣费。
     *
     * @param player 支付者，容器必须位于其随身背包或副手等 Inventory 槽位
     * @param cost 正数费用
     * @return 可完成或退款的付款记录；无容器或余额不足时返回 null
     */
    private static EnergyPayment withdraw(ServerPlayer player, long cost) {
        if (cost <= 0) return null;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof EnderOreContainerItem) {
                // 有末影容器时只消费全局 ME，余额不足也不混用普通容器。
                return OreConversionSavedData.get(player).debit(player, cost)
                        ? new EnergyPayment(player, cost, true, List.of()) : null;
            }
        }

        long remaining = cost;
        List<ContainerCharge> charges = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof OreContainerItem container)) continue;
            long amount = Math.min(remaining, container.storedMe(stack));
            if (amount > 0) charges.add(new ContainerCharge(stack, container, amount));
            remaining -= amount;
        }
        if (remaining > 0) return null;
        // 预检完全通过后才修改组件；用剩余费用递减，避免累加多个大容量容器时溢出。
        for (ContainerCharge charge : charges) {
            charge.container().setStoredMe(charge.stack(), charge.container().storedMe(charge.stack()) - charge.amount());
        }
        return new EnergyPayment(player, cost, false, charges);
    }

    /**
     * 发送手动使用失败的本地化提示，自动检测失败时保持安静。
     *
     * @param player 提示接收者
     * @param suffix 提示键后缀
     */
    private static void message(ServerPlayer player, String suffix) {
        player.displayClientMessage(Component.translatable("message.ore_craft.illumination." + suffix), true);
    }

    /**
     * 玩家上一次携带核心时的位置快照，仅供服务端移动判定。
     *
     * @param position 上一刻的脚部方块位置
     * @param dimension 所在维度，跨维度传送不当作步行照明
     * @param tick 玩家实体刻号，用于排除首次获得、重新登录和携带中断
     */
    private record MovementState(BlockPos position, ResourceKey<Level> dimension, int tick) {}

    /**
     * 从一个普通容器预扣的费用；只修改该容器的 ME 字段，不消耗容器本身。
     *
     * @param stack 背包内真实容器物品栈
     * @param container 对应的普通容器类型
     * @param amount 本次预扣的正数 ME
     */
    private record ContainerCharge(ItemStack stack, OreContainerItem container, long amount) {}

    /**
     * 一次同步操作的付款记录，成功后同步账户，放置被拒绝时返还原渠道。
     *
     * @param player 支付者
     * @param cost 总费用
     * @param global 是否从全局账户扣款
     * @param charges 普通容器的分摊费用，全局付款时为空
     */
    private record EnergyPayment(ServerPlayer player, long cost, boolean global, List<ContainerCharge> charges) {
        /** 完成交易并同步背包组件及末影账户余额；调用方必须确认产物已交付。 */
        private void finish() {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            if (global) OreConversionNetwork.sendState(player, -1);
        }

        /** 放置失败时退款，不发送成功提示；普通容器保留其余自定义组件。 */
        private void refund() {
            if (global) {
                OreConversionSavedData.get(player).credit(player, cost);
            } else {
                for (ContainerCharge charge : charges) {
                    charge.container().setStoredMe(charge.stack(),
                            charge.container().storedMe(charge.stack()) + charge.amount());
                }
            }
        }
    }
}
