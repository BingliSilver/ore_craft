package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.item.SpatialLocatorItem;
import com.lazeroX.ore_craft.menu.SpatialLocatorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 空间定位器的回收设置界面；切换时预览每发附加费，按完成后一次性保存。 */
public final class SpatialLocatorScreen extends AbstractOreContainerScreen<SpatialLocatorMenu> {
    /** 当前编辑的两个选项，窗口缩放重建控件时保留。 */
    private int options;

    /** @param menu 当前定位器菜单 @param inventory 玩家背包 @param title 本地化标题 */
    public SpatialLocatorScreen(SpatialLocatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 272, 172);
        options = menu.options();
    }

    /** 创建两个独立开关及完成按钮，使用原版菜单按钮数据包发送合法组合。 */
    @Override
    protected void init() {
        super.init();
        addOption(48, SpatialLocatorItem.COLLECT_ITEMS, "gui.ore_craft.spatial_locator.items");
        addOption(73, SpatialLocatorItem.RECALL_XP, "gui.ore_craft.spatial_locator.xp");
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, options))
                .bounds(leftPos + 86, topPos + 126, 100, 20).build());
    }

    /** @param y 控件纵坐标 @param bit 配置位 @param key 选项标签的翻译键 */
    private void addOption(int y, int bit, String key) {
        addRenderableWidget(Button.builder(label(bit, key), button -> {
            options ^= bit;
            button.setMessage(label(bit, key));
        }).bounds(leftPos + 32, topPos + y, imageWidth - 64, 20).build());
    }

    /** @param bit 配置位 @param key 翻译键 @return 带有当前开关状态的按钮文字 */
    private Component label(int bit, String key) {
        return Component.translatable(key, Component.translatable((options & bit) != 0 ? "options.on" : "options.off"));
    }

    /** 绘制与矿质道具设置菜单一致的边框。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        OreMachineScreenStyle.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
    }

    /** 在菜单的局部坐标绘制标题及按当前选择计算的每发附加费。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 28, OreMachineScreenStyle.TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.spatial_locator.surcharge",
                SpatialLocatorItem.surcharge(options)), imageWidth / 2, 106, OreMachineScreenStyle.CYAN);
    }
}
