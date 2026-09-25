package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.client.OreConversionClient;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
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

/** 注册转化桌与矿质转化器的网络载荷，并同步价格、账户状态和操作反馈。 */
public final class OreConversionNetwork {
    /** 从已学习目录提取单个物品到鼠标指针的操作编号。 */
    public static final int EXTRACT = 1;
    /** 按价格和背包空间批量提取物品的操作编号。 */
    public static final int EXTRACT_STACK = 2;
    /** 矿质转化器从已学习目录选择生产物品的操作编号。 */
    public static final int SELECT_MACHINE_ITEM = 3;

    /** 工具类不允许创建实例。 */
    private OreConversionNetwork() {}

    /**
     * 注册转化桌提取、机器物品选择和服务端同步载荷的编解码器及处理器。
     *
     * @param event 网络载荷注册事件
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("7")
                .playToServer(ActionPayload.TYPE, ActionPayload.CODEC, (packet, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    // 校验菜单实例和容器 ID，拒绝旧界面或其他机器伪造的操作请求。
                    if (player.containerMenu instanceof OreConversionMenu menu
                            && menu.containerId == packet.containerId()) {
                        switch (packet.action()) {
                            case EXTRACT -> menu.extract(player, packet.itemId(), packet.count());
                            case EXTRACT_STACK -> menu.extractStackToInventory(player, packet.itemId());
                            default -> { }
                        }
                    } else if (player.containerMenu instanceof OreConversionMachineMenu machine
                            && machine.containerId == packet.containerId()
                            && packet.action() == SELECT_MACHINE_ITEM) {
                        machine.select(player, packet.itemId());
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

    /** 玩家登录后发送价格目录和账户数据。 */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    /** 玩家重生后重新发送价格目录和账户数据。 */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    /**
     * 向玩家发送余额及可兑换的已学习物品目录。
     *
     * @param player 数据接收者
     * @param containerId 当前菜单 ID；非菜单同步时使用负数
     */
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

    /** 将全量价格目录按网络载荷上限分块发送给玩家。 */
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
        // 即使目录为空也发送首尾同为 true 的空分块，让客户端结束本轮接收状态。
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * 256;
            PacketDistributor.sendToPlayer(player, new PricesPayload(chunk == 0, chunk == chunks - 1,
                    allPrices.subList(from, Math.min(from + 256, allPrices.size()))));
        }
    }

    /** 向玩家发送与菜单绑定的操作提示及对应金额。 */
    public static void sendStatus(ServerPlayer player, int containerId, String key, long amount) {
        if (NetworkRegistry.hasChannel(player.connection, StatusPayload.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new StatusPayload(containerId, key, amount));
        }
    }

    /**
     * 客户端展示用的单条物品价格与可输入标记。
     *
     * @param id 物品注册 ID
     * @param price 物品的 ME 单价
     * @param convertible 物品是否可作为转化输入
     */
    public record PriceEntry(ResourceLocation id, long price, boolean convertible) {}

    /**
     * 客户端发起的转化桌操作请求。
     *
     * @param containerId 发起请求时的菜单 ID
     * @param action 操作类型编号
     * @param itemId 目标物品注册 ID
     * @param count 操作数量或附加槽位参数
     */
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

        /** 返回此载荷的注册类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 服务端发给客户端的余额和已学习目录快照。
     *
     * @param containerId 对应的菜单 ID
     * @param balance 玩家 ME 余额
     * @param catalog 已学习物品价格列表
     */
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
                    // 限制反序列化列表大小，避免异常载荷造成过量内存分配。
                    if (size < 0 || size > 2048) throw new IllegalArgumentException("Invalid conversion catalog size");
                    List<PriceEntry> catalog = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) catalog.add(new PriceEntry(buf.readResourceLocation(), buf.readLong(), false));
                    return new SyncPayload(id, balance, catalog);
                });

        /** 返回此载荷的注册类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 分块传输的全量物品价格目录。
     *
     * @param first 是否为本次传输的首块
     * @param last 是否为本次传输的末块
     * @param entries 当前分块中的价格条目
     */
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
                    // 单个价格载荷最多包含 256 条，匹配服务端分块大小。
                    if (size < 0 || size > 256) throw new IllegalArgumentException("Invalid conversion price chunk size");
                    List<PriceEntry> entries = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) entries.add(new PriceEntry(
                            buf.readResourceLocation(), buf.readLong(), buf.readBoolean()));
                    return new PricesPayload(first, last, entries);
                });

        /** 返回此载荷的注册类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 菜单操作的本地化提示键和相关金额。
     *
     * @param containerId 触发提示的菜单 ID
     * @param key 客户端语言文件中的提示键后缀
     * @param amount 提示中使用的金额参数
     */
    public record StatusPayload(int containerId, String key, long amount) implements CustomPacketPayload {
        public static final Type<StatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "conversion_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeVarInt(packet.containerId);
                    buf.writeUtf(packet.key, 32);
                    buf.writeLong(packet.amount);
                },
                buf -> new StatusPayload(buf.readVarInt(), buf.readUtf(32), buf.readLong()));

        /** 返回此载荷的注册类型。 */
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
