package com.lazeroX.ore_craft.value;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.register.ModEnchantments;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * 随身道具共用的末影账户接入判定，只判断资格，不读取或扣除 ME。
 * 实体容器可放在随身背包或副手；附魔盔甲必须实际穿在对应盔甲槽，放在背包中无效。
 */
public final class EnderContainerAccess {
    /** 无状态规则工具类不实例化。 */
    private EnderContainerAccess() {}

    /**
     * 判断玩家是否可按携带末影容器的规则，优先且仅从全局账户为随身道具付款。
     * 多件附魔盔甲或多个容器不会重复扣费；每次使用重新读取装备，脱下后立即失效。
     * @param player 需要使用随身道具的玩家
     * @return 正确穿戴附魔盔甲或随身携带末影矿质容器时为 true
     */
    public static boolean hasAccess(Player player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            ItemStack armor = player.getItemBySlot(slot);
            // 校验物品自身的装备槽，防止头部槽中由命令放入的其他附魔物品被误识别。
            if (armor.getItem() instanceof ArmorItem item && item.getEquipmentSlot() == slot
                    && armor.getEnchantmentLevel(ModEnchantments.ENDER_CONTAINER.get()) > 0) return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof EnderOreContainerItem) return true;
        }
        return false;
    }
}
