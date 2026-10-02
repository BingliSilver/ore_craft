package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.item.MineralTimeScepterItem;
import com.lazeroX.ore_craft.menu.MineralTimeScepterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * 权杖设置界面，使用固定按钮选择倍率和持续秒数，并实时预览施加总价。
 *
 * <p>按钮通过原版菜单操作包发送预设编号；客户端预览会立即变化，
 * 服务端仍会检查菜单和主手物品后才写入权杖设置。</p>
 */
public final class MineralTimeScepterScreen extends AbstractOreContainerScreen<MineralTimeScepterMenu> {
    /** 设置面板宽度，容纳六枚倍率按钮。 */
    private static final int PANEL_WIDTH = 260;
    /** 设置面板高度，容纳倍率、时长和总价。 */
    private static final int PANEL_HEIGHT = 196;
    /** 面板主体、边框和选中条的 ARGB 颜色。 */
    private static final int PANEL_COLOR = 0xFF171324;
    private static final int BORDER_COLOR = 0xFF7457A0;
    private static final int ACCENT_COLOR = 0xFFCFACFF;
    /** 主要文字与次要提示的 ARGB 颜色。 */
    private static final int TEXT_COLOR = 0xFFEADFFF;
    private static final int MUTED_COLOR = 0xFFB7A9C9;

    /**
     * 创建没有背包槽位的权杖设置界面。
     *
     * @param menu 与服务器同步的权杖菜单
     * @param inventory 玩家背包，仅用于标准屏幕构造
     * @param title 菜单标题
     */
    public MineralTimeScepterScreen(MineralTimeScepterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PANEL_WIDTH, PANEL_HEIGHT);
    }

    /** 生成六枚倍率按钮和五枚时间按钮；窗口缩放后由此方法重新定位。 */
    @Override
    protected void init() {
        super.init();
        for (int index = 0; index < MineralTimeScepterItem.MULTIPLIERS.size(); index++) {
            int buttonId = index;
            int multiplier = MineralTimeScepterItem.MULTIPLIERS.get(index);
            addRenderableWidget(Button.builder(Component.literal(multiplier + "×"), button -> select(buttonId))
                    .bounds(leftPos + 6 + index * 42, topPos + 48, 38, 20).build());
        }
        for (int index = 0; index < MineralTimeScepterItem.DURATIONS.size(); index++) {
            int buttonId = MineralTimeScepterItem.MULTIPLIERS.size() + index;
            int seconds = MineralTimeScepterItem.DURATIONS.get(index);
            addRenderableWidget(Button.builder(Component.translatable("gui.ore_craft.time_scepter.seconds", seconds),
                    button -> select(buttonId)).bounds(leftPos + 7 + index * 50, topPos + 99, 46, 20).build());
        }
    }

    /**
     * 先更新本地预览，再发送按钮编号给服务端；真实费用由服务器保存的配置计算。
     *
     * @param buttonId 预设倍率或时长的按钮编号
     */
    private void select(int buttonId) {
        if (minecraft == null || minecraft.player == null || minecraft.gameMode == null) return;
        if (menu.clickMenuButton(minecraft.player, buttonId)) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    /** 绘制深色面板、分隔线以及当前选项下方的紫色标记。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + PANEL_WIDTH, topPos + PANEL_HEIGHT, BORDER_COLOR);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + PANEL_WIDTH - 2,
                topPos + PANEL_HEIGHT - 2, PANEL_COLOR);
        graphics.fill(leftPos + 10, topPos + 77, leftPos + PANEL_WIDTH - 10, topPos + 78, BORDER_COLOR);
        graphics.fill(leftPos + 10, topPos + 130, leftPos + PANEL_WIDTH - 10, topPos + 131, BORDER_COLOR);

        int speedIndex = MineralTimeScepterItem.MULTIPLIERS.indexOf(menu.multiplier());
        if (speedIndex >= 0) {
            int x = leftPos + 6 + speedIndex * 42;
            graphics.fill(x + 3, topPos + 71, x + 35, topPos + 74, ACCENT_COLOR);
        }
        int durationIndex = MineralTimeScepterItem.DURATIONS.indexOf(menu.durationSeconds());
        if (durationIndex >= 0) {
            int x = leftPos + 7 + durationIndex * 50;
            graphics.fill(x + 3, topPos + 122, x + 43, topPos + 125, ACCENT_COLOR);
        }
    }

    /** 显示设置名称、每秒基准价以及随选项变化的一次性总价。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 12, TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.time_scepter.speed"),
                12, 33, MUTED_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.time_scepter.duration"),
                12, 84, MUTED_COLOR, false);
        int multiplier = menu.multiplier();
        int duration = menu.durationSeconds();
        graphics.drawString(font, Component.translatable("gui.ore_craft.time_scepter.rate",
                format(MineralTimeScepterItem.costPerSecond(multiplier))), 12, 140, MUTED_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.time_scepter.total",
                format(MineralTimeScepterItem.totalCost(multiplier, duration))), 12, 156, TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.time_scepter.hint"),
                12, 178, MUTED_COLOR, false);
    }

    /**
     * 用分组逗号显示 ME 数值，避免高倍率长时间费用不易辨认。
     *
     * @param amount 待展示的非负 ME 数值
     * @return 适合界面阅读的数字文字
     */
    private static String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
