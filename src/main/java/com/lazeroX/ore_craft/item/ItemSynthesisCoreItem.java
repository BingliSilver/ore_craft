package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 物品合成核心，在工作台中作为可重复使用的等价转换催化剂。
 * 转换比例由数据包配方按默认 ME 价格定义，不读取或扣除玩家及矿质容器的 ME。
 * 物品实例由注册器共享；每件核心不可堆叠、没有耐久，合成后保留原物品数据。
 */
public final class ItemSynthesisCoreItem extends Item {
    /**
     * 创建不可堆叠的核心，确保每个合成槽只需返还一件催化剂。
     *
     * @param properties 注册器提供的基础物品属性，不应设置耐久
     */
    public ItemSynthesisCoreItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /**
     * 通知标准合成流程在消耗原料后处理核心返还物。
     *
     * @param stack 参与合成的核心物品栈
     * @return 始终为 true，表示核心不随原料永久消耗
     */
    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return true;
    }

    /**
     * 为每次合成返还一件核心，普通领取和 Shift 批量领取沿用同一返还规则。
     * 此方法仅创建副本，可安全用于客户端预览，不修改输入物品或任何 ME 余额。
     *
     * @param stack 参与本次合成的核心，可能带有自定义名称等 NBT 数据
     * @return 数量为一且保留全部原始数据的独立核心副本
     */
    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack) {
        // 不能直接返回原栈或默认实例：前者会共享可变状态，后者会丢失名称等数据。
        return stack.copyWithCount(1);
    }

    /**
     * 展示等价转换用途、原料摆放方式及核心返还规则。
     *
     * @param stack 当前悬停的核心物品栈
     * @param context 客户端世界，某些物品预览场景下可为空
     * @param tooltip 接收本地化说明的提示列表，本方法会追加内容
     * @param flag 普通或高级物品提示标记
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.ore_craft.item_synthesis_core.purpose").withStyle(ChatFormatting.AQUA));
        // 无序配方中每份原料占一个格子；同一格堆叠多件不能代替多个原料格。
        tooltip.add(Component.translatable("tooltip.ore_craft.item_synthesis_core.layout").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.ore_craft.item_synthesis_core.reusable").withStyle(ChatFormatting.GREEN));
    }
}
