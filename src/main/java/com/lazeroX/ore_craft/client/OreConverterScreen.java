package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreConverterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 普通版和升级版矿质传输接口共用的双格界面，显示原料、容器和转换进度。
 * 绘制值仅供提示，物品消耗与 ME 记账全部由服务端方块实体执行。
 */
public final class OreConverterScreen extends AbstractOreMachineScreen<OreConverterMenu> {
    /** 纵向机器面板，顶部回收流程与下方背包分区展示。 */
    private static final int WIDTH = 220;
    private static final int HEIGHT = 244;

    /**
     * 使用菜单标题创建原料到容器的回收界面。
     *
     * @param menu 当前传输接口菜单
     * @param inventory 玩家背包
     * @param title 当前设备名称，包含普通或 Plus 版本信息
     */
    public OreConverterScreen(OreConverterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    /** 绘制与转化桌同源的矿石外框、青色回收流程、真实库存槽框及轮次进度。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        OreMachineScreenStyle.frame(graphics, x, y, WIDTH, HEIGHT);
        // 内容收在矿石装饰内侧，背包与快捷栏保持既有的真实槽位坐标。
        OreMachineScreenStyle.panel(graphics, x + 22, y + 48, 176, 82);
        OreMachineScreenStyle.panel(graphics, x + 27, y + 145, 166, 81);
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.getSlot(index);
            // 机器槽统一采用转化桌的青色能量边，输入角色由上方文字与箭头说明。
            OreMachineScreenStyle.slot(graphics, x + slot.x, y + slot.y, index < 2, OreMachineScreenStyle.CYAN);
        }
        OreMachineScreenStyle.arrow(graphics, x + 81, y + 84, 61, OreMachineScreenStyle.CYAN);
        // 进度条与下方文字都距操作区边线八像素，避免缩放后与描边粘连。
        OreMachineScreenStyle.progress(graphics, x + 30, y + 106, 160, menu.progressTicks(), menu.intervalTicks());
        graphics.fill(x + 29, y + 202, x + 191, y + 203, 0xFF53616E);
    }

    /** 绘制用途、角色标签、周期和进度百分比，文字全部位于各自分区内。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component tier = Component.translatable(menu.isUpgraded() ? "gui.ore_craft.machine.plus" : "gui.ore_craft.machine.standard");
        OreMachineScreenStyle.header(graphics, font, title,
                Component.translatable("gui.ore_craft.converter.subtitle"), tier, WIDTH);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.converter.input"), 62, 59, OreMachineScreenStyle.CYAN);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.converter.container"), 158, 59, OreMachineScreenStyle.CYAN);
        OreMachineScreenStyle.rate(graphics, font, Component.translatable(menu.isUpgraded()
                ? "gui.ore_craft.converter.rate_plus" : "gui.ore_craft.converter.rate"),
                30, 117, 160, menu.progressTicks(), menu.intervalTicks());
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, OreMachineScreenStyle.TEXT, false);
    }
}
