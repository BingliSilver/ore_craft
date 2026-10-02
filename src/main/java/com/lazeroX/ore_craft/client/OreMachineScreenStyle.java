package com.lazeroX.ore_craft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 两种矿质机器共用的转化桌风格：矿石石框、青色晶石、深蓝石质面板及扁平槽框。
 * 直接采样转化桌既有纹理的装饰和空白区域，不复制其背包、目录或交易控件。
 * 只绘制背景及装饰，不创建物品槽或影响机器数值，布局由各设备菜单决定。
 */
public final class OreMachineScreenStyle {
    /** 标题、辅助文字与能量强调色，与矿质转化桌保持一致。 */
    public static final int TEXT = 0xFFE9F1F3;
    public static final int MUTED = 0xFFAFBBC5;
    public static final int CYAN = 0xFF85F1EF;
    /** 搜索框及槽位的灰蓝边框，与转化桌的目录控件相同。 */
    public static final int BORDER = 0xFF647989;
    /** 复用转化桌的实际纹理尺寸，避免采样时把纹理像素当作 GUI 像素。 */
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            "ore_craft", "textures/gui/ore_conversion_table.png");
    private static final int TEXTURE_WIDTH = 1728;
    private static final int TEXTURE_HEIGHT = 912;
    /** 四角矿石块的逻辑大小，标准背包槽保持在其内侧。 */
    private static final int CORNER = 28;

    /** 工具类不创建实例；所有方法只向当前绘制上下文提交图形。 */
    private OreMachineScreenStyle() {}

    /**
     * 按四角与四边分别拼接转化桌矿石边框，适配单面板或双面板尺寸。
     * 装饰角保持比例；长边分段重复，避免把晶石横向或纵向拉成细条。
     *
     * @param graphics 当前绘制上下文
     * @param x 画布左边界
     * @param y 画布上边界
     * @param width 画布逻辑宽度
     * @param height 画布逻辑高度
     */
    public static void frame(GuiGraphics graphics, int x, int y, int width, int height) {
        texture(graphics, x + 12, y + 12, width - 24, height - 24, 990, 210, 450, 400);
        // 横边使用原图顶部的青色矿脉，纵边分别保留左侧青矿与右侧金矿装饰。
        for (int offset = CORNER; offset < width - CORNER; offset += 32) {
            int segment = Math.min(32, width - CORNER - offset);
            int sourceWidth = 240 * segment / 32;
            texture(graphics, x + offset, y + 2, segment, 12, 340, 30, sourceWidth, 86);
            texture(graphics, x + offset, y + height - 14, segment, 12, 350, 809, sourceWidth, 86);
        }
        for (int offset = CORNER; offset < height - CORNER; offset += 48) {
            int segment = Math.min(48, height - CORNER - offset);
            int sourceHeight = 350 * segment / 48;
            texture(graphics, x + 2, y + offset, 16, segment, 40, 210, 115, sourceHeight);
            texture(graphics, x + width - 18, y + offset, 16, segment, 1585, 210, 115, sourceHeight);
        }
        // 四角来自原框的钻石、红石、金矿和绿宝石块，透明外缘继续露出世界背景。
        texture(graphics, x, y, CORNER, CORNER, 0, 0, 200, 192);
        texture(graphics, x + width - CORNER, y, CORNER, CORNER, 1528, 0, 200, 192);
        texture(graphics, x, y + height - CORNER, CORNER, CORNER, 0, 720, 200, 192);
        texture(graphics, x + width - CORNER, y + height - CORNER, CORNER, CORNER, 1528, 720, 200, 192);
        // 青色细线承接转化桌的发光内边，保持可读内容与装饰的边界清楚。
        outline(graphics, x + 19, y + 18, width - 38, height - 36, 0xFF367F92);
    }

    /**
     * 绘制转化器双面板之间的晶石竖框，位置由左右功能区域的间隔决定。
     *
     * @param graphics 绘制上下文
     * @param x 竖框中心横坐标，不是左边界
     * @param y 画布上边界
     * @param height 画布高度，顶部与底部各保留晶石装饰
     */
    public static void divider(GuiGraphics graphics, int x, int y, int height) {
        texture(graphics, x - 4, y + 18, 8, height - 36, 850, 120, 30, 660);
        texture(graphics, x - 14, y, 28, 28, 795, 0, 142, 145);
        texture(graphics, x - 14, y + height - 28, 28, 28, 795, 767, 142, 145);
    }

    /**
     * 从转化桌纹理采样一个矩形，目标坐标均为公共缩放层中的逻辑像素。
     *
     * @param graphics 绘制上下文
     * @param x 目标横坐标
     * @param y 目标纵坐标
     * @param width 目标宽度
     * @param height 目标高度
     * @param sourceX 原纹理左边界
     * @param sourceY 原纹理上边界
     * @param sourceWidth 原纹理采样宽度
     * @param sourceHeight 原纹理采样高度
     */
    private static void texture(GuiGraphics graphics, int x, int y, int width, int height,
                                int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
        graphics.blit(BACKGROUND, x, y, width, height, sourceX, sourceY,
                sourceWidth, sourceHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    /**
     * 绘制带石质纹理的内嵌面板，灰蓝细边与转化桌的背包、目录分区一致。
     *
     * @param graphics 绘制上下文
     * @param x 面板左边界
     * @param y 面板上边界
     * @param width 面板逻辑宽度
     * @param height 面板逻辑高度
     */
    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        texture(graphics, x, y, width, height, 990, 210, 450, 400);
        graphics.fill(x, y, x + width, y + height, 0x5A0A1016);
        outline(graphics, x, y, width, height, BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFF394A56);
    }

    /**
     * 绘制与转化桌同色的物品槽；机器格加宽青色装饰边，背包格保持标准 18 像素间距。
     *
     * @param graphics 绘制上下文
     * @param itemX 物品图标横坐标，与菜单槽位一致
     * @param itemY 物品图标纵坐标，与菜单槽位一致
     * @param machine 是否为机器操作格
     * @param accent 机器格下沿的提示色
     */
    public static void slot(GuiGraphics graphics, int itemX, int itemY, boolean machine, int accent) {
        int inset = machine ? 5 : 1;
        int size = 16 + inset * 2;
        int x = itemX - inset;
        int y = itemY - inset;
        graphics.fill(x, y, x + size, y + size, 0xEB111A22);
        outline(graphics, x, y, size, size, machine ? accent : 0xFF627383);
        graphics.fill(x + 1, y + 1, x + size - 1, y + 2, machine ? 0xFF34595C : 0xFF2B3946);
        if (machine) outline(graphics, x + 2, y + 2, size - 4, size - 4, BORDER);
    }

    /** 绘制从左到右的能量流箭头，突出两种机器各自的输入输出顺序。 */
    public static void arrow(GuiGraphics graphics, int x, int y, int length, int color) {
        graphics.fill(x, y, x + length - 4, y + 1, 0xFF647989);
        graphics.fill(x + length - 5, y - 2, x + length - 3, y + 3, color);
        graphics.fill(x + length - 3, y - 1, x + length - 1, y + 2, color);
        graphics.fill(x + length - 1, y, x + length, y + 1, color);
    }

    /**
     * 绘制实际生产轮次的进度，避免将单轮等待时间误当成容器存储量。
     *
     * @param graphics 绘制上下文
     * @param x 进度条左边界
     * @param y 进度条上边界
     * @param width 进度条逻辑宽度，高度固定为六像素
     * @param progress 本轮已累计的游戏刻数
     * @param interval 当前设备等级的轮次总游戏刻数
     */
    public static void progress(GuiGraphics graphics, int x, int y, int width, int progress, int interval) {
        graphics.fill(x, y, x + width, y + 6, 0xFF09151C);
        outline(graphics, x, y, width, 6, BORDER);
        int filled = (width - 2) * Math.clamp(progress, 0, interval) / Math.max(1, interval);
        if (filled > 0) {
            graphics.fillGradient(x + 1, y + 1, x + 1 + filled, y + 5, CYAN, 0xFF329B9A);
        }
    }

    /**
     * 在预留好内边距的区域绘制速率和右对齐进度，并为两段文字保留八像素间隔。
     * 先测量百分比，再限制速率文字的可用宽度，防止较长本地化内容与进度重叠。
     *
     * @param graphics 已平移到画布左上角的绘制上下文
     * @param font 已初始化的游戏字体
     * @param label 普通或 Plus 版的转化速率说明
     * @param x 文字区左边界，调用方应与面板边框保留间距
     * @param y 文字区上边界
     * @param width 文字区可用宽度，包含速率、留白和进度百分比
     * @param progress 本轮已累计的游戏刻数
     * @param interval 当前设备的正数轮次时长，单位为游戏刻
     */
    public static void rate(GuiGraphics graphics, Font font, Component label, int x, int y, int width,
                            int progress, int interval) {
        String percent = Math.clamp(progress, 0, interval) * 100 / interval + "%";
        int percentX = x + width - font.width(percent);
        graphics.drawString(font, ellipsize(font, label.getString(), percentX - x - 8),
                x, y, MUTED, false);
        graphics.drawString(font, percent, percentX, y, CYAN, false);
    }

    /**
     * 绘制紧凑标题、用途与等级徽标，长标题省略后也不覆盖四角矿石装饰。
     *
     * @param graphics 已平移到画布左上角的绘制上下文
     * @param font 已初始化的字体
     * @param title 设备名称
     * @param subtitle 能量转换方向
     * @param tier 普通或 Plus 等级
     * @param width 当前设备画布宽度
     */
    public static void header(GuiGraphics graphics, Font font, Component title, Component subtitle,
                              Component tier, int width) {
        int badgeWidth = font.width(tier) + 10;
        int badgeX = width - 32 - badgeWidth;
        graphics.drawString(font, ellipsize(font, title.getString(), badgeX - 37), 29, 23, TEXT, false);
        graphics.drawString(font, subtitle, 29, 36, MUTED, false);
        graphics.fill(badgeX, 21, badgeX + badgeWidth, 34, 0xFF24565C);
        outline(graphics, badgeX, 21, badgeWidth, 13, BORDER);
        graphics.drawString(font, tier, badgeX + 5, 23, CYAN, false);
    }

    /**
     * 绘制机器清空按钮，配色与转化桌分类、分页按钮一致。
     *
     * @param graphics 绘制上下文
     * @param font 当前字体
     * @param label 按钮文字
     * @param x 按钮左边界
     * @param y 按钮上边界
     * @param width 按钮宽度
     * @param height 按钮高度
     * @param active 是否允许提交操作
     * @param highlighted 是否悬停或通过键盘聚焦
     */
    public static void button(GuiGraphics graphics, Font font, Component label, int x, int y,
                              int width, int height, boolean active, boolean highlighted) {
        graphics.fill(x, y, x + width, y + height,
                !active ? 0xFF252D36 : highlighted ? 0xFF405760 : 0xFF2A333D);
        outline(graphics, x, y, width, height, active && highlighted ? CYAN : 0xFF667989);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFF3D4E5C);
        graphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2, active ? TEXT : MUTED);
    }

    /**
     * 为长物品名附加省略号，给每行末尾的 ME 数值保留独立空间。
     *
     * @param font 已初始化的字体
     * @param text 原始名称
     * @param width 可用像素宽度
     * @return 完整名称、带省略号的名称或宽度不足时的空串
     */
    public static String ellipsize(Font font, String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        String ellipsis = "...";
        if (font.width(ellipsis) > width) return "";
        return font.plainSubstrByWidth(text, width - font.width(ellipsis)) + ellipsis;
    }

    /** 用一像素边线描出矩形，不改变内部内容或鼠标命中范围。 */
    public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
