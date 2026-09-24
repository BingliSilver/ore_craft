package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 接收矿质附魔台的预览选择；领取输出槽时才由服务端菜单扣费。 */
public final class OreEnchantingNetwork {
    /** 工具类不允许创建实例。 */
    private OreEnchantingNetwork() {
    }

    /** 注册客户端到服务端的附魔操作载荷。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(EnchantPayload.TYPE, EnchantPayload.CODEC, (packet, context) -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // 容器编号阻止关闭旧界面后的延迟请求作用到另一个新菜单。
            if (player.containerMenu instanceof OreEnchantingMenu menu && menu.containerId == packet.containerId()) {
                menu.select(player, packet.enchantmentId(), packet.level());
            }
        });
    }

    /**
     * 客户端只声明目标附魔及等级；价格由服务端重新计算。
     *
     * @param containerId 当前菜单编号
     * @param enchantmentId 目标附魔注册 ID
     * @param level 玩家选定的目标等级
     */
    public record EnchantPayload(int containerId, ResourceLocation enchantmentId, int level) implements CustomPacketPayload {
        /** 载荷的网络标识。 */
        public static final Type<EnchantPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "enchanting_action"));
        /** 按容器编号、附魔 ID、目标等级顺序编解码。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, EnchantPayload> CODEC = StreamCodec.of(
                (buffer, packet) -> {
                    buffer.writeVarInt(packet.containerId());
                    buffer.writeResourceLocation(packet.enchantmentId());
                    buffer.writeVarInt(packet.level());
                },
                buffer -> new EnchantPayload(buffer.readVarInt(), buffer.readResourceLocation(), buffer.readVarInt()));

        /** 返回本载荷的网络类型。 */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
