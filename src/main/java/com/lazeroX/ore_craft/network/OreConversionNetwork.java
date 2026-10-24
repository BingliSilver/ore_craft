package com.lazeroX.ore_craft.network;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.client.OreConversionClient;
import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.menu.OreCraftingMenu;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;

/** 使用 Forge 1.20.1 的可靠通道同步价格、账户和菜单操作；交易只在服务端主线程执行。 */
public final class OreConversionNetwork {
    /** 从目录提取单件的操作编号。 */
    public static final int EXTRACT = 1;
    /** 批量提取至背包的操作编号。 */
    public static final int EXTRACT_STACK = 2;
    /** 机器选定产物的操作编号。 */
    public static final int SELECT_MACHINE_ITEM = 3;
    /** 双端必须使用相同协议，避免不同载荷格式被误读。 */
    private static final String PROTOCOL = "1.20.1-6";
    /** 共享通道：0 至 3 为转化，4 为附魔，5 至 6 为灵魂出窍，7 为配方填充，8 至 9 为传送权杖。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Ore_craft.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** 网络工具类不允许实例化。 */
    private OreConversionNetwork() {}

    /** 注册方向与编解码器；Forge 将处理器调度至游戏主线程。 */
    public static void register(FMLCommonSetupEvent event) {
        CHANNEL.messageBuilder(ActionPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActionPayload::encode).decoder(ActionPayload::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player == null) return;
                    // 菜单类型、编号及交易方法中的距离检查共同拒绝伪造请求。
                    if (player.containerMenu instanceof OreConversionMenu menu
                            && menu.containerId == packet.containerId()) {
                        switch (packet.action()) {
                            case EXTRACT -> menu.extract(player, packet.itemId(), packet.count());
                            case EXTRACT_STACK -> menu.extractStackToInventory(player, packet.itemId());
                            default -> { }
                        }
                    } else if (player.containerMenu instanceof OreConversionMachineMenu menu
                            && menu.containerId == packet.containerId() && packet.action() == SELECT_MACHINE_ITEM) {
                        menu.select(player, packet.itemId());
                    }
                }).add();
        CHANNEL.messageBuilder(SyncPayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncPayload::encode).decoder(SyncPayload::decode)
                .consumerMainThread((packet, context) -> OreConversionClient.receive(packet)).add();
        CHANNEL.messageBuilder(PricesPayload.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PricesPayload::encode).decoder(PricesPayload::decode)
                .consumerMainThread((packet, context) -> OreConversionClient.receivePrices(packet)).add();
        CHANNEL.messageBuilder(StatusPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatusPayload::encode).decoder(StatusPayload::decode)
                .consumerMainThread((packet, context) -> OreConversionClient.receiveStatus(packet)).add();
        // 客户端只提交配方 ID 和批量意图；价格、原料和实际库存全部由服务端重新读取。
        CHANNEL.messageBuilder(CraftingPayload.class, 7, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CraftingPayload::encode).decoder(CraftingPayload::decode)
                .consumerMainThread((packet, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof OreCraftingMenu menu
                            && menu.containerId == packet.containerId()) {
                        menu.transferRecipe(player, packet.recipeId(), packet.maximum());
                    }
                }).add();
    }

    /** 客户端提交操作，服务器会重新校验参数与库存。 */
    public static void sendToServer(Object packet) { CHANNEL.sendToServer(packet); }

    /** 向已握手的真实玩家发包；测试模拟连接可不提供通道。 */
    private static void sendToPlayer(ServerPlayer player, Object packet) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /** 登录后发送完整价格和账户快照。 */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    /** 重生后恢复客户端账户缓存。 */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendPrices(player);
            sendState(player, -1);
        }
    }

    /** 同步余额、可提取目录与完整学习记录；负菜单编号表示全局更新。 */
    public static void sendState(ServerPlayer player, int containerId) {
        OreConversionSavedData.Account account = OreConversionSavedData.get(player).account(player);
        Map<ResourceLocation, Long> prices = OreConversionPrices.snapshot();
        List<PriceEntry> learned = new ArrayList<>();
        for (ResourceLocation id : account.learned()) {
            Long price = prices.get(id);
            if (price != null && BuiltInRegistries.ITEM.containsKey(id)
                    && OreConversionPrices.canExtract(BuiltInRegistries.ITEM.get(id))) {
                learned.add(new PriceEntry(id, price, false));
            }
        }
        learned.sort(Comparator.comparing(entry -> entry.id().toString()));
        List<ResourceLocation> learnedIds = account.learned().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        sendToPlayer(player, new SyncPayload(containerId, account.balance(), learned, learnedIds));
    }

    /** 每块最多 256 个价格，空目录也发送首尾分块以清除客户端旧数据。 */
    public static void sendPrices(ServerPlayer player) {
        // 开关与同一次价格传输一起发送，客户端不得使用单人服务器的静态状态推测权限。
        boolean convertAllItems = OreConversionSavedData.get(player).convertAllItems();
        List<PriceEntry> prices = OreConversionPrices.snapshot().entrySet().stream()
                .filter(entry -> BuiltInRegistries.ITEM.containsKey(entry.getKey()) && entry.getValue() > 0)
                .map(entry -> {
                    Item item = BuiltInRegistries.ITEM.get(entry.getKey());
                    return new PriceEntry(entry.getKey(), entry.getValue(), OreConversionPrices.canDeposit(new ItemStack(item)));
                }).sorted(Comparator.comparing(entry -> entry.id().toString())).toList();
        int chunks = Math.max(1, (prices.size() + 255) / 256);
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * 256;
            sendToPlayer(player, new PricesPayload(chunk == 0, chunk == chunks - 1, convertAllItems,
                    prices.subList(from, Math.min(from + 256, prices.size()))));
        }
    }

    /** 同步菜单操作提示键及其 ME 金额。 */
    public static void sendStatus(ServerPlayer player, int containerId, String key, long amount) {
        sendToPlayer(player, new StatusPayload(containerId, key, amount));
    }

    /** 单条价格；id 为注册 ID，price 为 ME 单价，convertible 表示能否回收。 */
    public record PriceEntry(ResourceLocation id, long price, boolean convertible) {}

    /**
     * 合成台配方填充请求，不携带任意物品、NBT 或客户端计算的费用。
     *
     * @param containerId 当前菜单编号
     * @param recipeId 服务器已加载的合成配方 ID
     * @param maximum 是否请求最多一组原料
     */
    public record CraftingPayload(int containerId, ResourceLocation recipeId, boolean maximum) {
        /** 保存请求参数，服务端处理时再次检查菜单有效性和配方类型。 */
        public CraftingPayload {}
        /** 按固定顺序编码，不传输任何由客户端决定的产物或费用。 */
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(containerId);
            buf.writeResourceLocation(recipeId);
            buf.writeBoolean(maximum);
        }
        /** 读取紧凑的配方请求。 */
        public static CraftingPayload decode(FriendlyByteBuf buf) {
            return new CraftingPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readBoolean());
        }
    }

    /** 操作请求；菜单编号和 count 的合法范围由对应交易方法校验。 */
    public record ActionPayload(int containerId, int action, ResourceLocation itemId, int count) {
        /** 按固定顺序写入操作参数。 */
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(containerId);
            buf.writeByte(action);
            buf.writeResourceLocation(itemId);
            buf.writeVarInt(count);
        }
        /** 从客户端请求读取操作参数。 */
        public static ActionPayload decode(FriendlyByteBuf buf) {
            return new ActionPayload(buf.readVarInt(), buf.readUnsignedByte(), buf.readResourceLocation(), buf.readVarInt());
        }
    }

    /** 账户快照；学习记录与可兑换目录分开保存，避免价格变化丢失学习提示。 */
    public record SyncPayload(int containerId, long balance, List<PriceEntry> catalog, List<ResourceLocation> learnedIds) {
        /** 序列化余额和目录，此处无须传输价格的回收权限。 */
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(containerId);
            buf.writeLong(balance);
            buf.writeVarInt(catalog.size());
            for (PriceEntry entry : catalog) {
                buf.writeResourceLocation(entry.id());
                buf.writeLong(entry.price());
            }
            buf.writeVarInt(learnedIds.size());
            learnedIds.forEach(buf::writeResourceLocation);
        }
        /** 解码数量上限与服务端 2048 种学习上限一致。 */
        public static SyncPayload decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            long balance = buf.readLong();
            int size = readSize(buf, OreConversionSavedData.MAX_LEARNED);
            List<PriceEntry> catalog = new ArrayList<>(size);
            for (int i = 0; i < size; i++) catalog.add(new PriceEntry(buf.readResourceLocation(), buf.readLong(), false));
            int learnedSize = readSize(buf, OreConversionSavedData.MAX_LEARNED);
            List<ResourceLocation> learned = new ArrayList<>(learnedSize);
            for (int i = 0; i < learnedSize; i++) learned.add(buf.readResourceLocation());
            return new SyncPayload(id, balance, catalog, learned);
        }
    }

    /**
     * 全量价格与世界回收开关的分块，同一轮传输使用同一开关值。
     *
     * @param first 是否开始一轮新的价格传输
     * @param last 是否接收完整目录，可发布价格与规则
     * @param convertAllItems 是否允许有价格的特殊状态物品按基础价回收
     * @param entries 本块最多 256 条物品价格与回收权限
     */
    public record PricesPayload(boolean first, boolean last, boolean convertAllItems, List<PriceEntry> entries) {
        /** 写入分块边界、世界开关及每种物品的价格、回收权限。 */
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(first);
            buf.writeBoolean(last);
            buf.writeBoolean(convertAllItems);
            buf.writeVarInt(entries.size());
            for (PriceEntry entry : entries) {
                buf.writeResourceLocation(entry.id());
                buf.writeLong(entry.price());
                buf.writeBoolean(entry.convertible());
            }
        }
        /** 限制每块最多 256 项，防止异常消息分配过量内存。 */
        public static PricesPayload decode(FriendlyByteBuf buf) {
            boolean first = buf.readBoolean();
            boolean last = buf.readBoolean();
            boolean convertAllItems = buf.readBoolean();
            int size = readSize(buf, 256);
            List<PriceEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) entries.add(new PriceEntry(buf.readResourceLocation(), buf.readLong(), buf.readBoolean()));
            return new PricesPayload(first, last, convertAllItems, entries);
        }
    }

    /** 与菜单编号绑定的本地化反馈及金额。 */
    public record StatusPayload(int containerId, String key, long amount) {
        /** 提示键最长 32 字符，文字由客户端语言文件提供。 */
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(containerId);
            buf.writeUtf(key, 32);
            buf.writeLong(amount);
        }
        /** 读取菜单反馈。 */
        public static StatusPayload decode(FriendlyByteBuf buf) {
            return new StatusPayload(buf.readVarInt(), buf.readUtf(32), buf.readLong());
        }
    }

    /** 读取有界集合长度；负数或超过协议容量时拒绝解码。 */
    private static int readSize(FriendlyByteBuf buf, int maximum) {
        int size = buf.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Invalid packet list size: " + size);
        return size;
    }
}
