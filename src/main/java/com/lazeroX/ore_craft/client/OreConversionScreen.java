package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.menu.OreConversionMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** 展示转化桌余额、玩家背包和可提取的已学习物品目录。 */
public final class OreConversionScreen extends AbstractContainerScreen<OreConversionMenu> {
    private static final int WIDTH = 432;
    private static final int HEIGHT = 228;
    private static final float X_LAYOUT_SCALE = WIDTH / 390.0F;
    private static final float Y_LAYOUT_SCALE = HEIGHT / 206.0F;
    private static final int TEXTURE_WIDTH = 1728;
    private static final int TEXTURE_HEIGHT = 912;
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            "ore_craft", "textures/gui/ore_conversion_table.png");
    private static final int CATALOG_X = sx(206);
    private static final int CATALOG_Y = sy(68);
    private static final int CATALOG_STEP_X = sx(29);
    private static final int CATALOG_STEP_Y = sy(24);
    private static final int CATALOG_CELL_WIDTH = sx(27);
    private static final int CATALOG_CELL_HEIGHT = sy(23);
    private static final int COLUMNS = 5;
    private static final int PAGE_SIZE = 20;
    private static final int TEXT = 0xFFE9F1F3;
    private static final int MUTED = 0xFFAFBBC5;
    private static final int CYAN = 0xFF85F1EF;

    private EditBox search;
    private Button previous;
    private Button next;
    private final List<FlatButton> categoryButtons = new ArrayList<>();
    private List<OreConversionNetwork.PriceEntry> filtered = List.of();
    private Category category = Category.ALL;
    private int page;
    private int seenRevision = -1;
    private boolean suppressCatalogRelease;
    private Component statusMessage;
    private boolean statusSuccess;
    private int statusTicks;

    /**
     * 创建转化桌界面并设置设计稿对应的画布尺寸。
     *
     * @param menu 当前转化桌菜单
     * @param inventory 玩家物品栏
     * @param title 菜单标题
     */
    public OreConversionScreen(OreConversionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    /** 初始化搜索框、分类按钮和目录分页控件。 */
    @Override
    protected void init() {
        String previousSearch = search == null ? "" : search.getValue();
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        super.init();
        categoryButtons.clear();
        previous = null;
        next = null;
        suppressCatalogRelease = false;
        search = new EditBox(font, leftPos + sx(231), topPos + sy(32), sx(114), sy(11),
                Component.translatable("gui.ore_craft.conversion.search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setMaxLength(64);
        search.setHint(Component.translatable("gui.ore_craft.conversion.search"));
        search.setValue(previousSearch);
        // 搜索条件改变后回到第一页，避免当前页码超过新的结果范围。
        search.setResponder(value -> { page = 0; refresh(); });
        addRenderableWidget(search);

        Category[] categories = Category.values();
        for (int index = 0; index < categories.length; index++) {
            Category target = categories[index];
            FlatButton button = (FlatButton) addButton(Component.translatable(target.translation),
                    sx(205 + index * 25), sy(49), sx(24), sy(14),
                    () -> { category = target; page = 0; refresh(); });
            categoryButtons.add(button);
        }
        previous = addButton(Component.literal("<"), sx(221), sy(164), sx(18), sy(13),
                () -> { page--; refresh(); });
        next = addButton(Component.literal(">"), sx(317), sy(164), sx(18), sy(13),
                () -> { page++; refresh(); });
        refresh();
    }

    /** 按照界面缩放后的坐标创建统一样式按钮。 */
    private Button addButton(Component label, int x, int y, int width, int height, Runnable action) {
        return addRenderableWidget(Button.builder(label, button -> action.run())
                .bounds(leftPos + x, topPos + y, width, height)
                .build(FlatButton::new));
    }

    /** 将目录操作封装成请求并发送到服务端验证。 */
    private void action(int kind, ResourceLocation id, int count) {
        PacketDistributor.sendToServer(new OreConversionNetwork.ActionPayload(menu.containerId, kind, id, count));
    }

    /** 递减提示显示时间，并在菜单状态变化时刷新目录。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (statusTicks > 0) statusTicks--;
        if (seenRevision != menu.revision()) refresh();
        else updateControls();
    }

    /** 根据服务端提示载荷更新界面中的短暂操作结果。 */
    public void receiveStatus(OreConversionNetwork.StatusPayload packet) {
        if (packet.containerId() != menu.containerId) return;
        String key = packet.key();
        statusSuccess = switch (key) {
            case "learned", "already_learned", "converted_learned", "converted_known" -> true;
            default -> false;
        };
        statusMessage = switch (key) {
            case "special_state", "unpriced", "overflow", "learn_limit", "not_learned",
                    "insufficient_me", "inventory_full", "learned", "already_learned" ->
                    Component.translatable("message.ore_craft.conversion." + key);
            case "converted_learned", "converted_known" ->
                    Component.translatable("message.ore_craft.conversion." + key, formatNumber(packet.amount()));
            default -> null;
        };
        statusTicks = statusMessage == null ? 0 : 120;
    }

    /** 按搜索词、分类和菜单目录重建当前显示列表。 */
    private void refresh() {
        if (search == null || previous == null) return;
        seenRevision = menu.revision();
        String query = search.getValue().toLowerCase(Locale.ROOT).trim();
        filtered = menu.clientCatalog().stream().filter(entry -> {
            Item item = BuiltInRegistries.ITEM.get(entry.id());
            return category.matches(item) && (query.isEmpty()
                    || entry.id().toString().contains(query)
                    || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query));
        }).toList();
        // 目录更新或筛选结果变少时，将页码限制在有效范围内。
        page = Math.max(0, Math.min(page, Math.max(0, (filtered.size() - 1) / PAGE_SIZE)));
        for (int index = 0; index < categoryButtons.size(); index++) {
            categoryButtons.get(index).selected = Category.values()[index] == category;
        }
        updateControls();
    }

    /** 根据页码和结果数量更新上一页、下一页按钮状态。 */
    private void updateControls() {
        if (previous == null || next == null) return;
        previous.active = page > 0;
        next.active = (page + 1) * PAGE_SIZE < filtered.size();
    }

    /** 绘制界面背景、背包格子、目录格子和鼠标悬停反馈。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(BACKGROUND, x, y, WIDTH, HEIGHT, 0, 0,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        graphics.fill(x + sx(210), y + sy(29), x + sx(350), y + sy(45), 0xE9111921);
        outline(graphics, x + sx(210), y + sy(29), sx(140), sy(16), 0xFF647989);
        graphics.fill(x + sx(211), y + sy(30), x + sx(349), y + sy(31), 0xFF394A56);
        searchIcon(graphics, x + sx(216), y + sy(33));

        graphics.fill(x + sx(204), y + sy(66), x + sx(352), y + sy(164), 0x5A0A1016);
        outline(graphics, x + sx(204), y + sy(66), sx(148), sy(98), 0xFF586674);
        graphics.fill(x + sx(35), y + sy(101), x + sx(190), y + sy(154), 0x5A0A1016);
        outline(graphics, x + sx(35), y + sy(101), sx(155), sy(53), 0xFF586674);
        graphics.fill(x + sx(35), y + sy(154), x + sx(190), y + sy(155), 0xFF53616E);
        if (statusTicks > 0) {
            graphics.fill(x + sx(45), y + sy(78), x + sx(190), y + sy(92),
                    statusSuccess ? 0xDB183438 : 0xDB3D2927);
            outline(graphics, x + sx(45), y + sy(78), sx(145), sy(14),
                    statusSuccess ? 0xFF70D6D1 : 0xFFE99C80);
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slotBackground(graphics, x + sx(36 + col * 17), y + sy(102 + row * 17));
            }
        }
        for (int col = 0; col < 9; col++) slotBackground(graphics, x + sx(36 + col * 17), y + sy(156));

        int localX = mouseX - x;
        int localY = mouseY - y;
        for (int index = 0; index < PAGE_SIZE; index++) {
            int cellX = CATALOG_X + index % COLUMNS * CATALOG_STEP_X;
            int cellY = CATALOG_Y + index / COLUMNS * CATALOG_STEP_Y;
            boolean hovered = page * PAGE_SIZE + index < filtered.size()
                    && localX >= cellX && localX < cellX + CATALOG_CELL_WIDTH
                    && localY >= cellY && localY < cellY + CATALOG_CELL_HEIGHT;
            graphics.fill(x + cellX, y + cellY,
                    x + cellX + CATALOG_CELL_WIDTH, y + cellY + CATALOG_CELL_HEIGHT,
                    hovered ? 0xFF34595C : 0xE519222C);
            outline(graphics, x + cellX, y + cellY, CATALOG_CELL_WIDTH, CATALOG_CELL_HEIGHT,
                    hovered ? CYAN : 0xFF586777);
            graphics.fill(x + cellX + 1, y + cellY + 1, x + cellX + CATALOG_CELL_WIDTH - 1,
                    y + cellY + 2, hovered ? 0xFF7FD8D5 : 0xFF34424E);
        }
    }

    /** 绘制搜索框左侧的像素风放大镜图标。 */
    private static void searchIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 2, y, x + 6, y + 1, MUTED);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, MUTED);
        graphics.fill(x + 6, y + 1, x + 7, y + 2, MUTED);
        graphics.fill(x, y + 2, x + 1, y + 6, MUTED);
        graphics.fill(x + 7, y + 2, x + 8, y + 6, MUTED);
        graphics.fill(x + 1, y + 6, x + 2, y + 7, MUTED);
        graphics.fill(x + 6, y + 6, x + 7, y + 7, MUTED);
        graphics.fill(x + 2, y + 7, x + 6, y + 8, MUTED);
        graphics.fill(x + 7, y + 8, x + 9, y + 10, MUTED);
    }

    /** 绘制单个背包槽位的背景和边框。 */
    private static void slotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + sx(17), y + sy(17), 0xEB111A22);
        outline(graphics, x, y, sx(17), sy(17), 0xFF627383);
        graphics.fill(x + sx(1), y + sy(1), x + sx(16), y + sy(2), 0xFF2B3946);
    }

    /** 用四条矩形边绘制指定颜色的矩形边框。 */
    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /** 将设计稿横向坐标换算为当前界面像素坐标。 */
    private static int sx(int value) { return Math.round(value * X_LAYOUT_SCALE); }

    /** 将设计稿纵向坐标换算为当前界面像素坐标。 */
    private static int sy(int value) { return Math.round(value * Y_LAYOUT_SCALE); }

    /** 绘制标题、余额、状态提示和目录图标及价格。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, sx(47), sy(30), TEXT, true);
        graphics.drawString(font, Component.translatable("gui.ore_craft.conversion.subtitle_short"), sx(48), sy(46), MUTED, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.conversion.balance"), sx(46), sy(59), MUTED, false);
        graphics.drawString(font, shortNumber(menu.clientBalance()) + " ME", sx(46), sy(69), CYAN, true);
        if (statusTicks > 0 && statusMessage != null) {
            String message = statusMessage.getString();
            String visible = font.plainSubstrByWidth(message, sx(140));
            if (visible.length() < message.length()) {
                visible = font.plainSubstrByWidth(message, sx(140) - font.width("...")) + "...";
            }
            graphics.drawString(font, visible, sx(46), sy(81), statusSuccess ? CYAN : 0xFFFFB09B, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.ore_craft.conversion.inventory_hint"), sx(46), sy(81), MUTED, false);
        }
        graphics.drawString(font, Component.translatable("gui.ore_craft.conversion.inventory"), sx(42), sy(92), TEXT, false);
        for (int index = 0; index < PAGE_SIZE; index++) {
            int catalogIndex = page * PAGE_SIZE + index;
            if (catalogIndex >= filtered.size()) break;
            OreConversionNetwork.PriceEntry entry = filtered.get(catalogIndex);
            int cellX = CATALOG_X + index % COLUMNS * CATALOG_STEP_X;
            int cellY = CATALOG_Y + index / COLUMNS * CATALOG_STEP_Y;
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(entry.id())), cellX + sx(5), cellY + sy(1));
            graphics.drawCenteredString(font, shortNumber(entry.price()), cellX + sx(13), cellY + sy(15), TEXT);
        }
        if (filtered.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.ore_craft.conversion.empty"), sx(278), sy(113), MUTED);
        }
        graphics.drawCenteredString(font,
                (page + 1) + " / " + Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE),
                sx(278), sy(167), MUTED);
    }

    /** 处理目录物品点击，并将背包槽位点击交由原版容器逻辑处理。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            int index = catalogIndexAt(localX, localY);
            if (index >= 0 && index < filtered.size()) {
                OreConversionNetwork.PriceEntry entry = filtered.get(index);
                if (hasShiftDown()) {
                    // Shift 操作请求将尽可能多的该物品直接放入背包。
                    action(OreConversionNetwork.EXTRACT_STACK, entry.id(), 0);
                    suppressCatalogRelease = true;
                    return true;
                }
                Item item = BuiltInRegistries.ITEM.get(entry.id());
                ItemStack carried = menu.getCarried();
                if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, new ItemStack(item))) {
                    suppressCatalogRelease = true;
                    return true;
                }
                // 普通左键只请求一个物品，避免点击目录时一次购买整组。
                action(OreConversionNetwork.EXTRACT, entry.id(), 1);
                suppressCatalogRelease = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 在目录按钮消费释放事件时阻止容器把同一次点击继续处理。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && suppressCatalogRelease) {
            suppressCatalogRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 将界面局部坐标转换为当前页的目录索引；未命中格子时返回负数。 */
    private int catalogIndexAt(int localX, int localY) {
        if (localX < CATALOG_X || localX >= CATALOG_X + COLUMNS * CATALOG_STEP_X
                || localY < CATALOG_Y || localY >= CATALOG_Y + 4 * CATALOG_STEP_Y) return -1;
        int column = (localX - CATALOG_X) / CATALOG_STEP_X;
        int row = (localY - CATALOG_Y) / CATALOG_STEP_Y;
        if ((localX - CATALOG_X) % CATALOG_STEP_X >= CATALOG_CELL_WIDTH
                || (localY - CATALOG_Y) % CATALOG_STEP_Y >= CATALOG_CELL_HEIGHT) return -1;
        return page * PAGE_SIZE + row * COLUMNS + column;
    }

    /** 在鼠标位于目录区域时用滚轮切换页面。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int localX = (int) mouseX - leftPos;
        int localY = (int) mouseY - topPos;
        if (localX >= sx(204) && localX < sx(352) && localY >= sy(66) && localY < sy(177) && scrollY != 0) {
            int lastPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
            page = Math.max(0, Math.min(lastPage, page + (scrollY > 0 ? -1 : 1)));
            updateControls();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 渲染界面和物品提示，并显示余额、目录物品或状态详情。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        int index = catalogIndexAt(localX, localY);
        if (index >= 0 && index < filtered.size()) {
            OreConversionNetwork.PriceEntry entry = filtered.get(index);
            Item item = BuiltInRegistries.ITEM.get(entry.id());
            List<Component> lines = new ArrayList<>();
            lines.add(item.getDescription());
            lines.add(Component.translatable("tooltip.ore_craft.conversion.me", formatNumber(entry.price()))
                    .withStyle(style -> style.withColor(0x64E7F1)));
            long affordable = menu.clientBalance() / entry.price();
            lines.add(Component.translatable("tooltip.ore_craft.conversion.affordable", formatNumber(affordable))
                    .withStyle(style -> style.withColor(CYAN)));
            if (OreConversionClient.canConvert(new ItemStack(item))) {
                lines.add(Component.translatable("tooltip.ore_craft.conversion.convertible")
                        .withStyle(style -> style.withColor(0xA9A6B0)));
            }
            lines.add(Component.translatable("gui.ore_craft.conversion.extract_hint")
                    .withStyle(style -> style.withColor(0xA9A6B0)));
            graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        } else if (statusTicks > 0 && statusMessage != null && font.width(statusMessage) > sx(140)
                && localX >= sx(45) && localX < sx(190) && localY >= sy(78) && localY < sy(92)) {
            graphics.renderTooltip(font, statusMessage, mouseX, mouseY);
        } else if (localX >= sx(45) && localX < sx(190) && localY >= sy(57) && localY < sy(79)) {
            graphics.renderTooltip(font, Component.literal(formatNumber(menu.clientBalance()) + " ME"), mouseX, mouseY);
        }
    }

    /** 按当前界面固定使用的区域设置格式化完整整数。 */
    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 为有限宽度的余额区域生成带单位后缀的紧凑数字。 */
    private static String shortNumber(long value) {
        if (value < 10000) return formatNumber(value);
        if (value < 1_000_000) return (value / 1000) + "k";
        if (value < 1_000_000_000) return compactNumber(value, 1_000_000, "m");
        if (value < 1_000_000_000_000L) return compactNumber(value, 1_000_000_000L, "b");
        if (value < 1_000_000_000_000_000L) return compactNumber(value, 1_000_000_000_000L, "t");
        if (value < 1_000_000_000_000_000_000L) return compactNumber(value, 1_000_000_000_000_000L, "q");
        return compactNumber(value, 1_000_000_000_000_000_000L, "Q");
    }

    /** 保留两位小数并添加单位后缀，用于显示大额余额。 */
    private static String compactNumber(long value, long divisor, String suffix) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)
                .toPlainString() + suffix;
    }

    /** 目录筛选分类及其本地化标签。 */
    private enum Category {
        ALL("gui.ore_craft.conversion.category.all"),
        BLOCKS("gui.ore_craft.conversion.category.blocks"),
        MATERIALS("gui.ore_craft.conversion.category.materials"),
        TOOLS("gui.ore_craft.conversion.category.tools"),
        EQUIPMENT("gui.ore_craft.conversion.category.equipment"),
        OTHER("gui.ore_craft.conversion.category.other");

        private final String translation;

        /** 创建带本地化键的目录分类。 */
        Category(String translation) { this.translation = translation; }

        /** 根据物品类型和注册 ID 将物品归入当前分类。 */
        private boolean matches(Item item) {
            if (this == ALL) return true;
            Category actual;
            // 优先检查可明确识别的装备和工具类型，再用注册 ID 区分常见材料与其他物品。
            if (item instanceof BlockItem) actual = BLOCKS;
            else if (item instanceof ArmorItem || item.getDefaultInstance().isDamageableItem()
                    && !(item instanceof DiggerItem || item instanceof SwordItem
                    || item instanceof BowItem || item instanceof CrossbowItem || item instanceof FishingRodItem)) {
                actual = EQUIPMENT;
            } else if (item instanceof DiggerItem || item instanceof SwordItem || item instanceof BowItem
                    || item instanceof CrossbowItem || item instanceof FishingRodItem) actual = TOOLS;
            else {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                actual = path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_shard")
                        || path.endsWith("_dust") || path.startsWith("raw_")
                        || path.equals("coal") || path.equals("charcoal") || path.equals("diamond")
                        || path.equals("emerald") || path.equals("redstone") || path.equals("lapis_lazuli")
                        || path.equals("quartz") ? MATERIALS : OTHER;
            }
            return this == actual;
        }
    }

    /** 绘制扁平像素风外观的目录分类与分页按钮。 */
    private static final class FlatButton extends Button {
        private boolean selected;

        /** 使用原版按钮配置创建自定义绘制按钮。 */
        private FlatButton(Builder builder) { super(builder); }

        /** 绘制按钮背景、焦点状态、选择标记和本地化文本。 */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int fill = !active ? 0xFF252D36 : selected ? 0xFF24565C : 0xFF2A333D;
            if (active && isHoveredOrFocused()) fill = 0xFF405760;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            outline(graphics, getX(), getY(), getWidth(), getHeight(), selected ? CYAN : 0xFF667989);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + 2,
                    selected ? 0xFF5FAEB2 : 0xFF3D4E5C);
            if (selected) graphics.fill(getX() + 2, getY() + getHeight() - 2,
                    getX() + getWidth() - 2, getY() + getHeight() - 1, CYAN);
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                    getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, active ? TEXT : MUTED);
        }
    }
}
