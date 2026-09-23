package com.lazeroX.ore_craft.value;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lazeroX.ore_craft.Ore_craft;
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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** Server-authoritative prices. Deterministic crafting, cooking and stonecutting recipes are inferred. */
public final class OreConversionPrices {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Config config = new Config(Map.of(), Map.of(), List.of(), Set.of(), Set.of(), List.of());
    private static volatile Map<ResourceLocation, Long> prices = Map.of();
    private static volatile Set<ResourceLocation> basePriced = Set.of();
    private static volatile MinecraftServer lastServer;

    private OreConversionPrices() {}

    public static void registerReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "ore_conversion") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, Long> base = new HashMap<>();
                Map<ResourceLocation, Long> tagBase = new HashMap<>();
                List<Source> sources = new ArrayList<>();
                Set<ResourceLocation> blocked = new HashSet<>();
                Set<ResourceLocation> blockedTags = new HashSet<>();
                List<Conversion> conversions = new ArrayList<>();
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
                        LOGGER.error("Invalid ore conversion data {}", entry.getKey(), ex);
                    }
                });
                config = new Config(Map.copyOf(base), Map.copyOf(tagBase), List.copyOf(sources),
                        Set.copyOf(blocked), Set.copyOf(blockedTags), List.copyOf(conversions));
                prices = Map.copyOf(base);
                basePriced = Set.copyOf(base.keySet());
                lastServer = null;
            }
        });
    }

    public static void onServerStarted(ServerStartedEvent event) {
        rebuild(event.getServer());
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        // Rebuild after /reload as well as login; prices are never trusted from the client.
        rebuild(server);
        event.getRelevantPlayers().forEach(player -> {
            OreConversionNetwork.sendPrices(player);
            OreConversionNetwork.sendState(player,
                    player.containerMenu instanceof com.lazeroX.ore_craft.menu.OreConversionMenu menu ? menu.containerId : -1);
        });
    }

    private static synchronized void rebuild(MinecraftServer server) {
        if (server == lastServer && !prices.isEmpty()) return;
        Config snapshot = config;
        Map<ResourceLocation, Long> resolved = new HashMap<>();
        snapshot.tagBase().forEach((id, value) -> BuiltInRegistries.ITEM
                .getTag(TagKey.create(Registries.ITEM, id))
                .ifPresent(tag -> tag.forEach(holder -> resolved.put(BuiltInRegistries.ITEM.getKey(holder.value()), value))));
        resolved.putAll(snapshot.base());
        resolved.entrySet().removeIf(entry -> isBlocked(BuiltInRegistries.ITEM.get(entry.getKey())));
        Set<ResourceLocation> anchors = Set.copyOf(resolved.keySet());
        List<Recipe<?>> supported = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!recipe.isSpecial() && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe
                    || recipe instanceof StonecutterRecipe || recipe instanceof AbstractCookingRecipe)) {
                supported.add(recipe);
            }
        }
        // Costs only decrease as new anchors become known. A fixed point handles tags, multi-output and anchored cycles.
        boolean changed;
        int rounds = 0;
        do {
            changed = false;
            for (Recipe<?> recipe : supported) {
                ItemStack output = recipe.getResultItem(server.registryAccess());
                if (output.isEmpty() || !output.isComponentsPatchEmpty() || output.getCount() <= 0) continue;
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
                        if (candidate.isEmpty() || !candidate.isComponentsPatchEmpty() || candidate.hasCraftingRemainingItem()) continue;
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
                long unit = total / output.getCount(); // ProjectE truncates fractional EMC towards zero.
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
        prices = Map.copyOf(resolved);
        basePriced = anchors;
        lastServer = server;
        LOGGER.info("Ore conversion has {} priced items", prices.size());
    }

    public static OptionalLong price(Item item) {
        Long value = prices.get(BuiltInRegistries.ITEM.getKey(item));
        return value == null || value <= 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public static boolean canLearn(ItemStack stack) {
        return isPlain(stack) && canExtract(stack.getItem());
    }

    public static boolean canExtract(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return item != Items.AIR && !isBlocked(item) && price(item).isPresent();
    }

    public static boolean canDeposit(ItemStack stack) {
        if (!isPlain(stack) || !basePriced.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                || price(stack.getItem()).isEmpty()) return false;
        return config.sources().stream().anyMatch(source -> source.matches(stack));
    }

    public static boolean isPlain(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() > 0 && stack.getCount() <= stack.getMaxStackSize()
                && stack.isComponentsPatchEmpty() && !stack.isDamaged();
    }

    public static Map<ResourceLocation, Long> snapshot() { return prices; }

    private static boolean isBlocked(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (config.blocked().contains(id)) return true;
        ItemStack stack = new ItemStack(item);
        return config.blockedTags().stream().anyMatch(tag -> stack.is(TagKey.create(Registries.ITEM, tag)));
    }

    private static ResourceLocation requireId(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !text.contains(":")) throw new JsonParseException("Expected namespace:path: " + text);
        return id;
    }

    private record Config(Map<ResourceLocation, Long> base, Map<ResourceLocation, Long> tagBase,
                          List<Source> sources, Set<ResourceLocation> blocked, Set<ResourceLocation> blockedTags,
                          List<Conversion> conversions) {}

    private record IngredientCost(Source source, int amount) {}
    private record Conversion(ResourceLocation output, int count, List<IngredientCost> ingredients) {}

    private record Source(ResourceLocation id, boolean tag) {
        private static Source parse(String text) {
            return new Source(requireId(text.startsWith("#") ? text.substring(1) : text), text.startsWith("#"));
        }
        private boolean matches(ItemStack stack) {
            return tag ? stack.is(TagKey.create(Registries.ITEM, id)) : BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
        }
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
