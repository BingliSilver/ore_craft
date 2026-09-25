package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.block.entity.OreConverterBlockEntity;
import com.lazeroX.ore_craft.menu.OreConverterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 矿质传输接口的双格界面，显示原料、矿质容器和五秒转换进度。
 * 绘制值仅供提示，物品消耗与 ME 记账全部由服务端方块实体执行。
 */
public final class OreConverterScreen extends AbstractContainerScreen<OreConverterMenu> {
    /** 与原版小型容器一致的界面宽高，容纳两格机器库存和玩家背包。 */
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;
    /** 黑石面板、金边与青蓝进度条的 ARGB 颜色。 */
    private static final int PANEL = 0xFF232630;
    private static final int BORDER = 0xFFE8AD3D;
    private static final int CYAN = 0xFF37DDF3;

    /** 使用菜单标题构造与传输接口服务端库存对应的界面。 */
    public OreConverterScreen(OreConverterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 72;
    }

    /** 绘制方块面板、两个机器格和表示下一轮转换时间的青蓝进度条。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BORDER);
        graphics.fill(x + 2, y + 2, x + WIDTH - 2, y + HEIGHT - 2, PANEL);
        // 原料和容器分别持久保存在方块内，图案与对应的菜单槽位对齐。
        drawSlot(graphics, x + 45, y + 34);
        drawSlot(graphics, x + 109, y + 34);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) drawSlot(graphics, x + 7 + column * 18, y + 83 + row * 18);
        }
        for (int column = 0; column < 9; column++) drawSlot(graphics, x + 7 + column * 18, y + 141);
        graphics.fill(x + 62, y + 63, x + 114, y + 69, 0xFF111820);
        int progress = Math.clamp(menu.progressTicks(), 0, OreConverterBlockEntity.INTERVAL_TICKS);
        graphics.fill(x + 63, y + 64, x + 63 + 50 * progress / OreConverterBlockEntity.INTERVAL_TICKS,
                y + 68, CYAN);
    }

    /** 绘制一个 18 像素见方的槽位框，不额外创建可交互格。 */
    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF5D6170);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF111820);
    }

    /** 标明原料、容器和处理周期，避免把进度条误认为实时到账数值。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xFFF5D58C, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.converter.input"), 35, 23, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.converter.container"), 99, 23, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.converter.rate"), 62, 53, 0xFF9BDCE8, false);
        graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFDDE9F4, false);
    }

    /** 由容器基类绘制物品和悬停提示，并先铺设暗色背景。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
