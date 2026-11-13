package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.client.SoulSeparationClient;
import com.lazeroX.ore_craft.event.SoulSeparation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.lazeroX.ore_craft.Ore_craft;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;

/** 灵魂出窍的单向状态同步与提前回归请求；身体位置、费用和计时始终由服务端决定。 */
public final class SoulSeparationNetwork {
    /** 网络工具类不实例化。 */
    private SoulSeparationNetwork() {}

    /** 注册回归请求与出窍状态，玩家身份从连接获取。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("9")
                .playToServer(ReturnRequest.TYPE, ReturnRequest.CODEC, (packet, context) -> {
                    if (context.player() instanceof ServerPlayer player) SoulSeparation.returnToBody(player);
                })
                .playToClient(State.TYPE, State.CODEC, (packet, context) ->
                        SoulSeparationClient.receive(packet.active(), packet.remainingTicks()));
    }

    /** @param player 接收者 @param active 是否处于出窍 @param remainingTicks 服务端剩余游戏刻 */
    public static void sync(ServerPlayer player, boolean active, int remainingTicks) {
        if (NetworkRegistry.hasChannel(player.connection, State.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new State(active, remainingTicks));
        }
    }

    /** 客户端请求提前回归，不携带可以伪造的玩家身份或目的坐标。 */
    public record ReturnRequest() implements CustomPacketPayload {
        /** 此载荷的稳定标识及严格对称的编解码器。 */
        public static final Type<ReturnRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                Ore_craft.MODID, "soul_returnrequest"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReturnRequest> CODEC =
                StreamCodec.of((buffer, packet) -> packet.encode(buffer), ReturnRequest::decode);
        /** 返回 NeoForge 注册的载荷类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

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
    public record State(boolean active, int remainingTicks) implements CustomPacketPayload {
        /** 此载荷的稳定标识及严格对称的编解码器。 */
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                Ore_craft.MODID, "soul_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of((buffer, packet) -> packet.encode(buffer), State::decode);
        /** 返回 NeoForge 注册的载荷类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** @param buffer 按启用标记、剩余刻数顺序编码 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeBoolean(active);
            buffer.writeVarInt(remainingTicks);
        }
        /** @param buffer 服务端载荷 @return 解码后的展示状态 */
        public static State decode(FriendlyByteBuf buffer) { return new State(buffer.readBoolean(), buffer.readVarInt()); }
    }
}
