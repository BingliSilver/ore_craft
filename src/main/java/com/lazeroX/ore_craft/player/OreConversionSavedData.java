package com.lazeroX.ore_craft.player;

import com.lazeroX.ore_craft.Ore_craft;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 将每名玩家的 ME 余额和已学习物品保存在主世界存档数据中。 */
public final class OreConversionSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 主世界存档中保存玩家转化账户的文件名。 */
    private static final String FILE_ID = Ore_craft.MODID + "_conversion_players";
    /** 按玩家 UUID 索引的账户数据。 */
    private final Map<UUID, Account> accounts = new HashMap<>();

    /**
     * 取得当前存档的玩家转化数据；数据由主世界存储，因此玩家实体重建后仍可读取。
     *
     * @param player 需要读取数据的服务端玩家
     * @return 当前存档的转化数据
     */
    public static OreConversionSavedData get(ServerPlayer player) {
        return get(player.getServer());
    }

    /** 从服务器主世界取得共享账户存档，供玩家离线时运行的矿质机器使用。 */
    public static OreConversionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(OreConversionSavedData::new, OreConversionSavedData::load), FILE_ID);
    }

    /** 取得玩家账户；首次访问时创建空账户。 */
    public Account account(ServerPlayer player) {
        return account(player.getUUID());
    }

    /** 按 UUID 取得账户，使方块实体可在玩家离线时继续向其余额记账。 */
    public Account account(UUID playerId) {
        return accounts.computeIfAbsent(playerId, ignored -> new Account());
    }

    /**
     * 为玩家增加 ME，并拒绝非正数或会导致 long 溢出的金额。
     *
     * @param player 账户所属玩家
     * @param amount 要增加的 ME
     * @return 成功入账时返回 {@code true}
     */
    public boolean credit(ServerPlayer player, long amount) {
        return credit(player.getUUID(), amount);
    }

    /**
     * 向指定 UUID 的账户增加 ME；余额达到 long 上限时拒绝交易，调用方不得消耗输入物。
     *
     * @param playerId 账户所属玩家 UUID
     * @param amount 要存入的正数 ME
     * @return 记账成功时为 true
     */
    public boolean credit(UUID playerId, long amount) {
        if (amount <= 0) return false;
        Account account = account(playerId);
        if (account.balance > Long.MAX_VALUE - amount) return false;
        account.balance += amount;
        setDirty();
        return true;
    }

    /**
     * 从玩家账户扣除 ME。
     *
     * @param player 账户所属玩家
     * @param amount 要扣除的 ME
     * @return 余额充足且扣款成功时返回 {@code true}
     */
    public boolean debit(ServerPlayer player, long amount) {
        return debit(player.getUUID(), amount);
    }

    /** 按玩家 UUID 扣款，使矿质转化器在拥有者离线时也能支付 ME。 */
    public boolean debit(UUID playerId, long amount) {
        Account account = account(playerId);
        if (amount <= 0 || account.balance < amount) return false;
        account.balance -= amount;
        setDirty();
        return true;
    }

    /**
     * 将物品加入玩家的已学习目录，并在目录发生变化时标记存档待保存。
     *
     * @param player 账户所属玩家
     * @param id 要学习的物品 ID
     * @return 本次调用新增记录时返回 {@code true}
     */
    public boolean learn(ServerPlayer player, ResourceLocation id) {
        boolean added = account(player).learned.add(id);
        if (added) setDirty();
        return added;
    }

    /**
     * 将所有玩家账户序列化到存档 NBT。
     *
     * @param tag 接收账户数据的目标标签
     * @param registries 当前存档的注册表访问器
     * @return 写入账户列表后的标签
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        accounts.forEach((uuid, account) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", uuid);
            entry.putLong("Balance", account.balance);
            ListTag learned = new ListTag();
            // 排序后写入列表，便于稳定比较存档差异。
            account.learned.stream().map(ResourceLocation::toString).sorted().forEach(id -> learned.add(StringTag.valueOf(id)));
            entry.put("Learned", learned);
            list.add(entry);
        });
        tag.put("Accounts", list);
        return tag;
    }

    /**
     * 从存档 NBT 加载账户，并跳过格式错误或注册表中不存在的物品 ID。
     *
     * @param tag 保存账户列表的根标签
     * @param registries 当前存档的注册表访问器
     * @return 还原后的转化数据
     */
    private static OreConversionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        OreConversionSavedData data = new OreConversionSavedData();
        for (Tag raw : tag.getList("Accounts", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            // 忽略缺少玩家 UUID 的损坏记录，避免无法归属到账户的数据进入缓存。
            if (!entry.hasUUID("Player")) continue;
            Account account = new Account();
            account.balance = Math.max(0, entry.getLong("Balance"));
            for (Tag learned : entry.getList("Learned", Tag.TAG_STRING)) {
                ResourceLocation id = ResourceLocation.tryParse(learned.getAsString());
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) account.learned.add(id);
                else LOGGER.warn("Skipping unknown learned conversion item {}", learned.getAsString());
            }
            data.accounts.put(entry.getUUID("Player"), account);
        }
        return data;
    }

    /** 单个玩家的转化桌账户视图。 */
    public static final class Account {
        private long balance;
        private final Set<ResourceLocation> learned = new HashSet<>();

        /** 返回账户当前的 ME 余额。 */
        public long balance() { return balance; }
        /** 返回已学习物品 ID 的不可变快照。 */
        public Set<ResourceLocation> learned() { return Set.copyOf(learned); }
        /** 判断指定物品是否已学习。 */
        public boolean knows(ResourceLocation id) { return learned.contains(id); }
    }
}
