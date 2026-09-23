package com.lazeroX.ore_craft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 放在玩家物品栏中即可自动修复装备的矿工徽章。
 *
 * <p>徽章每 5 秒修复物品栏、护甲栏和副手中所有受损耐久物品 5 点耐久。
 * 同一玩家携带多个徽章时仅第一个生效，防止修复速度叠加。</p>
 */
public class MinerBadgeItem extends Item {
    /** 自动修复的触发间隔：5 秒。 */
    private static final int REPAIR_INTERVAL_TICKS = 20 * 5;

    /** 每次触发时为每件受损物品恢复的耐久值。 */
    private static final int REPAIR_AMOUNT = 5;

    /**
     * 创建矿工徽章。
     *
     * @param properties 物品的基础属性
     */
    public MinerBadgeItem(Properties properties) {
        super(properties);
    }

    /** 为徽章补充修复效果及不可叠加效果的说明。 */
    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);

        // 两条说明分别突出实际能力和多个徽章不会重复生效的限制。
        tooltip.add(Component.translatable("tooltip.ore_craft.miner_badge.repair")
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.ore_craft.miner_badge.no_stack")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 在徽章位于玩家物品栏时定期触发修复。
     *
     * @param stack      当前被轮询的徽章物品栈
     * @param level      徽章所在的世界
     * @param entity     携带该物品栈的实体
     * @param slotId     该物品栈所在的物品栏槽位
     * @param isSelected 当前槽位是否为玩家选中的主手槽位
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        // 只在服务端整秒刻执行，并让玩家物品栏中的第一个徽章负责本轮修复。
        if (level.isClientSide()
                || !(entity instanceof Player player)
                || level.getGameTime() % REPAIR_INTERVAL_TICKS != 0
                || !isFirstBadge(player, stack)) {
            return;
        }

        repairInventory(player.getInventory());
    }

    /**
     * 判断当前物品栈是否为玩家物品栏中第一个矿工徽章。
     *
     * <p>通过物品栈对象身份确定触发者，确保携带多个徽章时每个周期只修复一次。</p>
     *
     * @param player       携带徽章的玩家
     * @param currentBadge 当前执行物品栏刻的徽章物品栈
     * @return 当前物品栈是第一个徽章时返回 {@code true}
     */
    private boolean isFirstBadge(Player player, ItemStack currentBadge) {
        Inventory inventory = player.getInventory();

        // 物品栏容器的遍历顺序固定，因此第一个匹配项可作为唯一触发者。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (stack.is(this)) {
                return stack == currentBadge;
            }
        }

        return false;
    }

    /**
     * 修复玩家完整物品栏中所有带耐久且已经受损的物品。
     *
     * @param inventory 需要检查的玩家物品栏
     */
    private static void repairInventory(Inventory inventory) {
        // 玩家 Inventory 容器同时覆盖主物品栏、快捷栏、护甲栏和副手槽。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (stack.isDamageableItem() && stack.isDamaged()) {
                // 下限限制为 0，避免一次恢复超过剩余损伤后产生负耐久损伤值。
                stack.setDamageValue(Math.max(0, stack.getDamageValue() - REPAIR_AMOUNT));
            }
        }
    }
}
