package com.lazeroX.ore_craft.player;

import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按玩家 UUID 保存传送标记，所有维度统一使用主世界数据存储。
 * 同一玩家的全部权杖共享标记；死亡、重登、换权杖均不会清空，只允许服务端主线程访问。
 */
public final class TeleportWaypointSavedData extends SavedData {
    /** 每位玩家最多 256 条标记，限制单次列表同步的载荷和存档增长。 */
    public static final int MAX_WAYPOINTS = 256;
    /** 独立存档文件名，不与 ME 账户数据耦合。 */
    private static final String FILE_ID = Ore_craft.MODID + "_teleport_waypoints";
    /** 玩家到有序标记列表的映射；列表顺序就是界面中的创建顺序。 */
    private final Map<UUID, List<TeleportWaypoint>> players = new HashMap<>();

    /** 创建空数据；首次成功标记时才产生需要保存的玩家记录。 */
    public TeleportWaypointSavedData() {}

    /** @param player 当前服务端玩家 @return 当前世界共用的传送标记存档 */
    public static TeleportWaypointSavedData get(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
                TeleportWaypointSavedData::load, TeleportWaypointSavedData::new, FILE_ID);
    }

    /** @param player 所属玩家 @return 不可变快照，调用方不能绕过 setDirty 修改存档 */
    public List<TeleportWaypoint> waypoints(ServerPlayer player) {
        return List.copyOf(players.getOrDefault(player.getUUID(), List.of()));
    }

    /**
     * 添加已命名的服务端位置快照；相同名称允许共存，操作始终依靠 UUID 区分。
     * @param player 标记所属玩家
     * @param waypoint 完整且已校验的服务端记录
     * @return 添加成功时为 true；满额、空名称或重复编号时不修改存档
     */
    public boolean add(ServerPlayer player, TeleportWaypoint waypoint) {
        if (!TeleportWaypoint.isValidName(waypoint.name())) return false;
        List<TeleportWaypoint> entries = players.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());
        if (entries.size() >= MAX_WAYPOINTS || entries.stream().anyMatch(entry -> entry.id().equals(waypoint.id()))) {
            return false;
        }
        entries.add(waypoint);
        setDirty();
        return true;
    }

    /**
     * 只删除发送请求的玩家自己的标记，不能通过其他玩家的标记 UUID 越权操作。
     * @param player 当前玩家
     * @param waypointId 待删除记录的稳定编号
     * @return 找到并删除时为 true，随后世界保存会写入变更
     */
    public boolean remove(ServerPlayer player, UUID waypointId) {
        List<TeleportWaypoint> entries = players.get(player.getUUID());
        if (entries == null || !entries.removeIf(entry -> entry.id().equals(waypointId))) return false;
        if (entries.isEmpty()) players.remove(player.getUUID());
        setDirty();
        return true;
    }

    /** @param root 世界保存的根 NBT @return 跳过损坏记录并限制每人条目数量的存档实例 */
    private static TeleportWaypointSavedData load(CompoundTag root) {
        TeleportWaypointSavedData data = new TeleportWaypointSavedData();
        ListTag players = root.getList("Players", Tag.TAG_COMPOUND);
        for (int index = 0; index < players.size(); index++) {
            CompoundTag playerTag = players.getCompound(index);
            if (!playerTag.hasUUID("Player")) continue;
            List<TeleportWaypoint> entries = new ArrayList<>();
            ListTag waypoints = playerTag.getList("Waypoints", Tag.TAG_COMPOUND);
            for (int waypointIndex = 0; waypointIndex < waypoints.size() && entries.size() < MAX_WAYPOINTS; waypointIndex++) {
                TeleportWaypoint waypoint = TeleportWaypoint.load(waypoints.getCompound(waypointIndex));
                // 重复编号会让按钮目标不唯一，因此只保留同一编号第一次出现的有效记录。
                if (waypoint != null && entries.stream().noneMatch(entry -> entry.id().equals(waypoint.id()))) {
                    entries.add(waypoint);
                }
            }
            if (!entries.isEmpty()) data.players.put(playerTag.getUUID("Player"), entries);
        }
        return data;
    }

    /** @param root Minecraft 提供的输出 NBT @return 保存各玩家及其全部标记的根 NBT */
    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag playerTags = new ListTag();
        players.forEach((playerId, entries) -> {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("Player", playerId);
            ListTag waypointTags = new ListTag();
            entries.forEach(waypoint -> waypointTags.add(waypoint.save()));
            playerTag.put("Waypoints", waypointTags);
            playerTags.add(playerTag);
        });
        root.put("Players", playerTags);
        return root;
    }
}
