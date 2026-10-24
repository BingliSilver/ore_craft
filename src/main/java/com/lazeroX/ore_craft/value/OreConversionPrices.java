package com.lazeroX.ore_craft.value;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lazeroX.ore_craft.recipe.OreContainerUpgradeRecipe;
import com.lazeroX.ore_craft.item.EternalEmeraldCoalItem;
import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** 服务端权威的物品价格表，支持数据包配置及确定性配方推导。 */
public final class OreConversionPrices {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前数据包声明的原始价格与转化配置。 */
    private static volatile Config config = new Config(Map.of(), Map.of(), List.of(), Set.of(), Set.of(), List.of());
    /** 供菜单和客户端同步读取的不可变价格快照。 */
    private static volatile Map<ResourceLocation, Long> prices = Map.of();
    /** 配置直接定价的物品集合；这些价格不会被配方推导覆盖。 */
    private static volatile Set<ResourceLocation> basePriced = Set.of();
    private static volatile MinecraftServer lastServer;

    /** 工具类不允许创建实例。 */
    private OreConversionPrices() {}

    /**
     * 注册数据包重载监听器，读取基础价格、标签价格、输入来源和自定义配方。
     * 单个 JSON 文件出错时会记录错误并跳过该文件。
     *
     * @param event MinecraftForge 的重载监听器注册事件
     */
    public static void registerReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "ore_conversion") {
            /** 将本轮重载的数据包资源解析为新的价格配置快照。 */
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, Long> base = new HashMap<>();
                Map<ResourceLocation, Long> tagBase = new HashMap<>();
                List<Source> sources = new ArrayList<>();
                Set<ResourceLocation> blocked = new HashSet<>();
                Set<ResourceLocation> blockedTags = new HashSet<>();
                List<Conversion> conversions = new ArrayList<>();
                // 排序资源文件，确保多个数据包文件声明相同键时结果稳定可复现。
                files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    try {
                        JsonObject root = entry.getValue().getAsJsonObject();
                        if (root.has("prices")) root.getAsJsonObject("prices").entrySet().forEach(price -> {
                            ResourceLocation id = requireId(price.getKey());
                            long value = price.getValue().getAsLong();
                            if (value <= 0) throw new JsonParseException("Price must be positive: " + id);
                            base.put(id, value);
                        });
                        if (root.has("tag_prices")) root.getAsJsonObject("tag_prices").entrySet().forEach(price -> {
                            String key = price.getKey();
                            ResourceLocation id = requireId(key.startsWith("#") ? key.substring(1) : key);
                            long value = price.getValue().getAsLong();
                            if (value <= 0) throw new JsonParseException("Tag price must be positive: " + id);
                            tagBase.put(id, value);
                        });
                        if (root.has("sources")) root.getAsJsonArray("sources").forEach(value ->
                                sources.add(Source.parse(value.getAsString())));
                        if (root.has("blocked")) root.getAsJsonArray("blocked").forEach(value ->
                                blocked.add(requireId(value.getAsString())));
                        if (root.has("blocked_tags")) root.getAsJsonArray("blocked_tags").forEach(value -> {
                            String key = value.getAsString();
                            blockedTags.add(requireId(key.startsWith("#") ? key.substring(1) : key));
                        });
                        if (root.has("conversions")) root.getAsJsonArray("conversions").forEach(value -> {
                            JsonObject conversion = value.getAsJsonObject();
                            ResourceLocation output = requireId(conversion.get("output").getAsString());
                            int count = conversion.has("count") ? conversion.get("count").getAsInt() : 1;
                            if (count <= 0) throw new JsonParseException("Invalid conversion output count: " + output);
                            List<IngredientCost> ingredients = new ArrayList<>();
                            conversion.getAsJsonArray("ingredients").forEach(raw -> {
                                JsonObject ingredient = raw.getAsJsonObject();
                                int amount = ingredient.has("amount") ? ingredient.get("amount").getAsInt() : 1;
                                if (amount <= 0) throw new JsonParseException("Invalid conversion ingredient amount");
                                ingredients.add(new IngredientCost(Source.parse(ingredient.get("source").getAsString()), amount));
                            });
                            if (ingredients.isEmpty()) throw new JsonParseException("Empty conversion: " + output);
                            conversions.add(new Conversion(output, count, List.copyOf(ingredients)));
                        });
                    } catch (RuntimeException ex) {
                        // 隔离单文件解析错误，避免一份配置损坏导致整个价格数据集无法加载。
                        LOGGER.error("Invalid ore conversion data {}", entry.getKey(), ex);
                    }
                });
                // 完成全部文件解析后再整体替换，避免运行中观察到半更新的配置。
                config = new Config(Map.copyOf(base), Map.copyOf(tagBase), List.copyOf(sources),
                        Set.copyOf(blocked), Set.copyOf(blockedTags), List.copyOf(conversions));
                prices = Map.copyOf(base);
                basePriced = Set.copyOf(base.keySet());
                lastServer = null;
            }
        });
    }

    /** 服务端启动后根据当前配方管理器构建完整价格表。 */
    public static void onServerStarted(ServerStartedEvent event) {
        rebuild(event.getServer());
    }

    /** 数据包同步时重建价格表，并向相关玩家发送最新目录和账户状态。 */
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        // 数据包重载与玩家登录时都重建价格；服务端不会信任客户端上报的价格。
        rebuild(server);
        // Forge 以空玩家表示全服重载，否则只向正在登录的玩家同步。
        var players = event.getPlayer() == null ? event.getPlayerList().getPlayers() : List.of(event.getPlayer());
        players.forEach(player -> {
            OreConversionNetwork.sendPrices(player);
            OreConversionNetwork.sendState(player,
                    player.containerMenu instanceof com.lazeroX.ore_craft.menu.OreConversionMenu menu ? menu.containerId : -1);
        });
    }

    /**
     * 合并数据包价格并迭代推导配方产物价格，直到没有更低的新价格。
     *
     * @param server 用于读取配方和注册表的服务端
     */
    private static synchronized void rebuild(MinecraftServer server) {
        if (server == lastServer && !prices.isEmpty()) return;
        Config snapshot = config;
        Map<ResourceLocation, Long> resolved = new HashMap<>();
        snapshot.tagBase().forEach((id, value) -> BuiltInRegistries.ITEM
                .getTag(TagKey.create(Registries.ITEM, id))
                .ifPresent(tag -> tag.forEach(holder -> resolved.put(BuiltInRegistries.ITEM.getKey(holder.value()), value))));
        resolved.putAll(snapshot.base());
        resolved.entrySet().removeIf(entry -> isBlocked(BuiltInRegistries.ITEM.get(entry.getKey())));
        // 显式物品价与标签价属于锚点，配方推导不会覆盖这些作者配置的价格。
        Set<ResourceLocation> anchors = Set.copyOf(resolved.keySet());
        List<Recipe<?>> supported = new ArrayList<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            // 仅推导确定性配方；特殊配方的产物受运行时状态影响，无法可靠定价。
            if (!recipe.isSpecial() && (recipe instanceof ShapedRecipe || recipe instanceof OreContainerUpgradeRecipe
                    || recipe instanceof ShapelessRecipe
                    || recipe instanceof StonecutterRecipe || recipe instanceof AbstractCookingRecipe)) {
                supported.add(recipe);
            }
        }
        // 新锚点只会让推导价格下降；迭代到不动点以处理标签、多产物配方和相互依赖的配方链。
        boolean changed;
        int rounds = 0;
        do {
            changed = false;
            for (Recipe<?> recipe : supported) {
                ItemStack output = recipe.getResultItem(server.registryAccess());
                if (output.isEmpty() || !hasDefaultData(output) || output.getCount() <= 0) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(output.getItem());
                if (isBlocked(output.getItem()) || anchors.contains(id)) continue;
                List<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.isEmpty()) continue;
                long total = 0;
                boolean valid = true;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    long cheapest = Long.MAX_VALUE;
                    for (ItemStack candidate : ingredient.getItems()) {
                        // 带特殊 NBT 或容器返还物的原料不能按普通物品价格参与推导。
                        if (candidate.isEmpty() || !hasDefaultData(candidate) || candidate.hasCraftingRemainingItem()) continue;
                        Long candidatePrice = resolved.get(BuiltInRegistries.ITEM.getKey(candidate.getItem()));
                        if (candidatePrice != null) cheapest = Math.min(cheapest, candidatePrice);
                    }
                    if (cheapest == Long.MAX_VALUE || total > Long.MAX_VALUE - cheapest) {
                        valid = false;
                        break;
                    }
                    total += cheapest;
                }
                if (!valid || total <= 0) continue;
                // 输出按整数组平均分；余数向下舍去，避免凭空增加价格。
                long unit = total / output.getCount();
                if (unit <= 0) continue;
                Long previous = resolved.get(id);
                if (previous == null || unit < previous) {
                    resolved.put(id, unit);
                    changed = true;
                }
            }
            for (Conversion conversion : snapshot.conversions()) {
                ResourceLocation id = conversion.output();
                if (!BuiltInRegistries.ITEM.containsKey(id) || isBlocked(BuiltInRegistries.ITEM.get(id))
                        || anchors.contains(id)) continue;
                long total = 0;
                boolean valid = true;
                for (IngredientCost ingredient : conversion.ingredients()) {
                    long unitCost = ingredient.source().minimumPrice(resolved);
                    if (unitCost <= 0 || unitCost > Long.MAX_VALUE / ingredient.amount()) {
                        valid = false;
                        break;
                    }
                    long cost = unitCost * ingredient.amount();
                    if (total > Long.MAX_VALUE - cost) { valid = false; break; }
                    total += cost;
                }
                if (!valid) continue;
                long unit = total / conversion.count();
                if (unit <= 0) continue;
                Long previous = resolved.get(id);
                if (previous == null || unit < previous) {
                    resolved.put(id, unit);
                    changed = true;
                }
            }
        } while (changed && ++rounds < 256);
        if (changed) LOGGER.warn("Ore conversion price propagation reached the 256-round limit");
        // 以不可变快照一次性发布，客户端查询不会读到推导过程中的中间结果。
        prices = Map.copyOf(resolved);
        basePriced = anchors;
        lastServer = server;
        LOGGER.info("Ore conversion has {} priced items", prices.size());
    }

    /**
     * 查询物品当前的 ME 单价。
     *
     * @param item 需要查询的物品
     * @return 有效价格；未定价或价格无效时为空
     */
    public static OptionalLong price(Item item) {
        Long value = prices.get(BuiltInRegistries.ITEM.getKey(item));
        return value == null || value <= 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** 判断默认状态的物品栈是否可学习并在之后按同一基础状态提取。 */
    public static boolean canLearn(ItemStack stack) {
        return isPlain(stack) && canExtract(stack.getItem());
    }

    /** 判断物品是否允许定价并从转化桌提取。 */
    public static boolean canExtract(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return item != Items.AIR && !isBlocked(item) && price(item).isPresent();
    }

    /**
     * 读取当前服务器存档中的回收开关，避免静态布尔缓存串到下一次打开的世界。
     * 仅供服务端规则使用；客户端通过价格数据包取得自己的同步副本。
     *
     * @return 当前世界开启全物品回收时为 true，服务端尚未就绪时为 false
     */
    public static boolean convertAllItems() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.overworld() != null
                && OreConversionSavedData.get(server).convertAllItems();
    }

    /**
     * 判断物品是否能输入转化桌或传输接口。
     * 全物品模式接受所有已有有效价格的物品及其特殊状态，不为未定价物品补充价格。
     * 默认模式保留矿物来源限制与特殊状态保护，矿质容器和绿宝石煤炭沿用原来的回收规则。
     *
     * @param stack 待回收物品栈
     * @return 当前世界规则允许回收时为 true
     */
    public static boolean canDeposit(ItemStack stack) {
        Item item = stack.getItem();
        boolean allItems = convertAllItems();
        if (!isValidForDeposit(stack, allItems) || !canExtract(item)) return false;
        // 开关只放宽回收来源和物品状态，不允许未定价物品凭空产生 ME。
        if (allItems) return true;
        boolean emeraldCoal = item == ModItems.EMERALD_COAL.get() || item == ModItems.ETERNAL_EMERALD_COAL.get();
        // 容器自身是可定价物品，回收不依赖普通矿物来源列表；末影容器不读取所连账户。
        if (item instanceof OreContainerItem || item instanceof EnderOreContainerItem) return true;
        if (!(basePriced.contains(BuiltInRegistries.ITEM.getKey(item)) || emeraldCoal)) return false;
        return config.sources().stream().anyMatch(source -> source.matches(stack));
    }

    /**
     * 计算输入栈中每件物品可回收的 ME，输入资格与金额始终由服务端校验。
     * 两种回收模式下，普通矿质容器均按本体价格加内部存储 ME 结算，末影容器只回收本体。
     * 全物品模式允许回收特殊状态物品，但附魔、损伤及容器内的物品不额外计价。
     *
     * @param stack 准备放入转化桌或传输接口的物品栈
     * @return 每件可到账的 ME；不允许输入或金额溢出时为空
     */
    public static OptionalLong depositValue(ItemStack stack) {
        if (!canDeposit(stack)) return OptionalLong.empty();
        OptionalLong base = price(stack.getItem());
        if (base.isEmpty()) return OptionalLong.empty();
        if (stack.getItem() instanceof OreContainerItem container) {
            // 存储 ME 会随容器本体一起消耗，两种模式都必须计入，避免全物品回收时丢失余额。
            long stored = container.storedMe(stack);
            // 超出 long 上限时拒绝整件回收，不能截断金额后仍消耗容器。
            if (base.getAsLong() > Long.MAX_VALUE - stored) return OptionalLong.empty();
            return OptionalLong.of(base.getAsLong() + stored);
        }
        // 末影容器不持有独立 ME，不能把玩家全局余额当作物品内部价值。
        return base;
    }

    /**
     * 校验输入栈的数量和特殊状态，两端使用同一规则避免槽位预测与服务端判定不一致。
     * 全物品模式允许损伤、附魔、名称、内置库存及其他 NBT，回收后这些数据随物品消耗。
     *
     * @param stack 待回收物品栈
     * @param allItems 服务端的世界开关，或客户端收到的同一开关
     * @return 非空、数量合法，且符合当前状态限制时为 true
     */
    public static boolean isValidForDeposit(ItemStack stack, boolean allItems) {
        if (stack.isEmpty() || stack.getCount() <= 0 || stack.getCount() > stack.getMaxStackSize()) return false;
        return allItems || isPlainForDeposit(stack);
    }

    /**
     * 检查默认回收模式中的物品状态；忽略普通容器自身存储量和永恒煤自动记录的持有者标记。
     * 附魔、自定义名称及其他 NBT 仍不能兑换 ME，避免带特殊状态的物品被误消耗。
     * 客户端使用同一规则显示可兑换提示，实际价格和来源资格由服务端判定。
     *
     * @param stack 待投入的物品栈
     * @return 物品状态符合兑换要求时为 true
     */
    public static boolean isPlainForDeposit(ItemStack stack) {
        if (stack.getItem() instanceof OreContainerItem container) {
            ItemStack withoutStoredMe = stack.copy();
            container.setStoredMe(withoutStoredMe, 0);
            return isPlain(withoutStoredMe);
        }
        if (stack.getItem() instanceof EternalEmeraldCoalItem eternalCoal) {
            return isPlain(eternalCoal.copyWithoutOwnerForDeposit(stack));
        }
        return isPlain(stack);
    }

    /** 判断物品栈是否非空、未损坏且不带NBT 数据。 */
    public static boolean isPlain(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() > 0 && stack.getCount() <= stack.getMaxStackSize()
                && hasDefaultData(stack) && !stack.isDamaged();
    }

    /**
     * 比较默认物品的 NBT 与能力数据，允许耐久工具自动写入的 Damage=0，拒绝附魔等特殊状态。
     * 不能简单检查 hasTag，因为 1.20.1 的全新耐久物品也可能自带默认 NBT。
     */
    private static boolean hasDefaultData(ItemStack stack) {
        return ItemStack.isSameItemSameTags(stack, stack.getItem().getDefaultInstance());
    }

    /** 返回当前不可变价格表快照。 */
    public static Map<ResourceLocation, Long> snapshot() { return prices; }

    /** 检查物品是否被单独 ID 或物品标签列入禁用配置。 */
    private static boolean isBlocked(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (config.blocked().contains(id)) return true;
        ItemStack stack = new ItemStack(item);
        return config.blockedTags().stream().anyMatch(tag -> stack.is(TagKey.create(Registries.ITEM, tag)));
    }

    /**
     * 解析必须带命名空间的资源 ID。
     *
     * @param text 配置中的资源 ID
     * @return 解析后的 ID
     * @throws JsonParseException ID 格式非法或未写命名空间时抛出
     */
    private static ResourceLocation requireId(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !text.contains(":")) throw new JsonParseException("Expected namespace:path: " + text);
        return id;
    }

    /**
     * 一次数据包重载解析出的不可变价格配置。
     *
     * @param base 显式物品价格
     * @param tagBase 标签成员的基础价格
     * @param sources 允许输入转化的来源
     * @param blocked 禁用的物品 ID
     * @param blockedTags 禁用的物品标签
     * @param conversions 自定义推导配方
     */
    private record Config(Map<ResourceLocation, Long> base, Map<ResourceLocation, Long> tagBase,
                          List<Source> sources, Set<ResourceLocation> blocked, Set<ResourceLocation> blockedTags,
                          List<Conversion> conversions) {}

    /** 自定义价格推导中单种原料及其消耗数量。 */
    private record IngredientCost(Source source, int amount) {}
    /** 自定义配方的产物、产量和原料列表。 */
    private record Conversion(ResourceLocation output, int count, List<IngredientCost> ingredients) {}

    /**
     * 按物品 ID 或物品标签描述的价格来源。
     *
     * @param id 物品或标签的注册 ID
     * @param tag 是否将 ID 解释为物品标签
     */
    private record Source(ResourceLocation id, boolean tag) {
        /** 解析普通资源 ID 或以 {@code #} 开头的标签 ID。 */
        private static Source parse(String text) {
            return new Source(requireId(text.startsWith("#") ? text.substring(1) : text), text.startsWith("#"));
        }
        /** 判断物品栈是否属于此来源。 */
        private boolean matches(ItemStack stack) {
            return tag ? stack.is(TagKey.create(Registries.ITEM, id)) : BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
        }
        /** 返回来源中当前已定价物品的最低单价；无有效成员时返回 0。 */
        private long minimumPrice(Map<ResourceLocation, Long> resolved) {
            if (!tag) return resolved.getOrDefault(id, 0L);
            return BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id))
                    .map(items -> {
                        long cheapest = Long.MAX_VALUE;
                        for (var holder : items) {
                            Long price = resolved.get(BuiltInRegistries.ITEM.getKey(holder.value()));
                            if (price != null && price > 0) cheapest = Math.min(cheapest, price);
                        }
                        return cheapest == Long.MAX_VALUE ? 0L : cheapest;
                    }).orElse(0L);
        }
    }
}
