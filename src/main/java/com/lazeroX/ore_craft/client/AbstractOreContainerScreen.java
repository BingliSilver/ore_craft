package com.lazeroX.ore_craft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 本模组所有菜单界面的公共缩放层，统一管理画布居中、绘制和鼠标坐标换算。
 * Minecraft 已按玩家的界面尺寸设置提供 GUI 坐标；此处仅在画布超出窗口时额外缩小。
 * 子类布局使用固定逻辑坐标，物品提示使用原版 GUI 坐标，均随游戏设置变化。
 *
 * @param <T> 界面绑定的服务端菜单类型
 */
public abstract class AbstractOreContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    /** 画布四周预留的 GUI 空间，单位为已经受游戏界面尺寸影响的像素。 */
    private static final int EDGE_MARGIN = 8;
    /** 额外的布局缩放倍率，不包含 Minecraft 已应用的 GUI 倍率，始终为正数。 */
    private float menuScale = 1.0F;

    /**
     * 设置界面的逻辑画布大小；此时字体尚未初始化，文字测量需在 init 中执行。
     *
     * @param menu 当前菜单
     * @param inventory 玩家背包
     * @param title 本地化标题
     * @param layoutWidth 逻辑画布宽度，单位像素，必须为正数
     * @param layoutHeight 逻辑画布高度，单位像素，必须为正数
     */
    protected AbstractOreContainerScreen(T menu, Inventory inventory, Component title,
                                         int layoutWidth, int layoutHeight) {
        super(menu, inventory, title);
        imageWidth = layoutWidth;
        imageHeight = layoutHeight;
    }

    /**
     * 按游戏提供的 GUI 窗口大小计算画布位置，切换界面尺寸或调整窗口时重新执行。
     * 原版 resize 会重建控件；子类在 super.init 之后使用更新的 leftPos、topPos 定位。
     */
    @Override
    protected void init() {
        super.init();
        // width、height 已经除过游戏 GUI 倍率，不能再次乘除窗口的 getGuiScale。
        // 极小窗口仍保留正数可用尺寸，避免逆变换出现除零或负数倍率。
        float availableWidth = Math.max(1, width - EDGE_MARGIN * 2);
        float availableHeight = Math.max(1, height - EDGE_MARGIN * 2);
        menuScale = Math.min(maximumMenuScale(),
                Math.min(availableWidth / imageWidth, availableHeight / imageHeight));
        leftPos = Math.round((width / menuScale - imageWidth) / 2.0F);
        topPos = Math.round((height / menuScale - imageHeight) / 2.0F);
    }

    /**
     * 返回画布相对原设计尺寸的最大倍率，不改变游戏本身的界面尺寸设置。
     * 附魔台可保留既有的紧凑布局，其他界面默认按一倍逻辑尺寸绘制。
     *
     * @return 大于零且不大于一的最大画布倍率
     */
    protected float maximumMenuScale() {
        return 1.0F;
    }

    /**
     * 返回世界暗幕的 ARGB 颜色，允许书页和附魔台保留各自原有的背景明暗。
     *
     * @return 覆盖窗口的半透明背景色
     */
    protected int backgroundColor() {
        return 0x99000000;
    }

    /**
     * 将 GUI 鼠标坐标或位移还原到菜单逻辑坐标；子类自定义命中判断也必须使用此方法。
     *
     * @param position 游戏 GUI 坐标系中的位置或位移
     * @return 菜单逻辑坐标，尚未扣除画布左上角
     */
    protected final double menuCoordinate(double position) {
        return position / menuScale;
    }

    /**
     * 绘制画布、槽位和控件，再回到原版 GUI 坐标系绘制物品提示。
     * 提示的边界计算由 Minecraft 处理，避免缩小画布后提示偏离鼠标或提前换行。
     */
    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, backgroundColor());
        int logicalX = (int) Math.floor(menuCoordinate(mouseX));
        int logicalY = (int) Math.floor(menuCoordinate(mouseY));
        graphics.pose().pushPose();
        try {
            graphics.pose().scale(menuScale, menuScale, 1.0F);
            // 原版槽位悬停、拖拽携带物和控件绘制均接收相同的逻辑鼠标位置。
            super.render(graphics, logicalX, logicalY, partialTick);
        } finally {
            // 及时还原矩阵，保证提示和后续其他模组的屏幕绘制不继承额外缩放。
            graphics.pose().popPose();
        }
        renderTooltip(graphics, mouseX, mouseY);
        renderExtraTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 为目录、状态文字或空槽位补充提示，默认没有额外内容。
     * 子类用 menuCoordinate 进行命中判断，但向 renderTooltip 传入原 GUI 鼠标坐标。
     *
     * @param graphics 未应用画布缩放的 GUI 绘制上下文
     * @param mouseX 游戏 GUI 鼠标横坐标
     * @param mouseY 游戏 GUI 鼠标纵坐标
     */
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {}

    /** 只在画布坐标中绘制面板，避免原版背景在缩放后的空间重复铺满窗口。 */
    @Override
    public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    /** 点击使用逆变换后的逻辑坐标；子类回退到此方法时必须传入原 GUI 坐标。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(menuCoordinate(mouseX), menuCoordinate(mouseY), button);
    }

    /** 鼠标释放与点击使用同一坐标系，保持取放物品和双击行为准确。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(menuCoordinate(mouseX), menuCoordinate(mouseY), button);
    }

    /** 拖拽时同时还原鼠标位置和位移，兼容原版分配物品及拖动控件。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(menuCoordinate(mouseX), menuCoordinate(mouseY), button,
                menuCoordinate(dragX), menuCoordinate(dragY));
    }

    /** 将鼠标移动位置还原到控件的逻辑坐标系。 */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(menuCoordinate(mouseX), menuCoordinate(mouseY));
    }

    /** 滚轮仅还原命中位置，滚动量和翻页方向不随界面倍率变化。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(menuCoordinate(mouseX), menuCoordinate(mouseY), scrollX, scrollY);
    }
}
