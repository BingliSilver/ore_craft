package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.client.SoulSeparationClient;
import com.lazeroX.ore_craft.event.SoulSeparation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;

/** 灵魂出窍的单向状态同步与提前回归请求；身体位置、费用和计时始终由服务端决定。 */
public final class SoulSeparationNetwork {
    /** 网络工具类不实例化。 */
    private SoulSeparationNetwork() {}

    /** @param event 通用初始化事件，消息 5 为回归请求，6 为出窍状态 */
    public static void register(FMLCommonSetupEvent event) {
        OreConversionNetwork.CHANNEL.messageBuilder(ReturnRequest.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ReturnRequest::encode).decoder(ReturnRequest::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) SoulSeparation.returnToBody(player);
                }).add();
        OreConversionNetwork.CHANNEL.messageBuilder(State.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(State::encode).decoder(State::decode)
                .consumerMainThread((packet, context) -> SoulSeparationClient.receive(packet.active, packet.remainingTicks)).add();
    }

    /** @param player 接收者 @param active 是否处于出窍 @param remainingTicks 服务端剩余游戏刻 */
    public static void sync(ServerPlayer player, boolean active, int remainingTicks) {
        if (OreConversionNetwork.CHANNEL.isRemotePresent(player.connection.connection)) {
            OreConversionNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new State(active, remainingTicks));
        }
    }

    /** 客户端请求提前回归，不携带可以伪造的玩家身份或目的坐标。 */
    public record ReturnRequest() {
        /** 创建无参数请求，服务端从连接获取真实使用者。 */
        public ReturnRequest {}
        /** @param buffer 无载荷请求不写入任何数据 */
        public void encode(FriendlyByteBuf buffer) {}
        /** @param buffer 无载荷请求缓冲区 @return 新的回归请求 */
        public static ReturnRequest decode(FriendlyByteBuf buffer) { return new ReturnRequest(); }
    }

    /**
     * 发送给本人客户端的权威状态；每秒校正倒计时，结束时立即发送 inactive。
     * @param active 出窍是否有效
     * @param remainingTicks 剩余游戏刻，客户端只用于显示，不能决定服务端回归
     */
    public record State(boolean active, int remainingTicks) {
        /** @param buffer 按启用标记、剩余刻数顺序编码 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeBoolean(active);
            buffer.writeVarInt(remainingTicks);
        }
        /** @param buffer 服务端载荷 @return 解码后的展示状态 */
        public static State decode(FriendlyByteBuf buffer) { return new State(buffer.readBoolean(), buffer.readVarInt()); }
    }
}
