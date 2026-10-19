package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.register.ModEnchantments;
import com.lazeroX.ore_craft.register.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 铁砧专用附魔配方：左侧一件盔甲，右侧末影矿质容器，产出带一级末影容器附魔的原盔甲。
 * 事件仅生成结果副本；领取输出时由原版铁砧统一扣经验、消耗材料并处理铁砧损坏。
 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID)
public final class EnderContainerAnvilEvents {
    /** 新赋予附魔的基础经验等级费用；另计原版既有惩罚与本次改名费用。 */
    private static final int BASE_LEVEL_COST = 1;
    /** 原版生存铁砧从 40 级开始拒绝交易，创造模式不受此限制。 */
    private static final int TOO_EXPENSIVE_LEVEL = 40;

    /** 事件订阅工具类不实例化。 */
    private EnderContainerAnvilEvents() {}

    /**
     * 为合法盔甲创建附魔预览，保留耐久、名称、其他附魔及自定义数据。
     * 已有此附魔时拒绝重复加工，避免无效消耗容器或产生超过一级的结果。
     * @param event 铁砧输入或名称变更事件，双端使用相同预览规则
     */
    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        if (!event.getRight().is(ModItems.ENDER_ORE_CONTAINER.get())) return;
        ItemStack armor = event.getLeft();
        Enchantment enchantment = ModEnchantments.ENDER_CONTAINER.get();
        if (armor.getCount() != 1 || !enchantment.canEnchant(armor)
                || armor.getEnchantmentLevel(enchantment) > 0) {
            event.setCanceled(true);
            return;
        }
        // 与其他附魔的冲突也由双向规则判定，不能借专用配方绕过其他模组的兼容限制。
        for (Enchantment existing : armor.getAllEnchantments().keySet()) {
            if (!enchantment.isCompatibleWith(existing)) {
                event.setCanceled(true);
                return;
            }
        }
        ItemStack result = armor.copy();
        result.enchant(enchantment, 1);
        long cost = (long) Math.max(0, armor.getBaseRepairCost())
                + Math.max(0, event.getRight().getBaseRepairCost()) + BASE_LEVEL_COST;
        String name = event.getName();
        if (name != null) {
            if (name.isBlank() && armor.hasCustomHoverName()) {
                result.resetHoverName();
                cost++;
            } else if (!name.isBlank() && !name.equals(armor.getHoverName().getString())) {
                result.setHoverName(Component.literal(name));
                cost++;
            }
        }
        if (cost >= TOO_EXPENSIVE_LEVEL && !event.getPlayer().getAbilities().instabuild) {
            event.setCanceled(true);
            return;
        }
        // 专用结果绕过了原版结果生成，因此在此补上同样的后续加工惩罚，长整型防止溢出。
        long previousPenalty = Math.max(Math.max(0, armor.getBaseRepairCost()),
                Math.max(0, event.getRight().getBaseRepairCost()));
        result.setRepairCost((int) Math.min(Integer.MAX_VALUE, previousPenalty * 2L + 1L));
        event.setCost((int) Math.min(Integer.MAX_VALUE, cost));
        event.setMaterialCost(1);
        event.setOutput(result);
    }
}
