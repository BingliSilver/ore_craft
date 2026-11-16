package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.item.GeologicalDetectorItem;
import com.lazeroX.ore_craft.menu.GeologicalDetectorMenu;
import com.lazeroX.ore_craft.network.GeologicalDetectorNetwork;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.value.GeologicalOreCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 地质探测器的双栏设置界面：左栏搜索矿石，右栏预览目标并选择范围。
 * 复用转化桌与传送权杖的矿石外框、石质面板和扁平按钮，仅保存时提交服务端配置。
 */
public final class GeologicalDetectorScreen extends AbstractOreContainerScreen<GeologicalDetectorMenu> {
    /** 固定画布及每页六种矿石，使用公共菜单缩放层适配窗口。 */
    private static final int WIDTH = 440;
    private static final int HEIGHT = 352;
    private static final int PAGE_SIZE = 6;
    /** 目录与右侧设置分区的逻辑坐标，四周避开共用主题的矿石装饰角。 */
    private static final int LIST_X = 30;
    private static final int LIST_Y = 90;
    private static final int LIST_WIDTH = 222;
    private static final int ROW_STEP = 28;
    private static final int SETTINGS_X = 272;
    private static final int SETTINGS_WIDTH = 138;
    /** 全部矿石、当前筛选结果及可复用行按钮；目录按注册名稳定排序。 */
    private final List<Block> ores = GeologicalOreCatalog.entries();
    private List<Block> filtered = List.of();
    private final List<Button> rows = new ArrayList<>();
    /** 范围按钮与翻页按钮用于同步可用状态及选中外观。 */
    private final List<Button> ranges = new ArrayList<>();
    private Button previous;
    private Button next;
    private Button save;
    /** 搜索框支持矿石本地化名称和完整注册名，包括模组命名空间。 */
    private EditBox search;
    /** 尚未提交的矿石、范围索引及当前页号；取消界面不写入物品。 */
    private Block selected;
    private int rangeIndex;
    private int page;

    /** @param menu 服务端绑定菜单 @param inventory 玩家背包 @param title 本地化标题 */
    public GeologicalDetectorScreen(GeologicalDetectorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        selected = GeologicalDetectorItem.selectedOre(menu.detector());
        rangeIndex = GeologicalDetectorItem.rangeIndex(menu.detector());
    }

    /** 创建搜索框、矿石行、翻页及四档范围；窗口尺寸变化时保留筛选文字和本地预览。 */
    @Override
    protected void init() {
        String query = search == null ? "" : search.getValue();
        super.init();
        rows.clear();
        ranges.clear();
        search = new EditBox(font, leftPos + LIST_X + 21, topPos + 65, LIST_WIDTH - 27, 12,
                Component.translatable("gui.ore_craft.detector.search"));
        // 边框交给公共石质面板绘制，输入文字与传送权杖的搜索框保持一致。
        search.setBordered(false);
        search.setTextColor(OreMachineScreenStyle.TEXT);
        search.setTextColorUneditable(OreMachineScreenStyle.MUTED);
        search.setMaxLength(128);
        search.setHint(Component.translatable("gui.ore_craft.detector.search"));
        addRenderableWidget(search);
        for (int row = 0; row < PAGE_SIZE; row++) {
            int rowIndex = row;
            rows.add(addRenderableWidget(new DetectorButton(LIST_X, LIST_Y + row * ROW_STEP, LIST_WIDTH, 26,
                    Component.empty(), button -> {
                int index = page * PAGE_SIZE + rowIndex;
                if (index < filtered.size()) selected = filtered.get(index);
                refresh();
            }, () -> page * PAGE_SIZE + rowIndex < filtered.size()
                    && filtered.get(page * PAGE_SIZE + rowIndex) == selected, rowIndex)));
        }
        previous = addRenderableWidget(new DetectorButton(LIST_X, 266, 24, 20, Component.literal("<"), button -> {
            page--;
            refresh();
        }, () -> false, -1));
        next = addRenderableWidget(new DetectorButton(128, 266, 24, 20, Component.literal(">"), button -> {
            page++;
            refresh();
        }, () -> false, -1));
        for (int index = 0; index < GeologicalDetectorItem.RADII.size(); index++) {
            int preset = index;
            ranges.add(addRenderableWidget(new DetectorButton(SETTINGS_X + 10, 148 + index * 26,
                    SETTINGS_WIDTH - 20, 22, Component.translatable("gui.ore_craft.detector.range",
                    GeologicalDetectorItem.RADII.get(index), GeologicalDetectorItem.multiplier(index)), button -> {
                rangeIndex = preset;
                refresh();
            }, () -> rangeIndex == preset, -1)));
        }
        save = addRenderableWidget(new DetectorButton(SETTINGS_X + 10, 266, SETTINGS_WIDTH - 20, 20,
                Component.translatable("gui.ore_craft.detector.save"), button -> {
            OreConversionNetwork.CHANNEL.sendToServer(new GeologicalDetectorNetwork.Configuration(menu.containerId,
                    BuiltInRegistries.BLOCK.getKey(selected), rangeIndex));
            button.active = false;
        }, () -> false, -1));
        search.setResponder(value -> filter(value));
        search.setValue(query);
        filter(query);
    }

    /** @param query 搜索文字；按本地化名称或注册名包含匹配，刷新后从第一页显示 */
    private void filter(String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        filtered = ores.stream().filter(block -> block.getName().getString().toLowerCase(Locale.ROOT).contains(needle)
                || BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        page = 0;
        refresh();
    }

    /** 更新可访问的按钮标题与可用状态；选中态单独绘制，保持键盘仍可聚焦当前选项。 */
    private void refresh() {
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < rows.size(); row++) {
            int index = page * PAGE_SIZE + row;
            Button button = rows.get(row);
            button.visible = index < filtered.size();
            if (!button.visible) continue;
            Block block = filtered.get(index);
            button.setMessage(Component.literal(block.getName().getString()
                    + " [" + BuiltInRegistries.BLOCK.getKey(block) + "]"));
        }
        previous.active = page > 0;
        next.active = page + 1 < pages;
        save.active = ores.contains(selected);
    }

    /** 绘制公共矿石外框、双栏分隔、搜索框及当前目标预览，控件内容由按钮自行绘制。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        OreMachineScreenStyle.frame(graphics, leftPos, topPos, WIDTH, HEIGHT);
        OreMachineScreenStyle.insetDivider(graphics, leftPos + 262, topPos + 40, 264);
        OreMachineScreenStyle.panel(graphics, leftPos + LIST_X, topPos + 58, LIST_WIDTH, 22);
        // 用像素放大镜表示搜索，避免占用本地化输入提示的位置。
        OreMachineScreenStyle.outline(graphics, leftPos + LIST_X + 6, topPos + 64, 6, 6,
                OreMachineScreenStyle.CYAN);
        graphics.fill(leftPos + LIST_X + 11, topPos + 69, leftPos + LIST_X + 14,
                topPos + 72, OreMachineScreenStyle.CYAN);
        OreMachineScreenStyle.panel(graphics, leftPos + SETTINGS_X, topPos + 58, SETTINGS_WIDTH, 236);
        // 预览槽沿用机器操作格的青色嵌边，不创建可放入物品的真实槽位。
        OreMachineScreenStyle.slot(graphics, leftPos + SETTINGS_X + 12, topPos + 88,
                true, OreMachineScreenStyle.CYAN);
        graphics.renderItem(new ItemStack(selected), leftPos + SETTINGS_X + 12, topPos + 88);
        graphics.fill(leftPos + 30, topPos + 297, leftPos + WIDTH - 30, topPos + 298,
                OreMachineScreenStyle.BORDER);
    }

    /** 显示页号、选择摘要及计费公式，明确扫描范围仅包含已加载区块。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        OreMachineScreenStyle.header(graphics, font, title,
                Component.translatable("gui.ore_craft.detector.subtitle"), Component.literal("ME"), WIDTH);
        graphics.drawCenteredString(font, (page + 1) + " / "
                + Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE), 91, 272, OreMachineScreenStyle.TEXT);
        graphics.drawString(font, Component.translatable("gui.ore_craft.detector.target_label"),
                SETTINGS_X + 10, 66, OreMachineScreenStyle.MUTED, false);
        graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, selected.getName().getString(),
                SETTINGS_WIDTH - 48), SETTINGS_X + 39, 91, OreMachineScreenStyle.CYAN, false);
        graphics.drawString(font, OreMachineScreenStyle.ellipsize(font,
                BuiltInRegistries.BLOCK.getKey(selected).getNamespace(), SETTINGS_WIDTH - 20),
                SETTINGS_X + 10, 116, OreMachineScreenStyle.MUTED, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.detector.range_label"),
                SETTINGS_X + 10, 134, OreMachineScreenStyle.TEXT, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.detector.cost", TeleportWaypoint.ME_PER_BLOCK),
                30, 306, OreMachineScreenStyle.CYAN, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.detector.loaded"),
                30, 321, OreMachineScreenStyle.MUTED, false);
        if (filtered.isEmpty()) graphics.drawCenteredString(font,
                Component.translatable("gui.ore_craft.detector.empty"), LIST_X + LIST_WIDTH / 2,
                155, OreMachineScreenStyle.MUTED);
    }

    /** @param graphics 未缩放上下文 @param mouseX GUI 鼠标横坐标 @param mouseY GUI 鼠标纵坐标 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        int row = (int) ((y - LIST_Y) / ROW_STEP);
        int index = page * PAGE_SIZE + row;
        if (x >= LIST_X && x < LIST_X + LIST_WIDTH && y >= LIST_Y && y < LIST_Y + PAGE_SIZE * ROW_STEP
                && index < filtered.size()) {
            Block block = filtered.get(index);
            graphics.renderTooltip(font, List.of(block.getName(),
                    Component.literal(BuiltInRegistries.BLOCK.getKey(block).toString())),
                    java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** 主题按钮统一绘制悬停、焦点及青色选中边线；矿石行额外展示图标和两行名称。 */
    private final class DetectorButton extends Button {
        /** 动态读取本地预览选中态，无需重建控件或借用禁用状态。 */
        private final BooleanSupplier selectedState;
        /** 矿石行在当前页中的索引；普通操作按钮使用 -1。 */
        private final int row;

        /**
         * 创建保留原版焦点与无障碍行为的主题按钮。
         * @param x 面板内横坐标
         * @param y 面板内纵坐标
         * @param width 宽度，单位像素
         * @param height 高度，单位像素
         * @param label 按钮标题，同时用于无障碍朗读
         * @param action 操作回调
         * @param selectedState 动态选中态
         * @param row 当前页矿石行或 -1
         */
        private DetectorButton(int x, int y, int width, int height, Component label, OnPress action,
                               BooleanSupplier selectedState, int row) {
            super(leftPos + x, topPos + y, width, height, label, action, DEFAULT_NARRATION);
            this.selectedState = selectedState;
            this.row = row;
        }

        /** 绘制矿石目录双行内容或普通按钮标题，所有长名称均限制在按钮内。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean chosen = selectedState.getAsBoolean();
            OreMachineScreenStyle.button(graphics, font, row < 0 ? getMessage() : Component.empty(),
                    getX(), getY(), width, height, active, isHoveredOrFocused() || chosen);
            if (chosen) {
                OreMachineScreenStyle.outline(graphics, getX(), getY(), width, height, OreMachineScreenStyle.CYAN);
                graphics.fill(getX() + 1, getY() + 1, getX() + 3, getY() + height - 1, OreMachineScreenStyle.CYAN);
            }
            int index = page * PAGE_SIZE + row;
            if (row < 0 || index >= filtered.size()) return;
            Block block = filtered.get(index);
            graphics.renderItem(new ItemStack(block), getX() + 7, getY() + 5);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, block.getName().getString(), width - 36),
                    getX() + 29, getY() + 4, chosen ? OreMachineScreenStyle.CYAN : OreMachineScreenStyle.TEXT, false);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font,
                    BuiltInRegistries.BLOCK.getKey(block).toString(), width - 36),
                    getX() + 29, getY() + 15, OreMachineScreenStyle.MUTED, false);
        }
    }

    /** 搜索框输入时保留背包键作为文字输入；Esc 仍按原版流程关闭且不保存预览。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search.isFocused() && keyCode != 256) {
            if (search.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
