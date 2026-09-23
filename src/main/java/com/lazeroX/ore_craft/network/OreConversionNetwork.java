package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.client.OreConversionClient;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;

public final class OreConversionNetwork {
    public static final int EXTRACT = 1;
    public static final int EXTRACT_STACK = 2;

    private OreConversionNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("7")
                .playToServer(ActionPayload.TYPE, ActionPayload.CODEC, (packet, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    if (!(player.containerMenu instanceof OreConversionMenu menu) || menu.containerId != packet.containerId()) return;
                    switch (packet.action()) {
                        case EXTRACT -> menu.extract(player, packet.itemId(), packet.count());
                        case EXTRACT_STACK -> menu.extractStackToInventory(player, packet.itemId());
                        default -> { }
                    }
                })
                .playToClient(SyncPayload.TYPE, SyncPayload.CODEC, (packet, context) -> {
                    if (context.player().level().isClientSide()) OreConversionClient.receive(packet);
                })
                .playToClient(PricesPayload.TYPE, PricesPayload.CODEC, (packet, context) -> {
                    if (context.player().level().isClientSide()) OreConversionClient.receivePrices(packet);
                })
                .playToClient(StatusPayload.TYPE, StatusPayload.CODEC, (packet, context) -> {
                    if (context.player().level().isClientSide()) OreConversionClient.receiveStatus(packet);
                });
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    public static void sendState(ServerPlayer player, int containerId) {
        if (!NetworkRegistry.hasChannel(player.connection, SyncPayload.TYPE.id())) return;
        OreConversionSavedData.Account account = OreConversionSavedData.get(player).account(player);
        Map<ResourceLocation, Long> prices = OreConversionPrices.snapshot();
        List<PriceEntry> learned = new ArrayList<>();
        for (ResourceLocation id : account.learned()) {
            Long price = prices.get(id);
            if (price != null && BuiltInRegistries.ITEM.containsKey(id) && OreConversionPrices.canExtract(BuiltInRegistries.ITEM.get(id))) {
                learned.add(new PriceEntry(id, price, false));
            }
        }
        learned.sort(Comparator.comparing(entry -> entry.id().toString()));
        PacketDistributor.sendToPlayer(player, new SyncPayload(containerId, account.balance(), learned));
    }

    public static void sendPrices(ServerPlayer player) {
        if (!NetworkRegistry.hasChannel(player.connection, PricesPayload.TYPE.id())) return;
        List<PriceEntry> allPrices = OreConversionPrices.snapshot().entrySet().stream()
                .filter(entry -> BuiltInRegistries.ITEM.containsKey(entry.getKey()) && entry.getValue() > 0)
                .map(entry -> {
                    Item item = BuiltInRegistries.ITEM.get(entry.getKey());
                    return new PriceEntry(entry.getKey(), entry.getValue(),
                            OreConversionPrices.canDeposit(new ItemStack(item)));
                })
                .sorted(Comparator.comparing(entry -> entry.id().toString()))
                .toList();
        int chunks = Math.max(1, (allPrices.size() + 255) / 256);
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * 256;
            PacketDistributor.sendToPlayer(player, new PricesPayload(chunk == 0, chunk == chunks - 1,
                    allPrices.subList(from, Math.min(from + 256, allPrices.size()))));
        }
    }

    public static void sendStatus(ServerPlayer player, int containerId, String key, long amount) {
        if (NetworkRegistry.hasChannel(player.connection, StatusPayload.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new StatusPayload(containerId, key, amount));
        }
    }

    public record PriceEntry(ResourceLocation id, long price, boolean convertible) {}

    public record ActionPayload(int containerId, int action, ResourceLocation itemId, int count) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "conversion_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeVarInt(packet.containerId);
                    buf.writeByte(packet.action);
                    buf.writeResourceLocation(packet.itemId);
                    buf.writeVarInt(packet.count);
                },
                buf -> new ActionPayload(buf.readVarInt(), buf.readUnsignedByte(), buf.readResourceLocation(), buf.readVarInt()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncPayload(int containerId, long balance, List<PriceEntry> catalog) implements CustomPacketPayload {
        public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "conversion_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeVarInt(packet.containerId);
                    buf.writeLong(packet.balance);
                    buf.writeVarInt(packet.catalog.size());
                    for (PriceEntry entry : packet.catalog) {
                        buf.writeResourceLocation(entry.id());
                        buf.writeLong(entry.price());
                    }
                },
                buf -> {
                    int id = buf.readVarInt();
                    long balance = buf.readLong();
                    int size = buf.readVarInt();
                    if (size < 0 || size > 2048) throw new IllegalArgumentException("Invalid conversion catalog size");
                    List<PriceEntry> catalog = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) catalog.add(new PriceEntry(buf.readResourceLocation(), buf.readLong(), false));
                    return new SyncPayload(id, balance, catalog);
                });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PricesPayload(boolean first, boolean last, List<PriceEntry> entries) implements CustomPacketPayload {
        public static final Type<PricesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "conversion_prices"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PricesPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeBoolean(packet.first);
                    buf.writeBoolean(packet.last);
                    buf.writeVarInt(packet.entries.size());
                    for (PriceEntry entry : packet.entries) {
                        buf.writeResourceLocation(entry.id());
                        buf.writeLong(entry.price());
                        buf.writeBoolean(entry.convertible());
                    }
                },
                buf -> {
                    boolean first = buf.readBoolean();
                    boolean last = buf.readBoolean();
                    int size = buf.readVarInt();
                    if (size < 0 || size > 256) throw new IllegalArgumentException("Invalid conversion price chunk size");
                    List<PriceEntry> entries = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) entries.add(new PriceEntry(
                            buf.readResourceLocation(), buf.readLong(), buf.readBoolean()));
                    return new PricesPayload(first, last, entries);
                });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StatusPayload(int containerId, String key, long amount) implements CustomPacketPayload {
        public static final Type<StatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "conversion_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeVarInt(packet.containerId);
                    buf.writeUtf(packet.key, 32);
                    buf.writeLong(packet.amount);
                },
                buf -> new StatusPayload(buf.readVarInt(), buf.readUtf(32), buf.readLong()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
