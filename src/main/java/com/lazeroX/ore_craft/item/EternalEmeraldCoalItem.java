package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.player.OreConversionSavedData;
import com.lazeroX.ore_craft.register.ModItems;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 可重复使用的绿宝石煤炭燃料。记录最后持有它的玩家，之后每次点火从该玩家的全局 ME 账户扣款。
 * 物品在燃料槽中保留，余额不足或绿宝石煤炭未定价时停止供燃。
 */
public class EternalEmeraldCoalItem extends Item {
    /** 物品自定义数据中的所属玩家 UUID 键。 */
    private static final String OWNER_KEY = "EternalEmeraldCoalOwner";

    /**
     * 创建不可堆叠的永恒绿宝石煤炭。
     *
     * @param properties 物品基础属性，注册时设置最大堆叠数为一
     */
    public EternalEmeraldCoalItem(Properties properties) {
        super(properties);
    }

    /**
     * 在服务端记录当前持有者；物品转交另一名玩家后，后续点火改从新持有者扣款。
     *
     * @param stack 当前物品栈
     * @param level 所在世界
     * @param entity 持有物品的实体
     * @param slotId 背包槽位
     * @param isSelected 是否被选中
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide && entity instanceof ServerPlayer player && !player.getUUID().equals(owner(stack))) {
            // 仅在持有者变化时写入，避免每刻改写物品组件与背包同步数据。
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(OWNER_KEY, player.getUUID()));
        }
    }

    /**
     * 查询当前是否可支付一轮燃烧；炉子也会用此方法判断燃料是否有效。
     *
     * @param stack 待点燃的物品
     * @param recipeType 炉子配方类型
     * @return 可支付时为普通绿宝石煤炭的当前燃烧时间，否则为零
     */
    @Override
    public int getBurnTime(ItemStack stack, @Nullable RecipeType<?> recipeType) {
        UUID owner = owner(stack);
        if (owner == null) return 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        // 客户端没有权威余额；仅供槽位预判，实际点火由服务端重新核验。
        if (server == null || !server.isSameThread()) return ModItems.EMERALD_COAL.get().getDefaultInstance().getBurnTime(recipeType);
        OptionalLong cost = OreConversionPrices.price(ModItems.EMERALD_COAL.get());
        return cost.isPresent() && OreConversionSavedData.get(server).account(owner).balance() >= cost.getAsLong()
                ? ModItems.EMERALD_COAL.get().getDefaultInstance().getBurnTime(recipeType) : 0;
    }

    /**
     * 让炉子在点火后用返还物替换燃料槽中的原物品，避免消耗物品本体。
     *
     * @param stack 当前物品栈
     * @return 始终为 true，使炉子走燃料返还分支
     */
    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return true;
    }

    /**
     * 炉子实际开始新一轮燃烧时扣除一个绿宝石煤炭的 ME 单价，并返还同一物品。
     * 点火前的燃料查询已检查余额；此处再次检查，确保扣款是原子操作。
     *
     * @param stack 已用于点火的物品栈
     * @return 保留原有所属玩家数据的物品副本
     */
    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        UUID owner = owner(stack);
        // 客户端可能预览配方返还物，扣款只允许在服务端主线程执行。
        if (server != null && server.isSameThread() && owner != null) {
            OptionalLong cost = OreConversionPrices.price(ModItems.EMERALD_COAL.get());
            if (cost.isPresent()) OreConversionSavedData.get(server).debit(owner, cost.getAsLong());
        }
        return stack.copyWithCount(1);
    }

    /**
     * 显示燃烧时长、扣费规则和所属玩家的判定方式。
     *
     * @param stack 当前物品栈
     * @param context 提示上下文
     * @param tooltip 待追加的提示列表
     * @param flag 提示显示选项
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.eternal_emerald_coal.fuel").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.ore_craft.eternal_emerald_coal.me").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.ore_craft.eternal_emerald_coal.owner").withStyle(ChatFormatting.GRAY));
    }

    /**
     * 读取最后持有者 UUID；未经过玩家背包的物品没有所属玩家。
     *
     * @param stack 待查询的物品
     * @return 所属玩家 UUID，尚未绑定时返回 null
     */
    @Nullable
    private static UUID owner(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID(OWNER_KEY) ? tag.getUUID(OWNER_KEY) : null;
    }
}
