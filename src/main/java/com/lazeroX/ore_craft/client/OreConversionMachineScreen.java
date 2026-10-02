package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 矿质转化器的双区界面：左侧展示支付、目标、产物和背包，右侧展示价格降序的已学习目录。
 * 外框复用转化桌的矿石与晶石装饰；目录仅发送选择请求，生产和 ME 扣费仍由服务端负责。
 */
public final class OreConversionMachineScreen extends AbstractOreMachineScreen<OreConversionMachineMenu> {
    /** 左右各占 220 像素，晶石分隔线位于整张画布中心，保证两侧严格为 1:1。 */
    private static final int HALF_WIDTH = 220;
    private static final int WIDTH = HALF_WIDTH * 2;
    private static final int HEIGHT = 244;
    /** 两侧主体面板使用相同的宽度、顶部和底部；右侧横向偏移恰好一页。 */
    private static final int PANEL_X = 22;
    private static final int PANEL_Y = 48;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 178;
    /** 目录每行包含图标、名称和右对齐的精确 ME 单价，行间留两像素间距。 */
    private static final int LIST_X = HALF_WIDTH + PANEL_X + 8;
    private static final int LIST_Y = 94;
    private static final int LIST_WIDTH = 152;
    private static final int LIST_ROWS = 6;
    private static final int LIST_STEP = 20;
    private static final int ROW_HEIGHT = 18;
    /** 目录右侧的滚动条位置和完整轨道高度。 */
    private static final int SCROLL_X = LIST_X + LIST_WIDTH + 5;
    private static final int SCROLL_WIDTH = 4;
    private static final int TRACK_HEIGHT = (LIST_ROWS - 1) * LIST_STEP + ROW_HEIGHT;
    /** 支持名称及物品 ID 搜索的输入框。 */
    private EditBox search;
    /** 清空当前生产目标的按钮；没有目标时禁用，避免重复提交。 */
    private Button clearSelection;
    /** 首条可见记录的索引，搜索变化和目录缩小时重新限制范围。 */
    private int firstRow;
    /** 目录点击的释放事件不应交给下方物品槽处理。 */
    private boolean suppressRelease;
    /** 当前是否正在拖动目录滚动条。 */
    private boolean draggingScroll;
    /** 上一次参与筛选的不可变目录引用；网络发布新快照时才重新排序。 */
    private List<OreConversionNetwork.PriceEntry> seenCatalog;
    /** 上一次使用的规范化搜索词。 */
    private String seenQuery = "";
    /** 缓存筛选与排序结果，避免每帧绘制、点击和提示各自排序完整目录。 */
    private List<OreConversionNetwork.PriceEntry> visibleEntries = List.of();

    /**
     * 创建三格生产流程及已学习物品目录的界面。
     *
     * @param menu 当前矿质转化器菜单
     * @param inventory 玩家背包
     * @param title 当前设备名称
     */
    public OreConversionMachineScreen(OreConversionMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    /** 初始化搜索框与清空按钮，窗口改变时保留搜索词并按缩放后的逻辑坐标放置组件。 */
    @Override
    protected void init() {
        String previous = search == null ? "" : search.getValue();
        super.init();
        seenCatalog = null;
        suppressRelease = false;
        draggingScroll = false;
        search = new EditBox(font, leftPos + LIST_X + 20, topPos + 75, LIST_WIDTH - 26, 12,
                Component.translatable("gui.ore_craft.machine.search"));
        search.setMaxLength(64);
        search.setBordered(false);
        search.setTextColor(OreMachineScreenStyle.TEXT);
        search.setTextColorUneditable(OreMachineScreenStyle.MUTED);
        search.setHint(Component.translatable("gui.ore_craft.machine.search"));
        search.setValue(previous);
        search.setResponder(value -> firstRow = 0);
        addRenderableWidget(search);
        // 将清空按钮放回左侧选择区域，右侧页脚只显示结果数量，窄目录也不会互相遮挡。
        clearSelection = addRenderableWidget(new ClearSelectionButton(leftPos + 102, topPos + 132));
        clearSelection.active = menu.getSlot(OreConversionMachineMenu.SELECTION_SLOT).hasItem();
    }

    /**
     * 左侧选择区域下方的转化桌风格按钮，沿用原版焦点、键盘操作与无障碍提示逻辑。
     * 点击只发送菜单按钮请求，由服务端验证所有者并清除生产目标。
     */
    private final class ClearSelectionButton extends Button {
        /**
         * 创建紧凑清空按钮，与左侧背包标题并排，避开速率栏和背包槽。
         *
         * @param x 菜单逻辑横坐标
         * @param y 菜单逻辑纵坐标
         */
        private ClearSelectionButton(int x, int y) {
            super(x, y, 86, 12, Component.translatable("gui.ore_craft.machine.clear_selection"), button -> {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                            OreConversionMachineMenu.CLEAR_SELECTION_BUTTON);
                }
            }, DEFAULT_NARRATION);
        }

        /** 绘制与目录相同的深色边框，悬停或键盘聚焦时用青色突出可操作状态。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            OreMachineScreenStyle.button(graphics, font, getMessage(), getX(), getY(), getWidth(), getHeight(),
                    active, active && isHoveredOrFocused());
        }
    }

    /**
     * 根据当前目录与搜索词更新缓存，按 ME 单价降序排列，同价时按注册 ID 稳定排序。
     *
     * @return 当前搜索下可选择的已学习物品，不包含不可兑换的特殊默认状态
     */
    private List<OreConversionNetwork.PriceEntry> filtered() {
        List<OreConversionNetwork.PriceEntry> catalog = OreConversionClient.catalog();
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (catalog != seenCatalog || !query.equals(seenQuery)) {
            seenCatalog = catalog;
            seenQuery = query;
            visibleEntries = catalog.stream().filter(entry -> {
                Item item = BuiltInRegistries.ITEM.get(entry.id());
                return OreConversionPrices.isPlain(new ItemStack(item)) && (query.isEmpty()
                        || entry.id().toString().toLowerCase(Locale.ROOT).contains(query)
                        || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query));
            }).sorted(Comparator.comparingLong(OreConversionNetwork.PriceEntry::price).reversed()
                    .thenComparing(entry -> entry.id().toString())).toList();
        }
        firstRow = Math.clamp(firstRow, 0, Math.max(0, visibleEntries.size() - LIST_ROWS));
        return visibleEntries;
    }

    /** 绘制生产流程、实际槽位、轮次进度和带价格的目录行。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 以服务端同步的虚拟槽为准，不提前清空客户端图标或修改真实产物。
        if (clearSelection != null) {
            clearSelection.active = menu.getSlot(OreConversionMachineMenu.SELECTION_SLOT).hasItem();
        }
        int x = leftPos;
        int y = topPos;
        OreMachineScreenStyle.frame(graphics, x, y, WIDTH, HEIGHT);
        // 晶石竖框延续转化桌的双页结构，目录与生产区之间保留清晰间隔。
        OreMachineScreenStyle.divider(graphics, x + HALF_WIDTH, y, HEIGHT);
        // 左侧生产与背包合用一个外面板，右侧目录严格复制其尺寸和上下边界。
        OreMachineScreenStyle.panel(graphics, x + PANEL_X, y + PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);
        OreMachineScreenStyle.panel(graphics, x + HALF_WIDTH + PANEL_X, y + PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.fill(x + 30, y + 130, x + 190, y + 131, 0xFF53616E);
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.getSlot(index);
            OreMachineScreenStyle.slot(graphics, x + slot.x, y + slot.y, index < 3, OreMachineScreenStyle.CYAN);
        }
        OreMachineScreenStyle.arrow(graphics, x + 62, y + 84, 31, OreMachineScreenStyle.CYAN);
        OreMachineScreenStyle.arrow(graphics, x + 130, y + 84, 31, OreMachineScreenStyle.CYAN);
        OreMachineScreenStyle.progress(graphics, x + 30, y + 106, 160, menu.progressTicks(), menu.intervalTicks());
        graphics.fill(x + 29, y + 202, x + 191, y + 203, 0xFF53616E);
        graphics.fill(x + LIST_X - 1, y + 70, x + LIST_X + LIST_WIDTH + 1, y + 88, 0xE9111921);
        OreMachineScreenStyle.outline(graphics, x + LIST_X - 1, y + 70, LIST_WIDTH + 2, 18, OreMachineScreenStyle.BORDER);
        graphics.fill(x + LIST_X, y + 71, x + LIST_X + LIST_WIDTH, y + 72, 0xFF394A56);
        drawSearchIcon(graphics, x + LIST_X + 5, y + 75);

        List<OreConversionNetwork.PriceEntry> entries = filtered();
        if (entries.isEmpty()) {
            String key = OreConversionClient.catalog().isEmpty() ? "empty" : "no_matches";
            graphics.drawWordWrap(font, Component.translatable("gui.ore_craft.machine." + key),
                    x + LIST_X + 8, y + LIST_Y + 13, LIST_WIDTH - 16, OreMachineScreenStyle.MUTED);
        }
        ItemStack selected = menu.getSlot(OreConversionMachineMenu.SELECTION_SLOT).getItem();
        for (int row = 0; row < LIST_ROWS && firstRow + row < entries.size(); row++) {
            OreConversionNetwork.PriceEntry entry = entries.get(firstRow + row);
            ItemStack shown = new ItemStack(BuiltInRegistries.ITEM.get(entry.id()));
            int rowY = y + LIST_Y + row * LIST_STEP;
            boolean active = !selected.isEmpty() && ItemStack.isSameItemSameComponents(selected, shown);
            boolean hovered = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_WIDTH
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT,
                    active ? 0xFF24565C : hovered ? 0xFF34595C : row % 2 == 0 ? 0xE519222C : 0xE51C2732);
            OreMachineScreenStyle.outline(graphics, x + LIST_X, rowY, LIST_WIDTH, ROW_HEIGHT,
                    active ? OreMachineScreenStyle.CYAN : 0xFF586777);
            graphics.fill(x + LIST_X + 1, rowY + 1, x + LIST_X + LIST_WIDTH - 1, rowY + 2,
                    active ? 0xFF5FAEB2 : 0xFF34424E);
            if (active) {
                graphics.fill(x + LIST_X, rowY + 1, x + LIST_X + 2, rowY + ROW_HEIGHT - 1, OreMachineScreenStyle.CYAN);
            }
            graphics.renderItem(shown, x + LIST_X + 3, rowY + 1);
            // 价格使用精确整数而非缩写，先为右侧数值保留空间，再省略过长的物品名。
            String price = String.format(Locale.ROOT, "%,d ME", entry.price());
            int priceX = x + LIST_X + LIST_WIDTH - 5 - font.width(price);
            int nameX = x + LIST_X + 23;
            String name = OreMachineScreenStyle.ellipsize(font, shown.getHoverName().getString(), priceX - nameX - 7);
            graphics.drawString(font, name, nameX, rowY + 5, OreMachineScreenStyle.TEXT, false);
            graphics.drawString(font, price, priceX, rowY + 5, active ? OreMachineScreenStyle.CYAN : OreMachineScreenStyle.TEXT, false);
        }
        drawScrollbar(graphics, entries.size());
    }

    /** 绘制与搜索框高度一致的像素放大镜，不创建额外按钮。 */
    private static void drawSearchIcon(GuiGraphics graphics, int x, int y) {
        OreMachineScreenStyle.outline(graphics, x, y, 6, 6, OreMachineScreenStyle.MUTED);
        graphics.fill(x + 5, y + 5, x + 8, y + 7, OreMachineScreenStyle.MUTED);
    }

    /** 根据总条目数与当前首行绘制滚动轨道和滑块，空目录隐藏滑块。 */
    private void drawScrollbar(GuiGraphics graphics, int size) {
        int x = leftPos + SCROLL_X;
        int y = topPos + LIST_Y;
        graphics.fill(x, y, x + SCROLL_WIDTH, y + TRACK_HEIGHT, 0xFF111A22);
        if (size == 0) return;
        int thumb = thumbHeight(size);
        int max = Math.max(0, size - LIST_ROWS);
        int offset = max == 0 ? 0 : (TRACK_HEIGHT - thumb) * firstRow / max;
        graphics.fill(x, y + offset, x + SCROLL_WIDTH, y + offset + thumb,
                draggingScroll ? OreMachineScreenStyle.CYAN : 0xFF667989);
    }

    /** 返回目录滑块高度，至少十二像素，少于一屏时占满轨道。 */
    private static int thumbHeight(int size) {
        return Math.min(TRACK_HEIGHT, Math.max(12, TRACK_HEIGHT * LIST_ROWS / Math.max(LIST_ROWS, size)));
    }

    /** 绘制设备名称、三格角色、生产速率、目录排序方向及当前结果范围。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component tier = Component.translatable(menu.isUpgraded() ? "gui.ore_craft.machine.plus" : "gui.ore_craft.machine.standard");
        OreMachineScreenStyle.header(graphics, font, title,
                Component.translatable("gui.ore_craft.machine.subtitle"), tier, WIDTH);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.machine.container"), 42, 59, OreMachineScreenStyle.CYAN);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.machine.selection"), 110, 59, OreMachineScreenStyle.CYAN);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.machine.output"), 178, 59, OreMachineScreenStyle.CYAN);
        OreMachineScreenStyle.rate(graphics, font, Component.translatable(menu.isUpgraded()
                ? "gui.ore_craft.machine.rate_plus" : "gui.ore_craft.machine.rate"),
                30, 117, 160, menu.progressTicks(), menu.intervalTicks());
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, OreMachineScreenStyle.TEXT, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.learned"), LIST_X, 55, OreMachineScreenStyle.TEXT, false);
        Component order = Component.translatable("gui.ore_craft.machine.price_order");
        graphics.drawString(font, order, LIST_X + LIST_WIDTH - font.width(order), 55, OreMachineScreenStyle.CYAN, false);
        List<OreConversionNetwork.PriceEntry> entries = filtered();
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.results",
                entries.isEmpty() ? 0 : firstRow + 1, Math.min(entries.size(), firstRow + LIST_ROWS), entries.size()),
                LIST_X, 214, OreMachineScreenStyle.MUTED, false);
    }

    /**
     * 根据逻辑鼠标位置查找目录条目，行间空隙和目录之外返回无命中。
     *
     * @param mouseX 逻辑鼠标横坐标
     * @param mouseY 逻辑鼠标纵坐标
     * @return 筛选目录中的条目索引，无命中时为 -1
     */
    private int entryAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos - LIST_Y;
        if (x < LIST_X || x >= LIST_X + LIST_WIDTH || y < 0 || y >= LIST_ROWS * LIST_STEP
                || y % LIST_STEP >= ROW_HEIGHT) return -1;
        List<OreConversionNetwork.PriceEntry> entries = filtered();
        int index = firstRow + (int) (y / LIST_STEP);
        return index < entries.size() ? index : -1;
    }

    /** 目录点击只发送目标物品 ID；滚动条点击进入拖动，其余操作使用原版菜单行为。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double logicalX = menuCoordinate(mouseX);
        double logicalY = menuCoordinate(mouseY);
        if (button == 0 && logicalX >= leftPos + SCROLL_X - 2 && logicalX < leftPos + SCROLL_X + SCROLL_WIDTH + 2
                && logicalY >= topPos + LIST_Y && logicalY < topPos + LIST_Y + TRACK_HEIGHT
                && filtered().size() > LIST_ROWS) {
            draggingScroll = true;
            updateScroll(logicalY);
            return true;
        }
        int index = button == 0 ? entryAt(logicalX, logicalY) : -1;
        if (index >= 0) {
            PacketDistributor.sendToServer(new OreConversionNetwork.ActionPayload(menu.containerId,
                    OreConversionNetwork.SELECT_MACHINE_ITEM, filtered().get(index).id(), 0));
            suppressRelease = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 消费目录与滚动条的配对释放事件，避免误触真实物品槽。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (suppressRelease || draggingScroll)) {
            suppressRelease = false;
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 拖动滚动条时更新首条可见记录，其余拖拽仍可正常分配物品。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScroll) {
            updateScroll(menuCoordinate(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 按滑块中心位置计算目录起点，移动到轨道两端时准确到达首尾。 */
    private void updateScroll(double logicalY) {
        int size = filtered().size();
        int thumb = thumbHeight(size);
        int max = Math.max(0, size - LIST_ROWS);
        double position = (logicalY - topPos - LIST_Y - thumb / 2.0) / Math.max(1, TRACK_HEIGHT - thumb);
        firstRow = Math.clamp((int) Math.round(position * max), 0, max);
    }

    /** 鼠标位于目录面板时按行滚动，其他区域保留原版行为。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (x >= LIST_X && x < SCROLL_X + SCROLL_WIDTH && y >= LIST_Y
                && y < LIST_Y + TRACK_HEIGHT && scrollY != 0) {
            firstRow = Math.clamp(firstRow + (scrollY > 0 ? -1 : 1), 0, Math.max(0, filtered().size() - LIST_ROWS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 按逻辑坐标识别目录行，以原版 GUI 倍率显示完整物品名、单价和已学习标记。 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int index = entryAt(menuCoordinate(mouseX), menuCoordinate(mouseY));
        if (index < 0) return;
        OreConversionNetwork.PriceEntry entry = filtered().get(index);
        Item item = BuiltInRegistries.ITEM.get(entry.id());
        graphics.renderTooltip(font, List.of(item.getDescription(),
                Component.literal(String.format(Locale.ROOT, "%,d ME", entry.price()))
                        .append(Component.translatable("tooltip.ore_craft.conversion.learned")
                                .withStyle(style -> style.withColor(0xA9A6B0)))), Optional.empty(), mouseX, mouseY);
    }
}
