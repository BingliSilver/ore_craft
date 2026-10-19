package com.lazeroX.ore_craft.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * 将穿戴中的盔甲作为全局 ME 账户入口的一级附魔。
 * 注册实例不保存余额或玩家状态；只有铁砧消耗末影矿质容器时才提供常规获取途径。
 * 四个盔甲槽均可生效，多件装备只表示已接通账户，不叠加费用或提供额外 ME。
 */
public final class EnderContainerEnchantment extends Enchantment {
    /** 创建仅适用于四类盔甲的附魔，物品资格与附魔台获取资格分别校验。 */
    public EnderContainerEnchantment() {
        super(Rarity.RARE, EnchantmentCategory.ARMOR,
                new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
    }

    /** @return 固定为一级，铁砧合并也不能升级为二级 */
    @Override
    public int getMaxLevel() { return 1; }

    /**
     * 限定头盔、胸甲、护腿和靴子；鞘翅、书以及仅能放入头部槽的普通物品不属于盔甲。
     * @param stack 待检查物品
     * @return 物品是盔甲时为 true
     */
    @Override
    public boolean canEnchant(ItemStack stack) { return stack.getItem() instanceof ArmorItem; }

    /** @param stack 附魔台中的目标物品 @return 始终禁止通过附魔台随机赋予 */
    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack) { return false; }

    /** @return 禁止附魔台生成本附魔的书，也不向创造模式附魔书列表添加条目 */
    @Override
    public boolean isAllowedOnBooks() { return false; }

    /** @return 不加入随机附魔和原版随机战利品附魔候选池 */
    @Override
    public boolean isDiscoverable() { return false; }

    /** @return 不允许村民出售此附魔书，保留消耗实体容器的获取成本 */
    @Override
    public boolean isTradeable() { return false; }
}
