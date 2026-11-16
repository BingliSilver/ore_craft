package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.event.GeologicalDetection;
import com.lazeroX.ore_craft.menu.GeologicalDetectorMenu;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 地质探测器：潜行右键配置矿石和球形搜索半径，普通右键支付随身容器 ME 寻找最近矿石。
 * 配置随单件物品保存，搜索、最近距离判定和扣费均由服务端完成，不消耗物品耐久。
 */
public final class GeologicalDetectorItem extends Item {
    /** 成功探测后引导粒子的持续秒数；客户端计时与服务端提示共用，避免显示不一致。 */
    public static final int GUIDE_DURATION_SECONDS = 30;
    /** 四档半径，单位为格；索引同时决定 2、4、8、16 倍的距离费用。 */
    public static final List<Integer> RADII = List.of(16, 32, 64, 128);
    /** 单件探测器保存目标方块注册名及范围预设索引的专用键。 */
    private static final String ORE_KEY = "GeologicalDetectorOre";
    private static final String RANGE_KEY = "GeologicalDetectorRange";

    /** @param properties 注册属性，要求不可堆叠以独立保存配置 */
    public GeologicalDetectorItem(Properties properties) { super(properties); }

    /** @param stack 探测器 @return 合法的半径索引；旧物品或损坏数据默认使用 16 格 */
    public static int rangeIndex(ItemStack stack) {
        int index = stack.hasTag() ? stack.getTag().getInt(RANGE_KEY) : 0;
        return index >= 0 && index < RADII.size() ? index : 0;
    }

    /** @param stack 探测器 @return 目标方块；未配置或矿石所属模组被移除时默认选择铁矿石 */
    public static Block selectedOre(ItemStack stack) {
        ResourceLocation id = stack.hasTag() ? ResourceLocation.tryParse(stack.getTag().getString(ORE_KEY)) : null;
        return id != null && BuiltInRegistries.BLOCK.containsKey(id)
                ? BuiltInRegistries.BLOCK.get(id) : Blocks.IRON_ORE;
    }

    /** @param index 已校验的范围索引 @return 对应 2、4、8、16 倍费用 */
    public static int multiplier(int index) { return 2 << index; }

    /**
     * 与传送器共用每格单价及三维距离向上取整规则，再乘搜索范围倍率。
     * @param origin 使用时玩家脚部坐标
     * @param target 矿石中心坐标
     * @param index 合法范围索引
     * @return 一次成功探测消耗的正数 ME
     */
    public static long energyCost(Vec3 origin, Vec3 target, int index) {
        return Math.max(1L, (long) Math.ceil(origin.distanceTo(target)))
                * TeleportWaypoint.ME_PER_BLOCK * multiplier(index);
    }

    /** @param stack 探测器 @param ore 服务端验证的矿石 @param index 合法范围索引；调用方负责同步背包 */
    public static void configure(ItemStack stack, Block ore, int index) {
        stack.getOrCreateTag().putString(ORE_KEY, BuiltInRegistries.BLOCK.getKey(ore).toString());
        stack.getOrCreateTag().putInt(RANGE_KEY, index);
    }

    /** @param level 当前世界 @param player 使用者 @param hand 使用手 @return 消费交互，避免双手重复执行 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) handleUse(serverPlayer, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /** 对着箱子等交互方块时也优先执行探测器操作。@param stack 探测器 @param context 交互上下文 @return 交互结果 */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) handleUse(player, context.getHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /** @param player 服务端玩家 @param hand 当前使用手；旁观者不能配置或支付探测 */
    private static void handleUse(ServerPlayer player, InteractionHand hand) {
        if (player.isSpectator()) return;
        if (player.isShiftKeyDown()) {
            NetworkHooks.openScreen(player, new SimpleMenuProvider(
                    (id, inventory, owner) -> new GeologicalDetectorMenu(id, inventory, hand),
                    Component.translatable("item.ore_craft.geological_detector")), buffer -> buffer.writeEnum(hand));
        } else {
            GeologicalDetection.start(player, hand);
        }
    }

    /** 显示当前矿石、范围和完整费用公式。@param stack 探测器 @param level 世界 @param tooltip 提示 @param flag 提示模式 */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        int index = rangeIndex(stack);
        tooltip.add(Component.translatable("tooltip.ore_craft.detector.target", selectedOre(stack).getName())
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("gui.ore_craft.detector.range", RADII.get(index), multiplier(index))
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.ore_craft.detector.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.ore_craft.detector.cost", TeleportWaypoint.ME_PER_BLOCK)
                .withStyle(ChatFormatting.GRAY));
    }
}
