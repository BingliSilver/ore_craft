package com.lazeroX.ore_craft.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.MeValueFiles;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;

/**
 * 注册矿质账户、世界回收规则及 ME 定价命令，供管理员、创造模式玩家及服务器控制台使用。
 * 清空操作只移除共享学习目录；ME 余额、背包物品和机器库存均保持原样。
 */
public final class OreConversionCommands {
    /** 管理员及控制台需达到等级 2；创造模式玩家另有命令入口授权。 */
    private static final int REQUIRED_PERMISSION = 2;

    /** 命令工具不保存会话状态，也不允许创建实例。 */
    private OreConversionCommands() {}

    /**
     * 注册 {@code /orecraft clearlearned [targets]}，省略目标时操作执行者本人。
     * 显式目标可使用玩家名、在线玩家 UUID 或 {@code @a} 等选择器。
     * {@code /orecraft convertall [true|false]} 查询或修改当前世界的全物品回收开关。
     * {@code setme} 与 {@code sethand} 设置物品单价，默认写入存档，也可显式选择全局作用域。
     *
     * @param event 全局事件总线发送的服务端命令注册事件
     */
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("orecraft")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION)
                        || source.getEntity() instanceof ServerPlayer player && player.isCreative())
                .then(Commands.literal("convertall")
                        .executes(context -> queryConvertAll(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setConvertAll(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")))))
                .then(Commands.literal("clearlearned")
                        .executes(context -> clearLearned(context.getSource(),
                                List.of(context.getSource().getPlayerOrException())))
                        // 原版 players 参数会拒绝 UUID；使用 entities 解析后仅查找在线玩家。
                        // 非玩家实体不会成为目标，也不会受到清空操作影响。
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> clearLearned(context.getSource(),
                                        EntityArgument.getPlayers(context, "targets"))))));
        event.getDispatcher().register(Commands.literal("orecraft")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION)
                        || source.getEntity() instanceof ServerPlayer player && player.isCreative())
                .then(Commands.literal("setme")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .then(Commands.argument("value", LongArgumentType.longArg(1))
                                        .executes(context -> setMe(context.getSource(),
                                                ResourceLocationArgument.getId(context, "item"),
                                                LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.WORLD))
                                        .then(Commands.literal("global").executes(context -> setMe(context.getSource(),
                                                ResourceLocationArgument.getId(context, "item"),
                                                LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.GLOBAL)))
                                        .then(Commands.literal("world").executes(context -> setMe(context.getSource(),
                                                ResourceLocationArgument.getId(context, "item"),
                                                LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.WORLD))))))
                .then(Commands.literal("sethand")
                        .then(Commands.argument("value", LongArgumentType.longArg(1))
                                .executes(context -> setHeldMe(context.getSource(),
                                        LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.WORLD))
                                .then(Commands.literal("global").executes(context -> setHeldMe(context.getSource(),
                                        LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.GLOBAL)))
                                .then(Commands.literal("world").executes(context -> setHeldMe(context.getSource(),
                                        LongArgumentType.getLong(context, "value"), MeValueFiles.Scope.WORLD))))));
    }

    /** 写入指定物品的价格，并立即刷新服务端与在线客户端。 */
    private static int setMe(CommandSourceStack source, ResourceLocation id, long value, MeValueFiles.Scope scope) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            source.sendFailure(Component.translatable("commands.ore_craft.me.unknown_item", id));
            return 0;
        }
        try {
            MeValueFiles.write(source.getServer(), scope, id, value);
            OreConversionPrices.reloadExternalValues(source.getServer());
            // 价格快照由服务端权威生成，写盘后立即同步，保证已打开的转化界面也能看到新值。
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                OreConversionNetwork.sendPrices(player);
                OreConversionNetwork.sendState(player, player.containerMenu.containerId);
            }
            source.sendSuccess(() -> Component.translatable("commands.ore_craft.me.set", id, value, scope == MeValueFiles.Scope.GLOBAL ? "global" : "world"), true);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.translatable("commands.ore_craft.me.write_failed"));
            return 0;
        }
    }

    /** 将主手物品转换为 ID 后复用显式物品设置逻辑。 */
    private static int setHeldMe(CommandSourceStack source, long value, MeValueFiles.Scope scope) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.translatable("commands.ore_craft.me.player_only"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("commands.ore_craft.me.empty_hand"));
            return 0;
        }
        return setMe(source, BuiltInRegistries.ITEM.getKey(held.getItem()), value, scope);
    }

    /**
     * 向命令来源显示世界的当前回收开关，不修改存档或广播管理操作。
     *
     * @param source 已通过权限校验的玩家或服务器控制台
     * @return 开启时为 1，关闭时为 0，可供命令方块读取
     */
    private static int queryConvertAll(CommandSourceStack source) {
        boolean enabled = OreConversionSavedData.get(source.getServer()).convertAllItems();
        source.sendSuccess(() -> Component.translatable("commands.ore_craft.convertall.query", enabled), false);
        return enabled ? 1 : 0;
    }

    /**
     * 保存世界的回收开关，并立即刷新所有在线玩家的价格提示、输入权限和已打开菜单。
     * 机器在每次投入和结算时重新检查回收规则，因此关闭开关后会停止处理不再允许的物品。
     *
     * @param source 已通过管理员权限校验的来源
     * @param enabled true 开启全物品回收，false 恢复默认来源限制
     * @return 命令成功执行时返回 1
     */
    private static int setConvertAll(CommandSourceStack source, boolean enabled) {
        OreConversionSavedData.get(source.getServer()).setConvertAllItems(enabled);
        // 即使重复设置同一个值也重新同步，使当前菜单和悬停提示立即采用服务端规则。
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            OreConversionNetwork.sendPrices(player);
            OreConversionNetwork.sendState(player, player.containerMenu.containerId);
        }
        source.sendSuccess(() -> Component.translatable("commands.ore_craft.convertall.set", enabled), true);
        return 1;
    }

    /**
     * 清空已解析玩家的共享学习目录，并立即刷新其客户端和当前打开的转化桌。
     * 机器每刻都会核对拥有者的学习记录，因此失去学习权限的目标会自动停止生产。
     *
     * @param source 已通过根命令管理权限校验的来源，用于结果反馈
     * @param targets 原版选择器解析出的在线玩家集合，无重复目标
     * @return 已处理玩家数量，包括学习记录本来就为空的玩家
     */
    private static int clearLearned(CommandSourceStack source, Collection<ServerPlayer> targets) {
        OreConversionSavedData data = OreConversionSavedData.get(source.getServer());
        // 多个账户的总记录数用 long 汇总，避免大量目标时整数相加溢出。
        long removed = 0;
        for (ServerPlayer target : targets) {
            removed += data.clearLearned(target.getUUID());
            // 使用当前菜单编号，除了全局提示和目录，也更新仍然打开的转化桌缓存。
            OreConversionNetwork.sendState(target, target.containerMenu.containerId);
        }
        Component result = targets.size() == 1
                ? Component.translatable("commands.ore_craft.clearlearned.single",
                        targets.iterator().next().getDisplayName(), removed)
                : Component.translatable("commands.ore_craft.clearlearned.multiple", targets.size(), removed);
        // 将管理操作反馈给执行者，并按原版规则记录或广播给服务器管理员。
        source.sendSuccess(() -> result, true);
        return targets.size();
    }
}
