package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 可存储固定容量 ME 的矿质容器；进度条仅表示填充比例。 */
public final class OreContainerItem extends Item {
    /** 1.20.1 物品 NBT 中保存 ME 的专用键。 */
    private static final String STORED_ME = "OreCraftStoredMe";
    /** 单个容器可存储的最大 ME。 */
    private final long capacity;

    /**
     * 创建矿质容器。
     *
     * @param capacity 最大 ME 容量，必须为正数
     * @param properties 物品属性；注册时应限制为单件堆叠
     */
    public OreContainerItem(long capacity, Properties properties) {
        super(properties);
        if (capacity <= 0) throw new IllegalArgumentException("Container capacity must be positive");
        this.capacity = capacity;
    }

    /** 返回该等级容器的 ME 容量。 */
    public long capacity() {
        return capacity;
    }

    /**
     * 读取物品栈中持久化的 ME，并将异常数据限制在有效容量内。
     *
     * @param stack 要读取的容器物品栈
     * @return 当前有效的 ME 数量
     */
    public long storedMe(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;
        return Math.max(0L, Math.min(tag.getLong(STORED_ME), capacity));
    }

    /**
     * 写入 ME；值为零时移除专用键，保留物品上其他来源的 NBT。
     *
     * @param stack 要修改的容器物品栈
     * @param amount 新的 ME 数量，必须在零与容量之间
     */
    public void setStoredMe(ItemStack stack, long amount) {
        if (amount < 0 || amount > capacity) throw new IllegalArgumentException("Stored ME exceeds container capacity");
        CompoundTag tag = stack.hasTag() ? stack.getTag().copy() : new CompoundTag();
        if (amount == 0) tag.remove(STORED_ME);
        else tag.putLong(STORED_ME, amount);
        stack.setTag(tag.isEmpty() ? null : tag);
    }

    /** 始终显示用于表示 ME 填充率的物品进度条。 */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    /** 按当前 ME 占容量的比例计算 13 像素进度条宽度。 */
    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) (13 * storedMe(stack) / capacity);
    }

    /** 使用青色区分 ME 存储进度与普通耐久。 */
    @Override
    public int getBarColor(ItemStack stack) {
        return 0x42DDE8;
    }

    /** 在提示中展示精确的存储量与容量。 */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.ore_container.storage", storedMe(stack), capacity)
                .withStyle(ChatFormatting.AQUA));
    }
}
