package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreCraftingMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 矿质合成台的双页界面：左侧三阶合成、转化供能口与背包，右侧为可搜索、分类的分页物品方格。
 * 所有取物、填充和开关操作都交由服务端确认；界面仅显示真实槽位和同步账户。
 * 沿用矿质机器的纹理、缩放和命中坐标，窗口较小时仍能完整操作。
 */
public final class OreCraftingScreen extends AbstractOreContainerScreen<OreCraftingMenu> {
    /** 两侧各二百二十逻辑像素，与现有双页机器保持一致。 */
    private static final int WIDTH = 440;
    /** 增加合成网格后需要的固定画布高度。 */
    private static final int HEIGHT = 270;
    /** 目录方格左上角，绘制、点击、提示和 JEI 查询共用此逻辑坐标。 */
    private static final int CATALOG_X = 236;
    private static final int CATALOG_Y = 78;
    /** 利用合成台较大的右页，每页展示六列五行，每格容纳物品图标及下方 ME 缩写。 */
    private static final int COLUMNS = 6;
    private static final int ROWS = 5;
    private static final int PAGE_SIZE = COLUMNS * ROWS;
    /** 方格边长二十八像素，格间留两像素空隙；空隙不参与物品命中。 */
    private static final int CELL_SIZE = 28;
    private static final int CELL_STEP = 30;
    /** 目录固定尺寸不随条目数量变化，末页仍保留完整的三十格背景。 */
    private static final int CATALOG_WIDTH = (COLUMNS - 1) * CELL_STEP + CELL_SIZE;
    private static final int CATALOG_HEIGHT = (ROWS - 1) * CELL_STEP + CELL_SIZE;
    /** 搜索框位于目录标题下方，左侧为放大镜，输入文字与图标预留独立空间。 */
    private static final int SEARCH_Y = 38;
    private static final int SEARCH_HEIGHT = 18;
    /** 六个分类按钮与下方六列方格对齐，按钮和方格之间保留四像素间距。 */
    private static final int CATEGORY_Y = 60;
    private static final int CATEGORY_HEIGHT = 14;
    /** 翻页控件与页码位于方格下方，末行方格与底部矿石边框之间保留间距。 */
    private static final int PAGE_BUTTON_Y = 234;
    /** 支持物品名称、注册 ID 及模组名搜索的输入框。 */
    private EditBox search;
    /** 当前分类与转化桌使用相同规则，窗口重建时保留选择，首次打开默认显示全部。 */
    private OreCatalogCategory category = OreCatalogCategory.ALL;
    /** 最近参与目录缓存的分类，null 表示尚未生成筛选结果。 */
    private OreCatalogCategory seenCategory;
    /** 当前页码，从零开始；搜索词或分类变化后回到第一页，目录缩小时自动收紧范围。 */
    private int page;
    /** 上一页与下一页按钮，首尾页分别禁用相应方向。 */
    private Button previousPage;
    private Button nextPage;
    /** 防止目录点击的释放事件触发原版槽位交互。 */
    private boolean suppressRelease;
    /** 最近用于缓存排序的菜单修订号。 */
    private int seenRevision = -1;
    /** 最近的规范化搜索词，避免每帧重算目录。 */
    private String seenQuery = "";
    /** 当前筛选结果，按价格从高到低展示。 */
    private List<OreConversionNetwork.PriceEntry> entries = List.of();
    /** 最近一次服务端交易反馈及剩余显示刻数。 */
    private Component status = Component.empty();
    private int statusTicks;

    /**
     * 创建两款合成台共用界面，实际供能口数量由菜单决定。
     *
     * @param menu 已打开的合成菜单
     * @param inventory 玩家背包
     * @param title 对应方块版本的本地化名称
     */
    public OreCraftingScreen(OreCraftingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    /** 窗口变化时保留搜索、分类及页码，按公共缩放层计算后的逻辑位置重新创建控件。 */
    @Override
    protected void init() {
        String query = search == null ? "" : search.getValue();
        super.init();
        seenRevision = -1;
        suppressRelease = false;
        search = new EditBox(font, leftPos + CATALOG_X + 20, topPos + SEARCH_Y + 5, CATALOG_WIDTH - 25, 12,
                Component.translatable("gui.ore_craft.conversion.search"));
        search.setMaxLength(64);
        search.setBordered(false);
        search.setTextColor(OreMachineScreenStyle.TEXT);
        search.setHint(Component.translatable("gui.ore_craft.conversion.search"));
        search.setValue(query);
        search.setResponder(value -> { page = 0; updatePageControls(); });
        addRenderableWidget(search);
        // 枚举顺序与转化桌一致，分类仅筛选右侧目录，不限制 JEI 或自动补料可用的物品。
        for (OreCatalogCategory target : OreCatalogCategory.values()) {
            addRenderableWidget(new CategoryButton(target));
        }
        addRenderableWidget(new CraftingButton(leftPos + 29, topPos + 120, false));
        addRenderableWidget(new CraftingButton(leftPos + 53, topPos + 120, true));
        // 翻页按钮相对网格内收八像素，避开两侧底部矿石装饰角。
        previousPage = addRenderableWidget(new PageButton(leftPos + CATALOG_X + 8, -1));
        nextPage = addRenderableWidget(new PageButton(leftPos + CATALOG_X + CATALOG_WIDTH - 26, 1));
        updatePageControls();
    }

    /** 更新搜索框光标及短暂反馈，不在客户端推测交易结果。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        search.tick();
        if (statusTicks > 0) statusTicks--;
        updatePageControls();
    }

    /** 搜索框输入物品栏快捷键时保持输入焦点；Esc 仍然关闭菜单。 */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (search.isFocused() && key != 256) {
            search.keyPressed(key, scanCode, modifiers);
            // 字符由后续 charTyped 事件输入；按键不能穿透成背包关闭或数字键换槽操作。
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    /** 缓存搜索与分类的交集，同价时按 ID 固定排序；无效默认状态不显示为可购买项目。 */
    private List<OreConversionNetwork.PriceEntry> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (seenRevision != menu.revision() || !seenQuery.equals(query) || seenCategory != category) {
            seenRevision = menu.revision();
            seenQuery = query;
            seenCategory = category;
            entries = menu.clientCatalog().stream().filter(entry -> {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(entry.id()));
                return OreConversionPrices.isPlain(stack) && category.matches(stack.getItem()) && (query.isEmpty()
                        || entry.id().toString().contains(query)
                        || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query));
            }).sorted(Comparator.comparingLong(OreConversionNetwork.PriceEntry::price).reversed()
                    .thenComparing(entry -> entry.id().toString())).toList();
        }
        page = Mth.clamp(page, 0, Math.max(0, (entries.size() - 1) / PAGE_SIZE));
        return entries;
    }

    /** 绘制共用矿石边框、真实槽位及分页目录；每个目录方格仅用于服务端提取请求。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        OreMachineScreenStyle.frame(graphics, leftPos, topPos, WIDTH, HEIGHT);
        OreMachineScreenStyle.insetDivider(graphics, leftPos + 220, topPos, HEIGHT);
        for (var slot : menu.slots) {
            boolean accent = slot.index == menu.outputSlot();
            OreMachineScreenStyle.slot(graphics, leftPos + slot.x, topPos + slot.y, accent, OreMachineScreenStyle.CYAN);
        }
        OreMachineScreenStyle.arrow(graphics, leftPos + 94, topPos + 90, 42, OreMachineScreenStyle.CYAN);
        graphics.fill(leftPos + 29, topPos + 138, leftPos + 191, topPos + 139, OreMachineScreenStyle.BORDER);
        // 搜索框沿用转化桌的深色底、细边和像素放大镜；聚焦时用青色边线标记输入区域。
        int searchX = leftPos + CATALOG_X;
        int searchY = topPos + SEARCH_Y;
        graphics.fill(searchX, searchY, searchX + CATALOG_WIDTH, searchY + SEARCH_HEIGHT, 0xE9111921);
        OreMachineScreenStyle.outline(graphics, searchX, searchY, CATALOG_WIDTH, SEARCH_HEIGHT,
                search.isFocused() ? OreMachineScreenStyle.CYAN : OreMachineScreenStyle.BORDER);
        graphics.fill(searchX + 1, searchY + 1, searchX + CATALOG_WIDTH - 1, searchY + 2, 0xFF394A56);
        OreMachineScreenStyle.searchIcon(graphics, searchX + 6, searchY + 4);
        List<OreConversionNetwork.PriceEntry> catalog = filtered();
        for (int cell = 0; cell < PAGE_SIZE; cell++) {
            int index = page * PAGE_SIZE + cell;
            int x = leftPos + CATALOG_X + cell % COLUMNS * CELL_STEP;
            int y = topPos + CATALOG_Y + cell / COLUMNS * CELL_STEP;
            boolean populated = index < catalog.size();
            boolean hovered = populated && mouseX >= x && mouseX < x + CELL_SIZE
                    && mouseY >= y && mouseY < y + CELL_SIZE;
            // 空目录和末页也保留所有格框，翻页、搜索和鼠标悬停均不会改变布局。
            graphics.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, hovered ? 0xFF34595C : 0xE519222C);
            OreMachineScreenStyle.outline(graphics, x, y, CELL_SIZE, CELL_SIZE,
                    hovered ? OreMachineScreenStyle.CYAN : 0xFF586777);
            graphics.fill(x + 1, y + 1, x + CELL_SIZE - 1, y + 2, hovered ? 0xFF7FD8D5 : 0xFF34424E);
            if (!populated) continue;
            var entry = catalog.get(index);
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(entry.id())), x + (CELL_SIZE - 16) / 2, y + 2);
            renderCellPrice(graphics, entry, x, y);
        }
    }

    /**
     * 在物品图标下显示转化桌同款 ME 缩写，过长数值按格内宽度缩小，避免越过相邻方格。
     * 完整整数价格与可兑换数量交给悬停提示显示，实际交易金额不受此显示处理影响。
     */
    private void renderCellPrice(GuiGraphics graphics, OreConversionNetwork.PriceEntry entry, int x, int y) {
        String price = MeNumberFormat.compact(entry.price());
        // 图标占据格内上方十六像素，价格同时受剩余高度和宽度限制，保留一像素上下间隔。
        float scale = Math.min((CELL_SIZE - 20.0F) / font.lineHeight,
                Math.min(1.0F, (CELL_SIZE - 4.0F) / Math.max(1, font.width(price))));
        graphics.pose().pushPose();
        graphics.pose().translate(x + CELL_SIZE / 2.0F, y + 19, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, price, -font.width(price) / 2, 0,
                entry.price() <= menu.clientBalance() ? OreMachineScreenStyle.CYAN : OreMachineScreenStyle.MUTED, false);
        graphics.pose().popPose();
    }

    /** 绘制精简标题、账户余额、供能口角色及反馈；过长文本在提示中显示完整内容。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        text(graphics, title, 29, 24, 166, OreMachineScreenStyle.TEXT);
        text(graphics, balanceText(), 29, 41, 166, OreMachineScreenStyle.CYAN);
        text(graphics, Component.translatable("gui.ore_craft.crafting.grid"), 29, 53, 104, OreMachineScreenStyle.MUTED);
        if (menu.containerBacked()) {
            text(graphics, Component.translatable("gui.ore_craft.crafting.container"), 151, 106, 43, OreMachineScreenStyle.MUTED);
        } else {
            text(graphics, Component.translatable("gui.ore_craft.crafting.deposit"), 127, 106, 35, OreMachineScreenStyle.MUTED);
            text(graphics, Component.translatable("gui.ore_craft.crafting.withdraw"), 163, 106, 35, OreMachineScreenStyle.MUTED);
        }
        text(graphics, playerInventoryTitle, 29, 143, 162, OreMachineScreenStyle.TEXT);
        int total = filtered().size();
        // 空目录提示使用原有标题行，搜索与分类始终保持原位，避免额外文字挤占方格空间。
        text(graphics, total == 0 ? emptyCatalogText() : Component.translatable("gui.ore_craft.machine.learned"),
                CATALOG_X, 24, CATALOG_WIDTH, total == 0 ? OreMachineScreenStyle.MUTED : OreMachineScreenStyle.TEXT);
        graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE),
                CATALOG_X + CATALOG_WIDTH / 2, PAGE_BUTTON_Y + 4, OreMachineScreenStyle.MUTED);
        if (statusTicks > 0) text(graphics, status, 29, 241, 166, OreMachineScreenStyle.TEXT);
    }

    /** 在固定文字区域绘制省略后的本地化内容，避免宽价格或英文标题覆盖其他控件。 */
    private void text(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, text.getString(), width), x, y, color, false);
    }

    /** 返回完整余额文字，普通容器和 Plus 账户均以菜单的实际结算来源为准。 */
    private Component balanceText() {
        return Component.translatable("gui.ore_craft.crafting.balance", String.format(Locale.ROOT, "%,d", menu.clientBalance()));
    }

    /** 区分尚未学习任何物品与搜索或分类无匹配结果，标题行和完整悬停提示共用此内容。 */
    private Component emptyCatalogText() {
        return Component.translatable(menu.clientCatalog().isEmpty()
                ? "gui.ore_craft.machine.empty" : "gui.ore_craft.machine.no_matches");
    }

    /** 返回实际 GUI 坐标边界，供 JEI 摆放侧栏时避开经过缩放的合成台。 */
    public Rect2i guiArea() {
        double scale = 1.0 / menuCoordinate(1.0);
        return new Rect2i((int) Math.round(leftPos * scale), (int) Math.round(topPos * scale),
                (int) Math.ceil(WIDTH * scale), (int) Math.ceil(HEIGHT * scale));
    }

    /** 只读查询目录或真实槽位下的物品，供可选 JEI 整合显示配方和用途。 */
    public ItemStack ingredientAt(double mouseX, double mouseY) {
        int index = entryAt(mouseX, mouseY);
        if (index >= 0) return new ItemStack(BuiltInRegistries.ITEM.get(filtered().get(index).id()));
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        for (var slot : menu.slots) {
            if (x >= slot.x && x < slot.x + 16 && y >= slot.y && y < slot.y + 16) return slot.getItem().copy();
        }
        return ItemStack.EMPTY;
    }

    /** 接收共享转化交易反馈及合成规划失败原因，拒绝其他菜单的旧消息。 */
    public void receiveStatus(OreConversionNetwork.StatusPayload packet) {
        if (packet.containerId() != menu.containerId) return;
        status = Component.translatable(packet.key().startsWith("craft_")
                        ? "message.ore_craft.crafting." + packet.key().substring(6)
                        : "message.ore_craft.conversion." + packet.key(), String.format(Locale.ROOT, "%,d", packet.amount()));
        statusTicks = 120;
    }

    /**
     * 将 GUI 鼠标位置转换成当前页的方格索引，提取、悬停和 JEI 共用同一命中规则。
     * 格间空隙、目录边界外及末页空格返回 -1，不能误取其他物品。
     */
    private int entryAt(double mouseX, double mouseY) {
        List<OreConversionNetwork.PriceEntry> catalog = filtered();
        double x = menuCoordinate(mouseX) - leftPos - CATALOG_X;
        double y = menuCoordinate(mouseY) - topPos - CATALOG_Y;
        if (x < 0 || x >= CATALOG_WIDTH || y < 0 || y >= CATALOG_HEIGHT
                || x % CELL_STEP >= CELL_SIZE || y % CELL_STEP >= CELL_SIZE) return -1;
        int index = page * PAGE_SIZE + (int) y / CELL_STEP * COLUMNS + (int) x / CELL_STEP;
        return index < catalog.size() ? index : -1;
    }

    /** 点击物品方格沿用转化桌的单件取至光标及 Shift 批量取至背包行为。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = button == 0 ? entryAt(mouseX, mouseY) : -1;
        if (index >= 0) {
            OreConversionNetwork.sendToServer(new OreConversionNetwork.ActionPayload(menu.containerId,
                    hasShiftDown() ? OreConversionNetwork.EXTRACT_STACK : OreConversionNetwork.EXTRACT, filtered().get(index).id(), 1));
            suppressRelease = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 消费目录物品点击的配对释放事件，避免误操作真实库存。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && suppressRelease) {
            suppressRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 鼠标位于目录或页码区域时用滚轮翻页，方向与转化桌一致。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (x >= CATALOG_X && x < CATALOG_X + CATALOG_WIDTH && y >= CATALOG_Y
                && y < PAGE_BUTTON_Y + 16 && delta != 0) {
            changePage(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 按方向切换一页，统一限制首尾边界并更新按钮，空目录固定显示第一页。 */
    private void changePage(int direction) {
        int total = filtered().size();
        page = Mth.clamp(page + direction, 0, Math.max(0, (total - 1) / PAGE_SIZE));
        updatePageControls();
    }

    /** 目录同步、搜索或分类改变后更新翻页按钮，禁用没有相邻页面的方向。 */
    private void updatePageControls() {
        int total = filtered().size();
        if (previousPage != null) previousPage.active = page > 0;
        if (nextPage != null) nextPage.active = (page + 1) * PAGE_SIZE < total;
    }

    /**
     * 切换目录分类并回到第一页，同时刷新搜索与分类的交集和翻页按钮。
     *
     * @param target 点击的分类；仅修改客户端视图，不发送交易或材料填充请求
     */
    private void selectCategory(OreCatalogCategory target) {
        category = target;
        page = 0;
        updatePageControls();
    }

    /** 显示物品完整提示、余额及被省略的操作反馈，使用未缩放 GUI 坐标定位。 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int index = entryAt(mouseX, mouseY);
        if (index >= 0) {
            var entry = filtered().get(index);
            // 方格内只显示图标和缩写；完整名称、价格及余额可兑换数量在提示中展开。
            graphics.renderTooltip(font, List.of(
                    BuiltInRegistries.ITEM.get(entry.id()).getDescription(),
                    OreConversionTooltip.meLine(String.format(Locale.ROOT, "%,d", entry.price()), true),
                    Component.translatable("tooltip.ore_craft.conversion.affordable",
                            String.format(Locale.ROOT, "%,d", menu.clientBalance() / entry.price()))),
                    Optional.empty(), mouseX, mouseY);
            return;
        }
        double x = menuCoordinate(mouseX) - leftPos;
        double y = menuCoordinate(mouseY) - topPos;
        if (x >= 29 && x < 195 && y >= 40 && y < 51) graphics.renderTooltip(font, balanceText(), mouseX, mouseY);
        if (statusTicks > 0 && x >= 29 && x < 195 && y >= 240 && y < 252) graphics.renderTooltip(font, status, mouseX, mouseY);
        int total = filtered().size();
        if (total == 0 && x >= CATALOG_X && x < CATALOG_X + CATALOG_WIDTH && y >= 23 && y < 34) {
            graphics.renderTooltip(font, emptyCatalogText(), mouseX, mouseY);
        } else if (x >= CATALOG_X + 30 && x < CATALOG_X + CATALOG_WIDTH - 30
                && y >= PAGE_BUTTON_Y && y < PAGE_BUTTON_Y + 16) {
            // 页码区悬停展示精确条目范围，不占用新增的第五行物品空间。
            graphics.renderTooltip(font, Component.translatable("gui.ore_craft.machine.results",
                    total == 0 ? 0 : page * PAGE_SIZE + 1, Math.min(total, (page + 1) * PAGE_SIZE), total), mouseX, mouseY);
        }
    }

    /** 目录分类按钮，沿用转化桌的选中底色、青色边线和底部标记。 */
    private final class CategoryButton extends Button {
        /** 当前按钮代表的固定分类，渲染时与界面选择比较以决定高亮状态。 */
        private final OreCatalogCategory target;

        /**
         * 创建与一列目录方格对齐的分类按钮，完整本地化标签同时用于悬停提示。
         *
         * @param target 按钮分类，其枚举序号决定从左到右的布局位置
         */
        private CategoryButton(OreCatalogCategory target) {
            super(leftPos + CATALOG_X + target.ordinal() * CELL_STEP, topPos + CATEGORY_Y,
                    CELL_SIZE, CATEGORY_HEIGHT, target.label(), button -> selectCategory(target), DEFAULT_NARRATION);
            this.target = target;
            setTooltip(Tooltip.create(getMessage()));
        }

        /** 绘制当前分类及焦点反馈；过长翻译在固定按钮内省略，悬停可查看完整标签。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = category == target;
            int fill = selected ? 0xFF24565C : 0xFF2A333D;
            if (isHoveredOrFocused()) fill = 0xFF405760;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            OreMachineScreenStyle.outline(graphics, getX(), getY(), getWidth(), getHeight(),
                    selected || isHoveredOrFocused() ? OreMachineScreenStyle.CYAN : 0xFF667989);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + 2,
                    selected ? 0xFF5FAEB2 : 0xFF3D4E5C);
            if (selected) graphics.fill(getX() + 2, getY() + getHeight() - 2,
                    getX() + getWidth() - 2, getY() + getHeight() - 1, OreMachineScreenStyle.CYAN);
            graphics.drawCenteredString(font,
                    OreMachineScreenStyle.ellipsize(font, getMessage().getString(), getWidth() - 4),
                    getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, OreMachineScreenStyle.TEXT);
        }
    }

    /** 与转化桌一致的箭头翻页按钮，沿用机器配色和原版按钮焦点、禁用及提示行为。 */
    private final class PageButton extends Button {
        /**
         * 创建目录底部的一个翻页方向按钮。
         *
         * @param x 按钮在菜单逻辑坐标中的横坐标
         * @param direction -1 表示上一页，1 表示下一页
         */
        private PageButton(int x, int direction) {
            super(x, topPos + PAGE_BUTTON_Y, 18, 16, Component.literal(direction < 0 ? "<" : ">"),
                    button -> changePage(direction), DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.translatable(direction < 0
                    ? "gui.ore_craft.crafting.previous_page" : "gui.ore_craft.crafting.next_page")));
        }

        /** 绘制箭头和禁用状态，所有页面使用固定尺寸以免改变网格布局。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            OreMachineScreenStyle.button(graphics, font, getMessage(), getX(), getY(), getWidth(), getHeight(),
                    active, active && isHoveredOrFocused());
        }
    }

    /** 清空图标及补料复选控件，使用服务端同步的状态绘制勾选框。 */
    private final class CraftingButton extends Button {
        /** true 表示自动补料复选控件，false 表示清空图标。 */
        private final boolean toggle;

        /** 创建合成区下方的紧凑控件，点击后只发送原版菜单按钮请求。 */
        private CraftingButton(int x, int y, boolean toggle) {
            super(x, y, toggle ? 72 : 18, 14,
                    Component.translatable(toggle ? "gui.ore_craft.crafting.refill" : "gui.ore_craft.crafting.clear"),
                    button -> {
                        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(
                                menu.containerId, toggle ? OreCraftingMenu.TOGGLE_REFILL : OreCraftingMenu.CLEAR_GRID);
                    }, DEFAULT_NARRATION);
            this.toggle = toggle;
            setTooltip(Tooltip.create(getMessage()));
        }

        /** 补料控件绘制标准勾选框，清空控件绘制叉号；焦点状态沿用游戏按钮行为。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!toggle) {
                OreMachineScreenStyle.button(graphics, font, Component.literal("x"), getX(), getY(), width, height, true, isHoveredOrFocused());
                return;
            }
            OreMachineScreenStyle.outline(graphics, getX(), getY() + 2, 10, 10,
                    isHoveredOrFocused() ? OreMachineScreenStyle.CYAN : OreMachineScreenStyle.BORDER);
            if (menu.autoRefill()) graphics.drawString(font, "x", getX() + 2, getY() + 3, OreMachineScreenStyle.CYAN, false);
            graphics.drawString(font, OreMachineScreenStyle.ellipsize(font, getMessage().getString(), width - 14),
                    getX() + 14, getY() + 3, OreMachineScreenStyle.TEXT, false);
        }
    }
}
