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
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player ME data stored in the overworld, independent of player entity recreation. */
public final class OreConversionSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_ID = Ore_craft.MODID + "_conversion_players";
    private final Map<UUID, Account> accounts = new HashMap<>();

    public static OreConversionSavedData get(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(OreConversionSavedData::new, OreConversionSavedData::load), FILE_ID);
    }

    public Account account(ServerPlayer player) {
        return accounts.computeIfAbsent(player.getUUID(), ignored -> new Account());
    }

    public boolean credit(ServerPlayer player, long amount) {
        if (amount <= 0) return false;
        Account account = account(player);
        if (account.balance > Long.MAX_VALUE - amount) return false;
        account.balance += amount;
        setDirty();
        return true;
    }

    public boolean debit(ServerPlayer player, long amount) {
        Account account = account(player);
        if (amount <= 0 || account.balance < amount) return false;
        account.balance -= amount;
        setDirty();
        return true;
    }

    public boolean learn(ServerPlayer player, ResourceLocation id) {
        boolean added = account(player).learned.add(id);
        if (added) setDirty();
        return added;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        accounts.forEach((uuid, account) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", uuid);
            entry.putLong("Balance", account.balance);
            ListTag learned = new ListTag();
            account.learned.stream().map(ResourceLocation::toString).sorted().forEach(id -> learned.add(StringTag.valueOf(id)));
            entry.put("Learned", learned);
            list.add(entry);
        });
        tag.put("Accounts", list);
        return tag;
    }

    private static OreConversionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        OreConversionSavedData data = new OreConversionSavedData();
        for (Tag raw : tag.getList("Accounts", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
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

    public static final class Account {
        private long balance;
        private final Set<ResourceLocation> learned = new HashSet<>();

        public long balance() { return balance; }
        public Set<ResourceLocation> learned() { return Set.copyOf(learned); }
        public boolean knows(ResourceLocation id) { return learned.contains(id); }
    }
}
