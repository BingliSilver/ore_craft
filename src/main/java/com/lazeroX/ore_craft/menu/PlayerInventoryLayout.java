package com.lazeroX.ore_craft.menu;

/** 玩家背包在菜单底图中的像素坐标，主背包和快捷栏可以独立对齐。
 * @param x 主背包第一列横坐标
 * @param y 主背包第一行纵坐标
 * @param hotbarX 快捷栏第一列横坐标
 * @param hotbarY 快捷栏纵坐标
 * @param spacing 相邻槽位中心的像素间距
 */
public record PlayerInventoryLayout(int x, int y, int hotbarX, int hotbarY, int spacing) {
    /** 记录菜单底图采用的槽位布局，坐标以界面左上角为原点。 */
    public PlayerInventoryLayout {}

    /** 接收背包索引和显示位置，由菜单决定是否锁定槽位或限制物品。 */
    @FunctionalInterface
    public interface SlotAdder {
        /** 向菜单追加一个槽位，index 为玩家库存索引，x、y 为图标左上角坐标。 */
        public void add(int index, int x, int y);
    }

    /** 按主背包、快捷栏的顺序注册槽位，保证两端菜单和快速移动使用相同的菜单索引。
     * @param slots 当前菜单用于创建并追加槽位的回调
     */
    public void addSlots(SlotAdder slots) {
        // 原版库存的前九格属于快捷栏，因此主背包从索引 9 开始。
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                slots.add(9 + row * 9 + column, x + column * spacing, y + row * spacing);
            }
        }
        // 快捷栏紧接主背包注册，其纵坐标由底图预留的间隔决定。
        for (int column = 0; column < 9; column++) {
            slots.add(column, hotbarX + column * spacing, hotbarY);
        }
    }
}
