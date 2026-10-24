package com.lazeroX.ore_craft.player;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一个不可变的传送标记；服务端捕获位置，客户端只提交名称和标记编号。
 * 标记随玩家存档保存，UUID 使同名标记及删除后的列表位移不会导致操作错位。
 *
 * @param id 标记的稳定编号
 * @param name 玩家输入的纯文本名称，待命名快照允许为空
 * @param dimension 目标维度的注册名
 * @param position 玩家脚底的精确坐标，单位为方块
 * @param yaw 标记时的水平朝向，单位为度
 * @param pitch 标记时的俯仰角，单位为度
 */
public record TeleportWaypoint(UUID id, String name, ResourceLocation dimension, Vec3 position,
                               float yaw, float pitch) {
    /** 名称最多 32 个 UTF-16 字符；输入框、网络解码和服务端校验使用同一限制。 */
    public static final int MAX_NAME_LENGTH = 32;
    /** 每格距离消耗 10 ME；距离先向上取整且至少按一格收费，费用为原规则的十倍。 */
    public static final long ME_PER_BLOCK = 10L;

    /** 创建位置快照；外部存档通过 load 校验，正常游戏中的坐标仅由服务器生成。 */
    public TeleportWaypoint {}

    /** @param player 发起标记的服务端玩家 @return 按下右键这一刻的位置和朝向快照 */
    public static TeleportWaypoint capture(ServerPlayer player) {
        return new TeleportWaypoint(UUID.randomUUID(), "", player.level().dimension().location(),
                player.position(), player.getYRot(), player.getXRot());
    }

    /** @param value 已经校验的非空名称 @return 保持编号、维度、位置和朝向的新记录 */
    public TeleportWaypoint withName(String value) {
        return new TeleportWaypoint(id, value, dimension, position, yaw, pitch);
    }

    /**
     * 按同维度的三维直线距离计算传送费用，客户端预估与服务端结算共用此规则。
     * 包含高度差，距离向上取整且至少按一格计费，再乘每格 10 ME；原地传送也消耗 10 ME。
     * @param origin 同一维度内玩家脚底的精确坐标；服务端使用进入传送门时的位置
     * @return 本次传送需要扣除的正数全局 ME，不修改余额
     */
    public long energyCost(Vec3 origin) {
        return Math.max(1L, (long) Math.ceil(origin.distanceTo(position))) * ME_PER_BLOCK;
    }

    /**
     * 检查纯文本名称，拒绝空白、换行和格式控制符，避免列表显示被名称破坏。
     * @param value 已去除首尾空白的输入
     * @return 名称可以持久化时为 true
     */
    public static boolean isValidName(String value) {
        return !value.isBlank() && value.length() <= MAX_NAME_LENGTH
                && value.equals(SharedConstants.filterText(value));
    }

    /** @return 用于世界存档的完整 NBT，不包含客户端状态 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Dimension", dimension.toString());
        tag.putDouble("X", position.x);
        tag.putDouble("Y", position.y);
        tag.putDouble("Z", position.z);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Pitch", pitch);
        return tag;
    }

    /**
     * 读取单条存档；缺字段、非法名称和非有限坐标被跳过，不影响同玩家的其他标记。
     * 维度即使暂时不存在也保留，便于重新启用提供该维度的模组后继续使用。
     * @param tag 单条标记 NBT
     * @return 有效记录；数据损坏时为 null
     */
    @Nullable
    public static TeleportWaypoint load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        String name = tag.getString("Name");
        if (!tag.hasUUID("Id") || dimension == null || !isValidName(name)
                || !tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC)) return null;
        Vec3 position = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        float yaw = tag.getFloat("Yaw");
        float pitch = tag.getFloat("Pitch");
        // 世界边界还会在传送时校验；此处先阻断 NaN、无穷大和超出原版坐标范围的数据。
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || Math.abs(position.x) > 30_000_000 || Math.abs(position.z) > 30_000_000
                || Math.abs(position.y) > 20_000_000 || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        return new TeleportWaypoint(tag.getUUID("Id"), name, dimension, position, yaw, pitch);
    }

    /** @param buffer 发往客户端的缓冲区，坐标永远不从客户端反向提交 */
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(name, MAX_NAME_LENGTH);
        buffer.writeResourceLocation(dimension);
        buffer.writeDouble(position.x);
        buffer.writeDouble(position.y);
        buffer.writeDouble(position.z);
        buffer.writeFloat(yaw);
        buffer.writeFloat(pitch);
    }

    /** @param buffer 服务端的位置快照 @return 仅供界面显示的记录 */
    public static TeleportWaypoint decode(FriendlyByteBuf buffer) {
        return new TeleportWaypoint(buffer.readUUID(), buffer.readUtf(MAX_NAME_LENGTH),
                buffer.readResourceLocation(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readFloat(), buffer.readFloat());
    }
}
