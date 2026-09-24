package com.lazeroX.ore_craft.item;

import com.lazeroX.ore_craft.player.OreConversionSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * 直接连接持有者全局 ME 账户的末影矿质容器。
 *
 * <p>此物品不保存独立 ME，也不使用存储进度条；余额由玩家账户决定。</p>
 */
public final class EnderOreContainerItem extends Item {
    /**
     * 创建末影矿质容器。
     *
     * @param properties 物品的基础属性
     */
    public EnderOreContainerItem(Properties properties) {
        super(properties);
    }

    /**
     * 读取指定玩家的全局 ME 余额。
     *
     * @param player 使用容器的服务端玩家
     * @return 该玩家账户的当前 ME，而非物品自身的数据
     */
    public long accountMe(ServerPlayer player) {
        return OreConversionSavedData.get(player).account(player).balance();
    }
}
