package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;

/** 接收附魔预览选择；领取输出时才由服务端菜单扣费。 */
public final class OreEnchantingNetwork {
    /** 网络工具类不允许创建实例。 */
    private OreEnchantingNetwork() {}

    /** 在共享通道上注册客户端请求，处理器在服务端主线程运行。 */
    public static void register(FMLCommonSetupEvent event) {
        OreConversionNetwork.CHANNEL.messageBuilder(EnchantPayload.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EnchantPayload::encode).decoder(EnchantPayload::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    // 忽略不存在的玩家以及关闭界面后到达的旧请求。
                    if (player != null && player.containerMenu instanceof OreEnchantingMenu menu
                            && menu.containerId == packet.containerId()) {
                        menu.select(player, packet.enchantmentId(), packet.level());
                    }
                }).add();
    }

    /** 菜单编号、附魔 ID 和目标等级；价格始终由服务端计算。 */
    public record EnchantPayload(int containerId, ResourceLocation enchantmentId, int level) {
        /** 按菜单、附魔和等级顺序写入缓冲区。 */
        public void encode(FriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeResourceLocation(enchantmentId);
            buffer.writeVarInt(level);
        }
        /** 解码选择参数，权限和等级上限由菜单验证。 */
        public static EnchantPayload decode(FriendlyByteBuf buffer) {
            return new EnchantPayload(buffer.readVarInt(), buffer.readResourceLocation(), buffer.readVarInt());
        }
    }
}
