package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.client.MineralTeleportScepterScreen;
import com.lazeroX.ore_craft.menu.MineralTeleportScepterMenu;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.player.TeleportWaypointSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 权杖请求与本人当前维度坐标列表的双向同步；处理器均在对应游戏主线程执行。 */
public final class MineralTeleportScepterNetwork {
    /** 网络注册工具不创建实例。 */
    private MineralTeleportScepterNetwork() {}

    /** @param event 通用初始化事件，使用共享通道的 8、9 号消息 */
    public static void register(FMLCommonSetupEvent event) {
        OreConversionNetwork.CHANNEL.messageBuilder(Request.class, 8, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::encode).decoder(Request::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    // 菜单编号阻止关闭或重新打开界面之后到达的旧请求。
                    if (player != null && player.containerMenu instanceof MineralTeleportScepterMenu menu
                            && menu.containerId == packet.containerId()) menu.handle(player, packet);
                }).add();
        OreConversionNetwork.CHANNEL.messageBuilder(State.class, 9, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(State::encode).decoder(State::decode)
                .consumerMainThread((packet, context) -> MineralTeleportScepterScreen.receive(packet)).add();
    }

    /** @param player 唯一接收者 @param state 当前菜单的服务端快照，不向其他玩家广播坐标 */
    public static void sync(ServerPlayer player, State state) {
        OreConversionNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), state);
    }

    /** 固定操作集合，客户端不能通过请求提交目的维度或坐标。 */
    public enum Operation {
        /** 将打开菜单时捕获的位置与输入名称一起保存。 */
        SAVE,
        /** 删除本人指定 UUID 的标记。 */
        DELETE,
        /** 为本人指定 UUID 的标记生成入口，实际传送要等待玩家进入成形的门。 */
        TELEPORT;

        /** 枚举实例只由 JVM 创建，网络按固定枚举次序编码。 */
        Operation() {}
    }

    /**
     * 客户端操作载荷；保存只发送名称，其他操作只发送稳定标记编号。
     * @param containerId 当前菜单编号
     * @param operation 操作类型
     * @param waypointId 删除或传送的目标；保存时为 null
     * @param name 保存时的名称；其他操作为空字符串
     */
    public record Request(int containerId, Operation operation, @Nullable UUID waypointId, String name) {
        /** 创建请求；服务端仍会校验菜单归属、阶段、名称和标记所有者。 */
        public Request {}

        /** @param buffer 按操作类型写入必要字段，避免传输无用的位置数据 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeEnum(operation);
            if (operation == Operation.SAVE) buffer.writeUtf(name, TeleportWaypoint.MAX_NAME_LENGTH);
            else buffer.writeUUID(waypointId);
        }

        /** @param buffer 客户端请求 @return 名称长度受限且操作类型已解码的请求 */
        public static Request decode(FriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            Operation operation = buffer.readEnum(Operation.class);
            return operation == Operation.SAVE
                    ? new Request(containerId, operation, null, buffer.readUtf(TeleportWaypoint.MAX_NAME_LENGTH))
                    : new Request(containerId, operation, buffer.readUUID(), "");
        }
    }

    /**
     * 服务端列表快照；打开菜单、删除完成或操作失败时刷新界面，保存或生成入口成功则关闭菜单。
     * @param containerId 接收菜单编号
     * @param waypoints 当前玩家在所在维度的全部标记，其他维度记录不下发
     * @param naming 是否仍在命名阶段
     * @param status 操作结果的本地化后缀
     */
    public record State(int containerId, List<TeleportWaypoint> waypoints, boolean naming, String status) {
        /** 冻结列表，避免发送排队期间调用方修改内容。 */
        public State { waypoints = List.copyOf(waypoints); }

        /** @param buffer 写入有数量上限的完整快照 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeBoolean(naming);
            buffer.writeUtf(status, 64);
            buffer.writeVarInt(waypoints.size());
            waypoints.forEach(entry -> entry.encode(buffer));
        }

        /** @param buffer 服务端快照 @return 数量受限的展示状态 @throws IllegalArgumentException 条目数越界 */
        public static State decode(FriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            boolean naming = buffer.readBoolean();
            String status = buffer.readUtf(64);
            int count = buffer.readVarInt();
            if (count < 0 || count > TeleportWaypointSavedData.MAX_WAYPOINTS) {
                throw new IllegalArgumentException("Invalid teleport waypoint count: " + count);
            }
            List<TeleportWaypoint> entries = new ArrayList<>(count);
            for (int index = 0; index < count; index++) entries.add(TeleportWaypoint.decode(buffer));
            return new State(containerId, entries, naming, status);
        }
    }
}
