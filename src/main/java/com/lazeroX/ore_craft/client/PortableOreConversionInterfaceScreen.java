package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.PortableOreConversionInterfaceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 便携矿质接口界面，复用传输接口的矿石边框、青色输入流程和槽位风格。 */
public final class PortableOreConversionInterfaceScreen
        extends AbstractOreMachineScreen<PortableOreConversionInterfaceMenu> {
    /** 与机器界面保持一致的固定逻辑画布尺寸。 */
    private static final int WIDTH = 220;
    private static final int HEIGHT = 244;

    /**
     * 创建单向物品回收界面。
     *
     * @param menu 便携接口菜单
     * @param inventory 玩家背包
     * @param title 本地化界面标题
     */
    public PortableOreConversionInterfaceScreen(PortableOreConversionInterfaceMenu menu,
                                                  Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    /** 绘制输入槽到矿质容器的回收方向，不绘制 ME 提取控件。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        OreMachineScreenStyle.frame(graphics, leftPos, topPos, WIDTH, HEIGHT);
        OreMachineScreenStyle.panel(graphics, leftPos + 22, topPos + 48, 176, 82);
        OreMachineScreenStyle.panel(graphics, leftPos + 27, topPos + 145, 166, 81);
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.getSlot(index);
            OreMachineScreenStyle.slot(graphics, leftPos + slot.x, topPos + slot.y,
                    index < 2, OreMachineScreenStyle.CYAN);
        }
        OreMachineScreenStyle.arrow(graphics, leftPos + 81, topPos + 84, 61, OreMachineScreenStyle.CYAN);
        graphics.fill(leftPos + 29, topPos + 202, leftPos + 191, topPos + 203, 0xFF53616E);
    }

    /** 标出原料与收款容器角色，并沿用机器背包标签。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        OreMachineScreenStyle.header(graphics, font, title,
                Component.translatable("gui.ore_craft.portable_conversion.subtitle"),
                Component.translatable("gui.ore_craft.portable_conversion.mode"), WIDTH);
        graphics.drawCenteredString(font,
                Component.translatable("gui.ore_craft.portable_conversion.input"), 62, 59,
                OreMachineScreenStyle.CYAN);
        graphics.drawCenteredString(font,
                Component.translatable("gui.ore_craft.portable_conversion.container"), 158, 59,
                OreMachineScreenStyle.CYAN);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                OreMachineScreenStyle.TEXT, false);
    }
}
