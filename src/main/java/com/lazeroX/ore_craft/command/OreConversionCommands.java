package com.lazeroX.ore_craft.command;

import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;

/**
 * 注册矿质账户的服务端管理命令，供开启作弊的玩家、管理员及服务器控制台使用。
 * 清空操作只移除共享学习目录；ME 余额、背包物品和机器库存均保持原样。
 */
public final class OreConversionCommands {
    /** 与原版管理命令一致，要求命令来源具有等级 2 或更高权限。 */
    private static final int REQUIRED_PERMISSION = 2;

    /** 命令工具不保存会话状态，也不允许创建实例。 */
    private OreConversionCommands() {}

    /**
     * 注册 {@code /orecraft clearlearned [targets]}，省略目标时操作执行者本人。
     * 显式目标可使用玩家名、在线玩家 UUID 或 {@code @a} 等选择器。
     *
     * @param event 全局事件总线发送的服务端命令注册事件
     */
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("orecraft")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION))
                .then(Commands.literal("clearlearned")
                        .executes(context -> clearLearned(context.getSource(),
                                List.of(context.getSource().getPlayerOrException())))
                        // 原版 players 参数会拒绝 UUID；使用 entities 解析后仅查找在线玩家。
                        // 非玩家实体不会成为目标，也不会受到清空操作影响。
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> clearLearned(context.getSource(),
                                        EntityArgument.getPlayers(context, "targets"))))));
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
