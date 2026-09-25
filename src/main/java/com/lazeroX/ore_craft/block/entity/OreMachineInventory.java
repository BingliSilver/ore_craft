package com.lazeroX.ore_craft.block.entity;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 将矿质机器已有的持久库存暴露为原版漏斗可识别的带方向容器。
 * 具体方块实体只需声明各面的槽位与进出规则；物品变动仍交给原库存保存并按需要调整计时。
 */
public interface OreMachineInventory extends WorldlyContainer {
    /** 返回方块实体原有的真实物品库存，不包括虚拟选择框。 */
    SimpleContainer inventory();

    /** 返回真实库存格数，供漏斗枚举有效槽位。 */
    @Override
    default int getContainerSize() { return inventory().getContainerSize(); }

    /** 库存中没有物品时返回 true。 */
    @Override
    default boolean isEmpty() { return inventory().isEmpty(); }

    /** 读取指定真实槽位中的物品。 */
    @Override
    default ItemStack getItem(int slot) { return inventory().getItem(slot); }

    /** 取走物品并让原库存触发保存及其自定义计时处理。 */
    @Override
    default ItemStack removeItem(int slot, int amount) { return inventory().removeItem(slot, amount); }

    /** 无通知地移除物品；调用者负责随后通知库存变化。 */
    @Override
    default ItemStack removeItemNoUpdate(int slot) { return inventory().removeItemNoUpdate(slot); }

    /** 写入物品并让原库存触发保存及其自定义计时处理。 */
    @Override
    default void setItem(int slot, ItemStack stack) { inventory().setItem(slot, stack); }

    /** 玩家交互仍需通过当前世界、方块实体和距离校验。 */
    @Override
    default boolean stillValid(Player player) {
        return this instanceof BlockEntity entity && Container.stillValidBlockEntity(entity, player);
    }

    /** 清空真实库存，避免虚拟选择物品被漏斗识别。 */
    @Override
    default void clearContent() { inventory().clearContent(); }
}
