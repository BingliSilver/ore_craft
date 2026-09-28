package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.event.MineralTimeAcceleration;
import com.lazeroX.ore_craft.menu.MineralTimeScepterMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.SimpleMenuProvider;

import java.util.List;

/**
 * 矿质时间权杖，保存每件权杖选择的倍率，并把目标方块交给服务端限时加速器。
 *
 * <p>普通右键打开设置界面；主手潜行右键方块时按界面所选倍率和时长一次性支付 ME。
 * 两项选择保存在物品自定义数据中，生效中的计时由服务端统一管理。</p>
 */
public final class MineralTimeScepterItem extends Item {
    /** 新权杖默认选择的倍率。 */
    public static final int MIN_MULTIPLIER = 2;
    /** 界面允许选择的六档倍率；不可变列表同时用于服务器校验。 */
    public static final List<Integer> MULTIPLIERS = List.of(2, 4, 8, 16, 32, 64);
    /** 界面允许选择的五档施加秒数；服务端只接受这些值。 */
    public static final List<Integer> DURATIONS = List.of(10, 30, 60, 120, 300);
    /** 2 倍速每秒消耗的全局 ME；每提高一档翻倍。 */
    private static final long BASE_COST_PER_SECOND = 1_024L;
    /** 新权杖尚未写入设置时的默认时长，单位秒。 */
    public static final int DEFAULT_DURATION_SECONDS = 30;
    /** 存放当前倍率的物品自定义数据键。 */
    private static final String MULTIPLIER_KEY = "MineralTimeMultiplier";
    /** 存放所选持续秒数的物品自定义数据键。 */
    private static final String DURATION_KEY = "MineralTimeDurationSeconds";

    /**
     * 创建一件不可堆叠的权杖；倍率尚未写入时按 2 倍处理。
     *
     * @param properties 注册时提供的物品属性
     */
    public MineralTimeScepterItem(Properties properties) {
        super(properties);
    }

    /**
     * 从物品数据读取倍率，并把旧数据或异常值安全地回退到 2 倍。
     *
     * @param stack 待读取的权杖
     * @return 2、4、8、16、32 或 64
     */
    public static int multiplier(ItemStack stack) {
        int value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getInt(MULTIPLIER_KEY);
        return MULTIPLIERS.contains(value) ? value : MIN_MULTIPLIER;
    }

    /**
     * 从物品数据读取所选时长；没有设置或数据异常时按 30 秒处理。
     *
     * @param stack 待读取的权杖
     * @return 五档合法持续秒数之一
     */
    public static int durationSeconds(ItemStack stack) {
        int value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getInt(DURATION_KEY);
        return DURATIONS.contains(value) ? value : DEFAULT_DURATION_SECONDS;
    }

    /**
     * 验证界面选择是否属于预设档位，供菜单和施加逻辑共用。
     *
     * @param multiplier 申请的倍率
     * @param durationSeconds 申请的持续秒数
     * @return 两项都在预设列表中时为 true
     */
    public static boolean isValidConfiguration(int multiplier, int durationSeconds) {
        return MULTIPLIERS.contains(multiplier) && DURATIONS.contains(durationSeconds);
    }

    /**
     * 将经过服务器校验的倍率和时长一次写入主手权杖，保证两项设置同步。
     *
     * @param stack 仍在玩家主手中的权杖
     * @param multiplier 预设倍率
     * @param durationSeconds 预设持续秒数
     */
    public static void saveConfiguration(ItemStack stack, int multiplier, int durationSeconds) {
        if (!isValidConfiguration(multiplier, durationSeconds)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt(MULTIPLIER_KEY, multiplier);
            tag.putInt(DURATION_KEY, durationSeconds);
        });
    }

    /**
     * 按当前倍率计算每秒基准费用，2 倍为 1024 ME，64 倍为 32768 ME。
     *
     * @param multiplier 已校验的倍率
     * @return 每秒应从施加者账户扣除的 ME
     */
    public static long costPerSecond(int multiplier) {
        return BASE_COST_PER_SECOND * (multiplier / MIN_MULTIPLIER);
    }

    /**
     * 计算按所选时长施加一次效果时应立即支付的总价。
     *
     * @param multiplier 已校验的倍率
     * @param durationSeconds 所选持续秒数
     * @return 本次施加的总 ME 费用
     */
    public static long totalCost(int multiplier, int durationSeconds) {
        return costPerSecond(multiplier) * durationSeconds;
    }

    /**
     * 在物品提示中显示当前倍率、费用与两种右键操作。
     *
     * @param stack 当前权杖
     * @param context 物品提示上下文
     * @param tooltip 待添加文字的列表
     * @param flag 提示显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        int selected = multiplier(stack);
        int duration = durationSeconds(stack);
        tooltip.add(Component.translatable("tooltip.ore_craft.mineral_time_scepter.mode",
                selected, costPerSecond(selected)).withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("tooltip.ore_craft.mineral_time_scepter.switch")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.mineral_time_scepter.apply",
                duration, totalCost(selected, duration))
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 在目标方块自身交互前处理右键：普通右键打开设置界面，主手潜行右键施加加速。
     * 服务端负责打开菜单和扣费，客户端只消费交互以阻止方块界面抢占操作。
     *
     * @param stack 当前正在使用的权杖
     * @param context 本次方块右键的世界、玩家、手和目标位置
     * @return 主手操作已处理时为成功，副手操作交由其他交互逻辑处理
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Level level = context.getLevel();

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                MineralTimeAcceleration.apply(serverPlayer, context.getClickedPos(),
                        multiplier(stack), durationSeconds(stack));
            } else {
                openSettings(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 对空气普通右键时同样打开设置界面；潜行空挥不消耗 ME。
     *
     * @param level 当前世界
     * @param player 持有权杖的玩家
     * @param hand 使用权杖的手
     * @return 本次操作及物品栈；副手或潜行空挥不处理
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            openSettings(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 打开绑定当前主手权杖的服务器菜单；关闭后设置仍留在物品上。
     *
     * @param player 当前服务端玩家
     */
    private static void openSettings(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, user) -> new MineralTimeScepterMenu(id, inventory),
                Component.translatable("container.ore_craft.mineral_time_scepter")));
    }
}
