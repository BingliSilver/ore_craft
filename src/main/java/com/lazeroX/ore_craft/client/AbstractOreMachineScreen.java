package com.lazeroX.ore_craft.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 矿质机器界面的通用布局层，为传输接口和转化器提供一致的标题与背包标签位置。
 * 缩放和输入处理由所有菜单共用的父类负责，避免机器使用独立的 GUI 倍率。
 *
 * @param <T> 对应设备的服务端菜单类型
 */
public abstract class AbstractOreMachineScreen<T extends AbstractContainerMenu> extends AbstractOreContainerScreen<T> {
    /**
     * 设置机器界面的逻辑尺寸和通用标题位置；字体测量留到初始化之后执行。
     *
     * @param menu 当前设备菜单
     * @param inventory 玩家背包
     * @param title 界面标题
     * @param layoutWidth 逻辑画布宽度，单位像素
     * @param layoutHeight 逻辑画布高度，单位像素
     */
    protected AbstractOreMachineScreen(T menu, Inventory inventory, Component title, int layoutWidth, int layoutHeight) {
        super(menu, inventory, title, layoutWidth, layoutHeight);
        titleLabelX = 29;
        titleLabelY = 23;
        inventoryLabelX = 29;
        inventoryLabelY = 135;
    }
}
