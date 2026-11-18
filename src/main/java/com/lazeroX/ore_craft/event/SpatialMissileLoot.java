package com.lazeroX.ore_craft.event;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.SpatialMissileStrikeEntity;
import com.lazeroX.ore_craft.item.SpatialLocatorItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** 根据致命伤害来源回收导弹击杀奖励，只处理该次死亡实际产生的掉落物和经验。 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID)
public final class SpatialMissileLoot {
    /** 经验事件没有伤害源，用死亡事件关联发射者；弱键允许无经验掉落的实体正常释放。 */
    private static final Map<LivingEntity, UUID> EXPERIENCE_OWNERS = new WeakHashMap<>();

    /** 事件处理器由 Forge 调用，无需实例。 */
    private SpatialMissileLoot() {}

    /** @param event 本次死亡的掉落列表；按原物品栈入包，仅将装不下的余量留在玩家脚边 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof SpatialMissileStrikeEntity strike)
                || !strike.hasRecoveryOption(SpatialLocatorItem.COLLECT_ITEMS)
                || !(event.getSource().getEntity() instanceof ServerPlayer owner)) return;
        var inventory = owner.getInventory();
        var drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemEntity drop = drops.next();
            var stack = drop.getItem();
            // add 会修改原栈的剩余数量；满包时跳过，避免创造模式的插入逻辑吞掉余量。
            if (inventory.getSlotWithRemainingSpace(stack) >= 0 || inventory.getFreeSlot() >= 0) {
                inventory.add(stack);
            }
            if (stack.isEmpty()) {
                drops.remove();
                continue;
            }
            drop.setPos(owner.position());
            drop.setDeltaMovement(Vec3.ZERO);
            drop.setNoPickUpDelay();
        }
        inventory.setChanged();
    }

    /** @param event 记录致命一击的发射者，使延后结算的经验也沿用该枚导弹的设置 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getSource().getDirectEntity() instanceof SpatialMissileStrikeEntity strike
                && strike.hasRecoveryOption(SpatialLocatorItem.RECALL_XP)
                && event.getSource().getEntity() instanceof ServerPlayer owner) {
            EXPERIENCE_OWNERS.put(event.getEntity(), owner.getUUID());
        } else {
            EXPERIENCE_OWNERS.remove(event.getEntity());
        }
    }

    /** @param event 按此次死亡实际应掉落的经验值在发射者位置生成经验球，并清零原位置的奖励 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExperience(LivingExperienceDropEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || event.getDroppedExperience() <= 0) return;
        UUID id = EXPERIENCE_OWNERS.get(event.getEntity());
        if (id == null) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(id);
        // 延迟结算时玩家可能已离线或离开该维度，此时保留原来的经验球生成位置。
        if (owner == null || owner.level() != level || !owner.isAlive()) return;
        ExperienceOrb.award(level, owner.position(), event.getDroppedExperience());
        event.setDroppedExperience(0);
    }
}
