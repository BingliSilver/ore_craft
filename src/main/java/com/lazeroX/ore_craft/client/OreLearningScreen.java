package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreLearningMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.register.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 矿质学习宝典的展开书本界面，左页展示学习方法与反馈，右页展示真实玩家背包。
 * 界面不提供提取目录或 ME 交互口，物品提示中的学习标记使用共享账户同步结果。
 */
public final class OreLearningScreen extends AbstractOreContainerScreen<OreLearningMenu> {
    /** 展开书本背景按菜单共用的逻辑尺寸绘制，透明边缘露出游戏场景。 */
    private static final int WIDTH = OreLearningMenu.BOOK_WIDTH;
    private static final int HEIGHT = OreLearningMenu.BOOK_HEIGHT;
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            "ore_craft", "textures/gui/ore_learning_book.png");
    /** 纸面文字采用深棕墨色，辅助说明使用较浅的棕色。 */
    private static final int INK = 0xFF493023;
    private static final int MUTED = 0xFF806548;
    /** 左页文字区域与两页中心坐标，避开装订槽和四角花纹。 */
    private static final int TEXT_X = 48;
    private static final int TEXT_WIDTH = 140;
    private static final int LEFT_PAGE_CENTER = 118;
    private static final int RIGHT_PAGE_CENTER = 313;
    /** 最近一次服务端学习结果；尚无结果时显示纸面上的操作提示。 */
    private Component statusMessage = Component.empty();
    /** 操作成功与失败分别显示绿色和红色。 */
    private boolean statusSuccess;
    /** 反馈剩余显示时间，单位客户端刻；一次显示 120 刻。 */
    private int statusTicks;

    /**
     * 创建与服务端背包槽位对应的学习界面。
     *
     * @param menu 当前学习菜单
     * @param inventory 玩家背包
     * @param title 菜单标题
     */
    public OreLearningScreen(OreLearningMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = OreLearningMenu.INVENTORY_X;
        inventoryLabelY = 42;
    }

    /**
     * 在屏幕获得字体后计算左页标题位置，并按窗口大小缩放、居中整本书。
     * 构造阶段的 font 尚未初始化，不能在构造方法中测量文字宽度。
     */
    @Override
    protected void init() {
        super.init();
        // 缩放与居中由公共父类完成，此处只计算字体相关的书页标题位置。
        titleLabelX = LEFT_PAGE_CENTER - font.width(title) / 2;
        titleLabelY = 42;
    }

    /** 保留纸质书页界面原有的较浅暗幕。 */
    @Override
    protected int backgroundColor() {
        return 0x90000000;
    }

    /** 绘制书本贴图和与真实槽位对齐的纸面凹槽，槽位操作仍由原版容器处理。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF9D8158);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFFDFCAA0);
            graphics.fill(x + 1, y + 1, x + 17, y + 2, 0xFFC5AC7E);
            graphics.fill(x + 1, y + 16, x + 17, y + 17, 0xFFF6E8C6);
        }
        // 两页标题下的细线使用暖金色，保持纸面而非机器面板的观感。
        graphics.fill(leftPos + 66, topPos + 57, leftPos + 170, topPos + 58, 0xFFB59359);
        graphics.fill(leftPos + 263, topPos + 57, leftPos + 363, topPos + 58, 0xFFB59359);
        // 左页的小宝典插图使用已注册物品纹理，与手持物品的外观保持一致。
        graphics.pose().pushPose();
        graphics.pose().translate(leftPos + LEFT_PAGE_CENTER - 12, topPos + 65, 0);
        graphics.pose().scale(1.5F, 1.5F, 1.0F);
        graphics.renderItem(ModItems.ORE_LEARNING_BOOK.get().getDefaultInstance(), 0, 0);
        graphics.pose().popPose();
    }

    /** 绘制两页标题、单件及整箱学习说明和学习结果，使中英文长句保持在纸面内。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, INK, false);
        graphics.drawCenteredString(font, playerInventoryTitle, RIGHT_PAGE_CENTER, inventoryLabelY, INK);
        int nextY = drawParagraph(graphics, Component.translatable("gui.ore_craft.learning.action"), 94, INK);
        nextY = drawParagraph(graphics, Component.translatable("gui.ore_craft.learning.rules"), nextY + 5, MUTED);
        // 左页第三段直接说明容器批量学习操作，不增加物品悬停提示的长度。
        drawParagraph(graphics, Component.translatable("gui.ore_craft.learning.container_action"), nextY + 5, MUTED);
        graphics.drawWordWrap(font, Component.translatable("gui.ore_craft.learning.inventory_hint"),
                OreLearningMenu.INVENTORY_X, 67, 162, MUTED);
        graphics.drawString(font, Component.translatable("gui.ore_craft.learning.hotbar"),
                OreLearningMenu.INVENTORY_X, OreLearningMenu.HOTBAR_Y - 12, MUTED, false);

        // 反馈居中并换行，避开左页底部角花；完整显示现有的成功与失败原因。
        Component feedback = statusTicks > 0 ? statusMessage
                : Component.translatable("gui.ore_craft.learning.idle");
        int feedbackColor = statusTicks > 0 ? statusSuccess ? 0xFF37674B : 0xFF9A3E32 : MUTED;
        int feedbackY = 169;
        for (var line : font.split(feedback, 104)) {
            graphics.drawString(font, line, LEFT_PAGE_CENTER - font.width(line) / 2, feedbackY, feedbackColor, false);
            feedbackY += font.lineHeight + 1;
        }
    }

    /**
     * 在左页按文字实际行数绘制段落，不让本地化长句横跨装订槽。
     *
     * @param graphics 绘制上下文
     * @param text 当前说明或反馈
     * @param y 段落起始纵坐标
     * @param color 墨色
     * @return 段落结束后的纵坐标，供下一段依次排版
     */
    private int drawParagraph(GuiGraphics graphics, Component text, int y, int color) {
        for (var line : font.split(text, TEXT_WIDTH)) {
            graphics.drawString(font, line, TEXT_X, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    /** 每客户端刻缩短反馈剩余时间；不会在客户端执行学习或修改背包。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (statusTicks > 0) statusTicks--;
    }

    /**
     * 接收当前菜单的学习反馈，忽略过期菜单或不属于学习功能的消息。
     *
     * @param packet 服务端操作反馈
     */
    public void receiveStatus(OreConversionNetwork.StatusPayload packet) {
        if (packet.containerId() != menu.containerId) return;
        switch (packet.key()) {
            case "learned", "already_learned", "special_state", "unpriced", "learn_limit" -> {
                statusMessage = Component.translatable("message.ore_craft.conversion." + packet.key());
                statusSuccess = packet.key().equals("learned") || packet.key().equals("already_learned");
                statusTicks = 120;
            }
            default -> { }
        }
    }
}
