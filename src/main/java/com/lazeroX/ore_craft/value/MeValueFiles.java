package com.lazeroX.ore_craft.value;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理可由玩家手动编辑的 ME 价格文件。
 *
 * <p>全局文件位于模组配置目录，存档文件位于当前存档根目录；存档文件中的同名物品会覆盖全局值。</p>
 */
public final class MeValueFiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIRECTORY = "ore_craft";
    private static final String FILE_NAME = "me_values.json";

    /** 文件作用域，决定价格写入全局配置还是当前存档。 */
    public enum Scope {
        GLOBAL,
        WORLD
    }

    private MeValueFiles() {
    }

    /**
     * 读取全局文件和当前存档文件，并按存档覆盖全局的顺序合并。
     *
     * @param server 当前服务端
     * @return 合并后的物品 ID 与 ME 单价
     */
    public static Map<ResourceLocation, Long> load(MinecraftServer server) {
        Map<ResourceLocation, Long> values = new HashMap<>();
        values.putAll(read(path(Scope.GLOBAL, server)));
        values.putAll(read(path(Scope.WORLD, server)));
        return values;
    }

    /**
     * 写入一个价格并保留文件中的其他物品。
     *
     * @param server 当前服务端
     * @param scope 文件作用域
     * @param id 物品注册 ID
     * @param value 每件物品的 ME 单价
     * @throws IOException 创建目录或写入文件失败时抛出
     */
    public static void write(MinecraftServer server, Scope scope, ResourceLocation id, long value) throws IOException {
        Path file = path(scope, server);
        Files.createDirectories(file.getParent());
        Map<ResourceLocation, Long> values = read(file);
        values.put(id, value);
        JsonObject root = new JsonObject();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> root.addProperty(entry.getKey().toString(), entry.getValue()));
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(root, writer);
        }
    }

    /** 返回指定作用域的文件路径。 */
    public static Path path(Scope scope, MinecraftServer server) {
        Path root = scope == Scope.GLOBAL
                ? FMLPaths.CONFIGDIR.get()
                : server.getWorldPath(LevelResource.ROOT);
        return root.resolve(DIRECTORY).resolve(FILE_NAME);
    }

    /** 读取扁平 JSON 对象；单个非法条目不会阻止其他条目加载。 */
    private static Map<ResourceLocation, Long> read(Path file) {
        Map<ResourceLocation, Long> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new JsonParseException("ME value file must be a JSON object");
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                long value = entry.getValue().getAsLong();
                if (id == null || !entry.getKey().contains(":") || value <= 0
                        || !BuiltInRegistries.ITEM.containsKey(id)) {
                    LOGGER.error("Ignoring invalid ME value in {}: {}", file, entry.getKey());
                    continue;
                }
                values.put(id, value);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Unable to read ME value file {}", file, exception);
        }
        return values;
    }
}
