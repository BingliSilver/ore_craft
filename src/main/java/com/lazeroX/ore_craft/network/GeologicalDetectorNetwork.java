package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.client.GeologicalDetectorClient;
import com.lazeroX.ore_craft.menu.GeologicalDetectorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;

/** 探测器设置请求及付费成功后的粒子端点同步；客户端不能提交目标坐标或扣费金额。 */
public final class GeologicalDetectorNetwork {
    /** 无状态网络工具不创建实例。 */
    private GeologicalDetectorNetwork() {}

    /** @param event 初始化事件；共享通道 10 号配置、11 号粒子结果，均在对应主线程处理 */
    public static void register(FMLCommonSetupEvent event) {
        OreConversionNetwork.CHANNEL.messageBuilder(Configuration.class, 10, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Configuration::encode).decoder(Configuration::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof GeologicalDetectorMenu menu
                            && menu.containerId == packet.containerId()) {
                        menu.configure(player, packet.ore(), packet.rangeIndex());
                    }
                }).add();
        OreConversionNetwork.CHANNEL.messageBuilder(Trace.class, 11, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Trace::encode).decoder(Trace::decode)
                .consumerMainThread((packet, context) -> GeologicalDetectorClient.show(packet)).add();
    }

    /** @param player 付费成功的玩家；其他玩家不会收到矿石位置 @param target 服务端确定的矿石位置 */
    public static void sendTrace(ServerPlayer player, BlockPos target) {
        OreConversionNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Trace(player.level().dimension().location(), target,
                        BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(target).getBlock())));
    }

    /** @param containerId 当前设置菜单编号 @param ore 矿石注册名 @param rangeIndex 范围预设索引 */
    public record Configuration(int containerId, ResourceLocation ore, int rangeIndex) {
        /** 创建请求；所有字段在服务端菜单中再次验证。 */
        public Configuration {}
        /** @param buffer 请求缓冲区，仅包含配置而无世界坐标 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeResourceLocation(ore);
            buffer.writeVarInt(rangeIndex);
        }
        /** @param buffer 客户端请求 @return 待校验的配置 */
        public static Configuration decode(FriendlyByteBuf buffer) {
            return new Configuration(buffer.readVarInt(), buffer.readResourceLocation(), buffer.readVarInt());
        }
    }

    /** @param dimension 探测发生的维度 @param target 矿石位置 @param ore 探测时的矿石类型，用于识别破坏或替换 */
    public record Trace(ResourceLocation dimension, BlockPos target, ResourceLocation ore) {
        /** 冻结方块位置，避免外部可变坐标改变已排队的结果。 */
        public Trace { target = target.immutable(); }
        /** @param buffer 服务端端点快照；客户端自行生成粒子，避免逐点发送网络包 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimension);
            buffer.writeBlockPos(target);
            buffer.writeResourceLocation(ore);
        }
        /** @param buffer 服务端结果 @return 唯一接收者的粒子引导数据 */
        public static Trace decode(FriendlyByteBuf buffer) {
            return new Trace(buffer.readResourceLocation(), buffer.readBlockPos(), buffer.readResourceLocation());
        }
    }
}
