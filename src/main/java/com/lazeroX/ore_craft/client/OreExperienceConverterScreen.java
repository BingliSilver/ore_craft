package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.block.entity.OreExperienceConverterBlockEntity;
import com.lazeroX.ore_craft.menu.OreExperienceConverterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 矿质经验转化器的单槽界面，展示供能容器、开关、余额、八个生产档位和轮次进度。
 * 沿用机器公共缩放与矿石边框，所有数值取自服务端菜单同步，不在客户端进行扣费。
 */
public final class OreExperienceConverterScreen extends AbstractOreMachineScreen<OreExperienceConverterMenu> {
    /** 界面逻辑宽度，与现有单面板机器和九列背包一致。 */
    private static final int WIDTH = 220;
    /** 为两排倍率按钮、供能说明及背包保留独立空间，单位为逻辑像素。 */
    private static final int HEIGHT = 330;
    /** 经验主题的绿色，用于供能槽和运行状态提示。 */
    private static final int EXPERIENCE_COLOR = 0xFFAFF36C;
    /** 当前窗口的八个档位按钮，重新初始化时重建，用于显示精确费用提示。 */
    private final List<MultiplierButton> multiplierButtons = new ArrayList<>();

    /**
     * 创建供能与生产状态界面，并将背包标题对齐本菜单的槽位布局。
     *
     * @param menu 同步中的经验转化器菜单
     * @param inventory 玩家背包
     * @param title 机器本地化名称
     */
    public OreExperienceConverterScreen(OreExperienceConverterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelY = 221;
    }

    /** 初始化两排四列的倍率按钮；重开界面或窗口变化后仍以菜单同步档位作为选中值。 */
    @Override
    protected void init() {
        super.init();
        multiplierButtons.clear();
        for (int index = 0; index < OreExperienceConverterBlockEntity.MULTIPLIERS.size(); index++) {
            MultiplierButton button = new MultiplierButton(leftPos + 30 + index % 4 * 41,
                    topPos + 148 + index / 4 * 24, index);
            multiplierButtons.add(addRenderableWidget(button));
        }
    }

    /**
     * 先预测选择并刷新费用预览，再用原版菜单按钮包向服务端提交预设编号。
     *
     * @param buttonId 固定倍率列表的按钮索引，实际有效性由菜单两端共同验证
     */
    private void selectMultiplier(int buttonId) {
        if (minecraft == null || minecraft.player == null || minecraft.gameMode == null) return;
        if (menu.clickMenuButton(minecraft.player, buttonId)) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    /**
     * 沿用原版焦点、键盘和无障碍操作的档位按钮，选中档位使用绿色下沿标记。
     * 选中状态每帧从菜单读取，服务端同步或重新进入界面后不会依赖过期的本地按钮状态。
     */
    private final class MultiplierButton extends Button {
        /** 本按钮对应的合法倍率，数值来自不可变的预设列表。 */
        private final int multiplier;

        /**
         * 创建一个固定宽度的档位按钮，八个按钮共享同一个服务端菜单操作入口。
         *
         * @param x 逻辑画布中的绝对横坐标
         * @param y 逻辑画布中的绝对纵坐标
         * @param index 预设倍率列表中的位置，也作为网络按钮编号
         */
        private MultiplierButton(int x, int y, int index) {
            super(x, y, 37, 20, Component.literal("x" + OreExperienceConverterBlockEntity.MULTIPLIERS.get(index)),
                    button -> selectMultiplier(index), DEFAULT_NARRATION);
            multiplier = OreExperienceConverterBlockEntity.MULTIPLIERS.get(index);
        }

        /** 按公共机器按钮风格绘制，悬停、聚焦或已选中的档位均突出边框。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = menu.multiplier() == multiplier;
            OreMachineScreenStyle.button(graphics, font, getMessage(), getX(), getY(), getWidth(), getHeight(),
                    active, isHoveredOrFocused() || selected);
            if (selected) {
                graphics.fill(getX() + 3, getY() + getHeight() - 4,
                        getX() + getWidth() - 3, getY() + getHeight() - 2, EXPERIENCE_COLOR);
            }
        }
    }

    /** 绘制机器面板、绿色供能槽、经验流向箭头、轮次进度和背包背景。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        OreMachineScreenStyle.frame(graphics, x, y, WIDTH, HEIGHT);
        OreMachineScreenStyle.panel(graphics, x + 22, y + 48, 176, 166);
        OreMachineScreenStyle.panel(graphics, x + 27, y + 231, 166, 81);
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.getSlot(index);
            OreMachineScreenStyle.slot(graphics, x + slot.x, y + slot.y, index == 0, EXPERIENCE_COLOR);
        }
        OreMachineScreenStyle.arrow(graphics, x + 66, y + 83, 12, EXPERIENCE_COLOR);
        OreMachineScreenStyle.progress(graphics, x + 30, y + 112, 160,
                menu.progressTicks(), OreExperienceConverterBlockEntity.INTERVAL_TICKS);
        graphics.fill(x + 29, y + 288, x + 191, y + 289, 0xFF53616E);
    }

    /** 绘制开关徽标、当前档位整轮消耗和产出；余额不足所选档位时明确显示等待。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component status = Component.translatable(menu.isEnabled()
                ? "gui.ore_craft.experience.enabled" : "gui.ore_craft.experience.disabled");
        OreMachineScreenStyle.header(graphics, font, title,
                Component.translatable("gui.ore_craft.experience.subtitle"), status, WIDTH);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.machine.container"),
                52, 59, EXPERIENCE_COLOR);
        // 辅助标签限制在右侧区域内，避免较长本地化文本覆盖供能槽。
        label(graphics, Component.translatable("gui.ore_craft.experience.output",
                menu.multiplier()), 84, 72, 106, EXPERIENCE_COLOR);
        label(graphics, Component.translatable("gui.ore_craft.experience.cost",
                format(menu.cycleCost())), 84, 85, 106, OreMachineScreenStyle.MUTED);
        label(graphics, Component.translatable("gui.ore_craft.experience.balance",
                MeNumberFormat.compact(menu.storedMe())), 30, 100, 160, OreMachineScreenStyle.TEXT);
        Component rate = Component.translatable(!menu.isEnabled() ? "gui.ore_craft.experience.stopped"
                : menu.storedMe() < menu.cycleCost()
                ? "gui.ore_craft.experience.waiting" : "gui.ore_craft.experience.rate");
        OreMachineScreenStyle.rate(graphics, font, rate, 30, 123, 160,
                menu.progressTicks(), OreExperienceConverterBlockEntity.INTERVAL_TICKS);
        label(graphics, Component.translatable("gui.ore_craft.experience.multiplier", menu.multiplier()),
                30, 135, 160, EXPERIENCE_COLOR);
        label(graphics, Component.translatable("gui.ore_craft.experience.toggle"), 30, 200, 160, OreMachineScreenStyle.MUTED);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, OreMachineScreenStyle.TEXT, false);
    }

    /** 按公共缩放层还原按钮命中坐标，并以完整数字提示该档位每秒一轮的消耗和经验点数。 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        double logicalX = menuCoordinate(mouseX);
        double logicalY = menuCoordinate(mouseY);
        for (MultiplierButton button : multiplierButtons) {
            if (button.isMouseOver(logicalX, logicalY)) {
                graphics.renderTooltip(font, Component.translatable("gui.ore_craft.experience.multiplier_hint",
                        button.multiplier, format(OreExperienceConverterBlockEntity.ME_PER_EXPERIENCE * button.multiplier),
                        button.multiplier), mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * 用分组逗号展示完整 ME 金额，避免高倍率费用被缩写后难以与容器余额比较。
     *
     * @param amount 当前档位的非负费用
     * @return 使用英文分组逗号的精确整数文字
     */
    private static String format(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    /**
     * 按可用宽度省略辅助文字，保持中文和英文界面均不超出面板。
     *
     * @param graphics 已平移到界面原点的绘制上下文
     * @param text 本地化说明
     * @param x 文字左边界
     * @param y 文字上边界
     * @param width 可用逻辑像素宽度
     * @param color 文字 ARGB 颜色
     */
    private void label(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, text.getString(), width), x, y, color, false);
    }
}
