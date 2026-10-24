package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.MineralTeleportScepterMenu;
import com.lazeroX.ore_craft.network.MineralTeleportScepterNetwork;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.player.TeleportWaypoint;
import com.lazeroX.ore_craft.register.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 矿质传送权杖的命名输入框与本维度分页坐标列表，共用同一个服务端菜单。
 * 外框、石质背景、搜索框与行高亮复用矿质转化器风格，命名窗口使用同套紧凑布局。
 * 使用公共缩放层适配窗口；搜索只筛选本地快照，删除与生成入口始终提交服务端校验。
 */
public final class MineralTeleportScepterScreen extends AbstractOreContainerScreen<MineralTeleportScepterMenu> {
    /** 列表与命名分别使用固定画布，四周为矿石装饰留出至少二十二像素。 */
    private static final int LIST_WIDTH = 440;
    private static final int LIST_HEIGHT = 334;
    private static final int NAMING_WIDTH = 360;
    private static final int NAMING_HEIGHT = 244;
    /** 目录每页六行；两行文字与操作按钮共用固定行高，行间保留两像素间隔。 */
    private static final int PAGE_SIZE = 6;
    private static final int ROW_X = 30;
    private static final int ROW_TOP = 88;
    private static final int ROW_WIDTH = 370;
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_STEP = 28;
    /** 文本区与右侧命令区分开测量，长名称、预计费用和坐标均不能挤占按钮。 */
    private static final int TEXT_X = 56;
    private static final int TEXT_RIGHT = 276;
    private static final int TELEPORT_X = 284;
    private static final int DELETE_X = 352;
    /** 细滚动条沿用转化器的配色，位置表示当前页，可点击或拖动定位。 */
    private static final int SCROLL_X = 405;
    private static final int TRACK_HEIGHT = (PAGE_SIZE - 1) * ROW_STEP + ROW_HEIGHT;
    /** 字体、边框与按钮直接取公共主题，仅错误提示保留独立颜色。 */
    private static final int TEXT = OreMachineScreenStyle.TEXT;
    private static final int MUTED = OreMachineScreenStyle.MUTED;
    private static final int ACCENT = OreMachineScreenStyle.CYAN;
    private static final int ERROR = 0xFFFF9993;
    /** 命名输入框和保存按钮只存在于命名阶段。 */
    private EditBox nameInput;
    private Button saveButton;
    /** 搜索框按本维度的名称及坐标筛选，不改变服务器记录。 */
    private EditBox search;
    /** 固定六组行按钮，刷新结果只改变可见性，保留搜索框的焦点与光标。 */
    private final Button[] teleportButtons = new Button[PAGE_SIZE];
    private final Button[] deleteButtons = new Button[PAGE_SIZE];
    /** 页码导航按钮，在首尾页或等待回包期间禁用。 */
    private Button previousButton;
    private Button nextButton;
    /** 当前搜索结果，按玩家到目标的三维距离升序排列；等距条目保持服务端的创建顺序。 */
    private List<TeleportWaypoint> filtered = List.of();
    /** 从零开始的当前页；删除末页最后一项后自动回到仍存在的最后一页。 */
    private int page;
    /** 最近处理的服务端修订号，收到新快照时刷新内容及按钮状态，保留输入焦点。 */
    private int seenRevision = -1;
    /** 一次请求发出后暂时禁用操作按钮，直到服务器返回快照或关闭菜单。 */
    private boolean waiting;
    /** 是否正在拖动分页滑块；鼠标释放后终止。 */
    private boolean draggingScroll;

    /** @param menu 权杖菜单 @param inventory 玩家背包 @param title 本地化标题 */
    public MineralTeleportScepterScreen(MineralTeleportScepterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.naming() ? NAMING_WIDTH : LIST_WIDTH,
                menu.naming() ? NAMING_HEIGHT : LIST_HEIGHT);
    }

    /**
     * 接收当前菜单快照；旧界面的迟到消息不会修改新菜单。
     * @param state 服务器发给本人客户端的列表和结果
     */
    public static void receive(MineralTeleportScepterNetwork.State state) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu instanceof MineralTeleportScepterMenu menu
                && menu.containerId == state.containerId()) {
            menu.applyState(state.waypoints(), state.naming(), state.status());
        }
    }

    /** 重建当前阶段的控件；保留名称与页码，窗口缩放和服务端反馈不会吞掉尚未保存的输入。 */
    @Override
    protected void init() {
        String previousName = nameInput == null ? "" : nameInput.getValue();
        String previousSearch = search == null ? "" : search.getValue();
        super.init();
        seenRevision = menu.revision();
        draggingScroll = false;
        nameInput = null;
        saveButton = null;
        search = null;
        if (menu.naming()) initNaming(previousName);
        else initList(previousSearch);
    }

    /** @param previousName 重建界面前的输入，首次打开时为空 */
    private void initNaming(String previousName) {
        nameInput = new EditBox(font, leftPos + 37, topPos + 130, NAMING_WIDTH - 74, 12,
                Component.translatable("gui.ore_craft.teleport_scepter.name"));
        nameInput.setBordered(false);
        nameInput.setTextColor(TEXT);
        nameInput.setTextColorUneditable(MUTED);
        nameInput.setMaxLength(TeleportWaypoint.MAX_NAME_LENGTH);
        nameInput.setValue(previousName);
        nameInput.setResponder(value -> updateSaveButton());
        addRenderableWidget(nameInput);
        saveButton = addRenderableWidget(new ScepterButton(146, 174, 88, 20,
                Component.translatable("gui.ore_craft.teleport_scepter.save"), button -> save(), false));
        addRenderableWidget(new ScepterButton(242, 174, 88, 20,
                CommonComponents.GUI_CANCEL, button -> onClose(), false));
        updateSaveButton();
        setInitialFocus(nameInput);
    }

    /** @param previousSearch 窗口重建前的搜索词；控件只创建一次，输入和翻页时刷新内容 */
    private void initList(String previousSearch) {
        search = new EditBox(font, leftPos + ROW_X + 21, topPos + 64, ROW_WIDTH - 26, 12,
                Component.translatable("gui.ore_craft.teleport_scepter.search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setTextColorUneditable(MUTED);
        search.setMaxLength(64);
        search.setHint(Component.translatable("gui.ore_craft.teleport_scepter.search"));
        search.setValue(previousSearch);
        search.setResponder(value -> { page = 0; refreshList(); });
        addRenderableWidget(search);
        for (int row = 0; row < PAGE_SIZE; row++) {
            int visibleRow = row;
            int y = ROW_TOP + row * ROW_STEP + 4;
            teleportButtons[row] = addRenderableWidget(new ScepterButton(TELEPORT_X, y, 62, 18,
                    Component.translatable("gui.ore_craft.teleport_scepter.teleport"),
                    button -> requestRow(visibleRow, MineralTeleportScepterNetwork.Operation.TELEPORT), false));
            deleteButtons[row] = addRenderableWidget(new ScepterButton(DELETE_X, y, 42, 18,
                    Component.translatable("gui.ore_craft.teleport_scepter.delete"),
                    button -> requestRow(visibleRow, MineralTeleportScepterNetwork.Operation.DELETE), true));
        }
        previousButton = addRenderableWidget(new ScepterButton(30, 264, 22, 20,
                Component.literal("<"), button -> changePage(-1), false));
        nextButton = addRenderableWidget(new ScepterButton(126, 264, 22, 20,
                Component.literal(">"), button -> changePage(1), false));
        addRenderableWidget(new ScepterButton(350, 264, 60, 20, CommonComponents.GUI_DONE, button -> onClose(), false));
        refreshList();
    }

    /**
     * 用当前搜索词筛选服务端快照，再按本次刷新时玩家到目标的三维直线距离由近到远排序。
     * 全量结果先排序再分页；等距条目维持原顺序，删除或搜索导致页数减少时限制到最后一页。
     */
    private void refreshList() {
        String query = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        // 固定本轮排序的脚底位置；平方距离与实际距离排序相同，也不会受到 ME 向上取整的影响。
        Vec3 origin = minecraft.player == null ? null : minecraft.player.position();
        filtered = menu.waypoints().stream().filter(entry -> query.isEmpty()
                || (entry.name() + " " + coordinates(entry).getString()).toLowerCase(Locale.ROOT).contains(query))
                // 有序流的稳定排序保留等距条目的顺序；玩家暂不可用时同样保留服务端顺序。
                .sorted(Comparator.comparingDouble(entry -> origin == null ? 0.0 : entry.position().distanceToSqr(origin)))
                .toList();
        page = Mth.clamp(page, 0, pageCount() - 1);
        updateListButtons();
    }

    /** 仅切换行按钮的显示和启用状态，避免重新创建搜索框打断输入、选择或输入法。 */
    private void updateListButtons() {
        for (int row = 0; row < PAGE_SIZE; row++) {
            boolean visible = page * PAGE_SIZE + row < filtered.size();
            teleportButtons[row].visible = deleteButtons[row].visible = visible;
            teleportButtons[row].active = deleteButtons[row].active = visible && !waiting && menu.revision() > 0;
        }
        previousButton.active = page > 0 && !waiting;
        nextButton.active = page + 1 < pageCount() && !waiting;
        search.setEditable(!waiting);
    }

    /** @param row 当前页可见行 @param operation 点击的命令，发包时取该行稳定 UUID 而非索引 */
    private void requestRow(int row, MineralTeleportScepterNetwork.Operation operation) {
        int index = page * PAGE_SIZE + row;
        if (index < filtered.size()) request(operation, filtered.get(index).id(), "");
    }

    /** 与矿质转化器同款的扁平按钮，保留原版键盘、焦点与无障碍行为。 */
    private final class ScepterButton extends Button {
        /** 删除按钮聚焦时额外使用错误色边框，区分普通操作。 */
        private final boolean destructive;

        /** @param x 面板内 X @param y 面板内 Y @param width 宽度 @param height 高度
         * @param label 本地化命令 @param action 点击回调 @param destructive 是否为删除命令 */
        private ScepterButton(int x, int y, int width, int height, Component label, OnPress action, boolean destructive) {
            super(leftPos + x, topPos + y, width, height, label, action, DEFAULT_NARRATION);
            this.destructive = destructive;
        }

        /** 沿用公共按钮背景及悬停色，删除按钮仅在可操作且悬停或聚焦时强调红色边线。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            OreMachineScreenStyle.button(graphics, font, getMessage(), getX(), getY(), getWidth(), getHeight(),
                    active, active && isHoveredOrFocused());
            if (destructive && active && isHoveredOrFocused()) {
                OreMachineScreenStyle.outline(graphics, getX(), getY(), getWidth(), getHeight(), ERROR);
            }
        }
    }

    /** 每刻处理输入框光标及服务器回包；失败时也解除等待，让玩家修改名称或选择其他坐标。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (nameInput != null) nameInput.tick();
        if (search != null) search.tick();
        if (seenRevision != menu.revision()) {
            seenRevision = menu.revision();
            waiting = false;
            if (menu.naming()) updateSaveButton();
            else refreshList();
        }
    }

    /** 名称必须非空且符合服务端字符规则，初始列表尚未同步时不能提交操作。 */
    private void updateSaveButton() {
        if (saveButton != null) saveButton.active = !waiting && menu.revision() > 0
                && TeleportWaypoint.isValidName(nameInput.getValue().strip());
        if (nameInput != null) nameInput.setEditable(!waiting);
    }

    /** 保存输入框中的名称；目的位置由服务端菜单的初始快照提供。 */
    private void save() {
        if (saveButton != null && saveButton.active) {
            request(MineralTeleportScepterNetwork.Operation.SAVE, null, nameInput.getValue());
        }
    }

    /**
     * 提交一次操作并等待权威结果；坐标列表不在客户端提前修改。
     * @param operation 请求类型
     * @param waypointId 删除或传送的 UUID；保存时为 null
     * @param name 保存时的输入名称
     */
    private void request(MineralTeleportScepterNetwork.Operation operation, UUID waypointId, String name) {
        if (waiting || menu.revision() == 0) return;
        waiting = true;
        OreConversionNetwork.sendToServer(new MineralTeleportScepterNetwork.Request(menu.containerId, operation, waypointId, name));
        if (menu.naming()) updateSaveButton();
        else updateListButtons();
    }

    /** @return 空列表也保留一页，保证页码和翻页边界始终有效 */
    private int pageCount() { return Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE); }

    /** @param delta 翻页方向，越界时保持当前页 */
    private void changePage(int delta) {
        page = Mth.clamp(page + delta, 0, pageCount() - 1);
        updateListButtons();
    }

    /** @param graphics 绘制上下文 @param partialTick 插值时间 @param mouseX 逻辑鼠标 X @param mouseY 逻辑鼠标 Y */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        OreMachineScreenStyle.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (menu.naming()) {
            OreMachineScreenStyle.panel(graphics, leftPos + 22, topPos + 50, NAMING_WIDTH - 44, 114);
            drawInputFrame(graphics, 30, 123, NAMING_WIDTH - 60, 22, nameInput.isFocused());
        } else {
            OreMachineScreenStyle.panel(graphics, leftPos + 22, topPos + 50, LIST_WIDTH - 44, 210);
            drawInputFrame(graphics, ROW_X, 58, SCROLL_X + 4 - ROW_X, 22, search.isFocused());
            OreMachineScreenStyle.searchIcon(graphics, leftPos + ROW_X + 6, topPos + 64);
            renderRows(graphics, mouseX, mouseY);
            drawScrollbar(graphics);
        }
    }

    /** @param graphics 绘制上下文 @param x 本地 X @param y 本地 Y @param width 宽度
     * @param height 高度 @param focused 输入框是否聚焦，聚焦时使用青色细边 */
    private void drawInputFrame(GuiGraphics graphics, int x, int y, int width, int height, boolean focused) {
        graphics.fill(leftPos + x, topPos + y, leftPos + x + width, topPos + y + height, 0xE9111921);
        OreMachineScreenStyle.outline(graphics, leftPos + x, topPos + y, width, height,
                focused ? ACCENT : OreMachineScreenStyle.BORDER);
        graphics.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + width - 1, topPos + y + 2, 0xFF394A56);
    }

    /**
     * 绘制与转化器目录一致的交替底色、细框和青色高亮；行内按钮与文字使用同一基准线。
     * @param graphics 绘制上下文
     * @param mouseX 逻辑鼠标 X
     * @param mouseY 逻辑鼠标 Y
     */
    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int row = 0; row < PAGE_SIZE && page * PAGE_SIZE + row < filtered.size(); row++) {
            TeleportWaypoint entry = filtered.get(page * PAGE_SIZE + row);
            int x = leftPos + ROW_X;
            int y = topPos + ROW_TOP + row * ROW_STEP;
            boolean highlighted = mouseX >= x && mouseX < x + ROW_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT
                    || teleportButtons[row].isFocused() || deleteButtons[row].isFocused();
            graphics.fill(x, y, x + ROW_WIDTH, y + ROW_HEIGHT,
                    highlighted ? 0xFF24565C : row % 2 == 0 ? 0xE519222C : 0xE51C2732);
            OreMachineScreenStyle.outline(graphics, x, y, ROW_WIDTH, ROW_HEIGHT, highlighted ? ACCENT : 0xFF586777);
            graphics.fill(x + 1, y + 1, x + ROW_WIDTH - 1, y + 2, highlighted ? 0xFF5FAEB2 : 0xFF34424E);
            if (highlighted) graphics.fill(x, y + 1, x + 2, y + ROW_HEIGHT - 1, ACCENT);
            graphics.renderItem(dimensionIcon(entry), x + 5, y + 5);

            // 本维度列表使用右侧位置展示预计费用；实际结算按进入传送门时的位置重新计算。
            long cost = estimatedCost(entry);
            String price = OreMachineScreenStyle.ellipsize(font, Component.translatable(
                    "gui.ore_craft.teleport_scepter.estimated_cost", MeNumberFormat.compact(cost)).getString(), 116);
            int priceX = leftPos + TEXT_RIGHT - font.width(price);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, entry.name(),
                    priceX - leftPos - TEXT_X - 8), leftPos + TEXT_X, y + 4, TEXT, false);
            graphics.drawString(font, price, priceX, y + 4, cost > OreConversionClient.balance() ? ERROR : ACCENT, false);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, coordinates(entry).getString(), TEXT_RIGHT - TEXT_X),
                    leftPos + TEXT_X, y + 15, MUTED, false);
        }
    }

    /**
     * 使用客户端当前位置预估本维度传送费用；客户端余额和位置都不参与服务端实际扣款。
     * @param waypoint 服务端下发的本维度目标
     * @return 距离向上取整后按每格 10 ME 计算的预计费用；玩家暂不可用时显示最低费用
     */
    private long estimatedCost(TeleportWaypoint waypoint) {
        return minecraft.player == null ? TeleportWaypoint.ME_PER_BLOCK : waypoint.energyCost(minecraft.player.position());
    }

    /** @param graphics 绘制上下文，空列表只绘制轨道，多页时滑块可以拖动定位 */
    private void drawScrollbar(GuiGraphics graphics) {
        int x = leftPos + SCROLL_X;
        int y = topPos + ROW_TOP;
        graphics.fill(x, y, x + 4, y + TRACK_HEIGHT, 0xFF111A22);
        if (filtered.isEmpty()) return;
        int thumb = thumbHeight();
        int offset = pageCount() == 1 ? 0 : (TRACK_HEIGHT - thumb) * page / (pageCount() - 1);
        graphics.fill(x, y + offset, x + 4, y + offset + thumb, draggingScroll ? ACCENT : 0xFF667989);
    }

    /** @return 按总页数计算的滑块高度，最小十二像素，单页占满轨道 */
    private int thumbHeight() { return Math.max(12, TRACK_HEIGHT / pageCount()); }

    /** @param graphics 已平移到面板左上角的绘制上下文 @param mouseX 逻辑鼠标 X @param mouseY 逻辑鼠标 Y */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.renderItem(ModItems.MINERAL_TELEPORT_SCEPTER.get().getDefaultInstance(), 29, 23);
        Component count = Component.translatable("gui.ore_craft.teleport_scepter.count",
                menu.waypoints().size());
        int titleRight = menu.naming() ? imageWidth - 30 : imageWidth - 42 - font.width(count);
        graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, title.getString(), titleRight - 52), 52, 27, TEXT, false);
        if (menu.naming()) {
            renderNamingLabels(graphics);
        } else {
            graphics.drawString(font, count, imageWidth - 30 - font.width(count), 27, MUTED, false);
            renderListLabels(graphics);
        }
        if (!menu.status().isEmpty()) {
            int color = menu.status().equals("deleted") ? ACCENT : ERROR;
            // 结果最多占两行，英文错误消息不会盖住列表或按钮。
            graphics.drawWordWrap(font, Component.translatable("message.ore_craft.teleport_scepter." + menu.status()),
                    30, menu.naming() ? 204 : 294, imageWidth - 60, color);
        } else if (!menu.naming()) {
            // 底部空闲状态显示全局余额；错误提示仍优先占据此处，不覆盖列表或按钮。
            graphics.drawString(font, Component.translatable("gui.ore_craft.teleport_scepter.balance",
                    MeNumberFormat.compact(OreConversionClient.balance())), 30, 298, MUTED, false);
        }
    }

    /** @param graphics 面板本地坐标绘制上下文，显示捕获位置、输入标签及字符计数 */
    private void renderNamingLabels(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("gui.ore_craft.teleport_scepter.mark"), 30, 62, ACCENT, false);
        TeleportWaypoint pending = menu.pending();
        if (pending != null) {
            String dimension = OreMachineScreenStyle.ellipsize(font, dimensionName(pending).getString(), 140);
            graphics.drawString(font, dimension, NAMING_WIDTH - 30 - font.width(dimension), 62, MUTED, false);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, coordinates(pending).getString(), NAMING_WIDTH - 60),
                    30, 84, TEXT, false);
        }
        graphics.drawString(font, Component.translatable("gui.ore_craft.teleport_scepter.name"), 30, 109, TEXT, false);
        if (nameInput != null) {
            String count = nameInput.getValue().length() + " / " + TeleportWaypoint.MAX_NAME_LENGTH;
            graphics.drawString(font, count, NAMING_WIDTH - 30 - font.width(count), 109, MUTED, false);
        }
    }

    /** @param graphics 面板本地坐标绘制上下文，空列表文案仅针对当前维度或当前搜索条件 */
    private void renderListLabels(GuiGraphics graphics) {
        if (filtered.isEmpty()) {
            String key = menu.revision() == 0 ? "loading" : menu.waypoints().isEmpty() ? "empty" : "no_matches";
            graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.teleport_scepter." + key),
                    LIST_WIDTH / 2, 164, MUTED);
        }
        graphics.drawCenteredString(font, Component.literal((page + 1) + " / " + pageCount()), 89, 270, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.machine.results",
                filtered.isEmpty() ? 0 : page * PAGE_SIZE + 1,
                Math.min(filtered.size(), (page + 1) * PAGE_SIZE), filtered.size()), 248, 270, MUTED);
    }

    /** @param waypoint 标记 @return 原版维度本地化名称，自定义维度回退到完整注册 ID */
    private static Component dimensionName(TeleportWaypoint waypoint) {
        return switch (waypoint.dimension().toString()) {
            case "minecraft:overworld" -> Component.translatable("gui.ore_craft.teleport_scepter.overworld");
            case "minecraft:the_nether" -> Component.translatable("gui.ore_craft.teleport_scepter.nether");
            case "minecraft:the_end" -> Component.translatable("gui.ore_craft.teleport_scepter.end");
            default -> Component.literal(waypoint.dimension().toString());
        };
    }

    /** @param waypoint 标记 @return 维度代表方块的原版物品图标，自定义维度使用末影珍珠 */
    private static ItemStack dimensionIcon(TeleportWaypoint waypoint) {
        return new ItemStack(switch (waypoint.dimension().toString()) {
            case "minecraft:overworld" -> Items.GRASS_BLOCK;
            case "minecraft:the_nether" -> Items.NETHERRACK;
            case "minecraft:the_end" -> Items.END_STONE;
            default -> Items.ENDER_PEARL;
        });
    }

    /** @param waypoint 位置快照 @return 使用方块坐标展示 X/Y/Z，真实传送仍使用精确小数 */
    private static Component coordinates(TeleportWaypoint waypoint) {
        return Component.literal("X " + Mth.floor(waypoint.position().x) + "   Y " + Mth.floor(waypoint.position().y)
                + "   Z " + Mth.floor(waypoint.position().z));
    }

    /** 仅命名界面保留位置提示；传送列表直接展示坐标与费用，不绘制悬停提示框。 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.naming() || menu.pending() == null) return;
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (x >= 30 && x < NAMING_WIDTH - 30 && y >= 58 && y < 98) {
            graphics.renderComponentTooltip(font, List.of(dimensionName(menu.pending()), coordinates(menu.pending()),
                    Component.literal(menu.pending().dimension().toString())), mouseX, mouseY);
        }
    }

    /** 点击细滚动条进入拖动定位，其他控件继续由公共缩放层处理命中。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (button == 0 && !menu.naming() && !waiting && pageCount() > 1
                && x >= SCROLL_X - 2 && x < SCROLL_X + 6 && y >= ROW_TOP && y < ROW_TOP + TRACK_HEIGHT) {
            draggingScroll = true;
            updateScroll(y);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滑块拖动期间只更新页码，不触发下方行按钮或改变坐标存档。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScroll) {
            updateScroll(menuCoordinate(mouseY) - topPos);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 鼠标释放时结束滑块拖动，避免再次移动鼠标时继续翻页。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** @param y 面板内鼠标 Y，滑块中心映射到合法页码，轨道首尾可准确到达第一页和末页 */
    private void updateScroll(double y) {
        double position = (y - ROW_TOP - thumbHeight() / 2.0) / Math.max(1, TRACK_HEIGHT - thumbHeight());
        page = Mth.clamp((int) Math.round(position * (pageCount() - 1)), 0, pageCount() - 1);
        updateListButtons();
    }

    /** 列表区域内的滚轮按页导航，其余位置沿用公共坐标换算及原版行为。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (!menu.naming() && !waiting && delta != 0 && x >= ROW_X && x < SCROLL_X + 4
                && y >= ROW_TOP && y < ROW_TOP + TRACK_HEIGHT) {
            changePage(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 输入名称或搜索时拦截物品栏快捷键，保证普通字符不会关闭窗口；名称框 Enter 保存。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        EditBox input = menu.naming() ? nameInput : search;
        if (input != null && input.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB) {
            if (menu.naming() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) save();
            else input.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** @param character 输入字符 @param modifiers 修饰键 @return 当前聚焦的名称或搜索框优先接收文字 */
    @Override
    public boolean charTyped(char character, int modifiers) {
        EditBox input = menu.naming() ? nameInput : search;
        return input != null && input.isFocused()
                ? input.charTyped(character, modifiers) : super.charTyped(character, modifiers);
    }
}
