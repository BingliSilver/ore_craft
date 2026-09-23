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
import java.util.Optional;

/** 展示玩家背包和已学习物品目录；背包格子的快捷操作沿用原版容器界面。 */
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

    public OreConversionScreen(OreConversionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

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

    private Button addButton(Component label, int x, int y, int width, int height, Runnable action) {
        return addRenderableWidget(Button.builder(label, button -> action.run())
                .bounds(leftPos + x, topPos + y, width, height)
                .build(FlatButton::new));
    }

    private void action(int kind, ResourceLocation id, int count) {
        PacketDistributor.sendToServer(new OreConversionNetwork.ActionPayload(menu.containerId, kind, id, count));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (statusTicks > 0) statusTicks--;
        if (seenRevision != menu.revision()) refresh();
        else updateControls();
    }

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
        page = Math.max(0, Math.min(page, Math.max(0, (filtered.size() - 1) / PAGE_SIZE)));
        for (int index = 0; index < categoryButtons.size(); index++) {
            categoryButtons.get(index).selected = Category.values()[index] == category;
        }
        updateControls();
    }

    private void updateControls() {
        if (previous == null || next == null) return;
        previous.active = page > 0;
        next.active = (page + 1) * PAGE_SIZE < filtered.size();
    }

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

    private static void slotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + sx(17), y + sy(17), 0xEB111A22);
        outline(graphics, x, y, sx(17), sy(17), 0xFF627383);
        graphics.fill(x + sx(1), y + sy(1), x + sx(16), y + sy(2), 0xFF2B3946);
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static int sx(int value) { return Math.round(value * X_LAYOUT_SCALE); }

    private static int sy(int value) { return Math.round(value * Y_LAYOUT_SCALE); }

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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            int index = catalogIndexAt(localX, localY);
            if (index >= 0 && index < filtered.size()) {
                OreConversionNetwork.PriceEntry entry = filtered.get(index);
                if (hasShiftDown()) {
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
                action(OreConversionNetwork.EXTRACT, entry.id(), 1);
                suppressCatalogRelease = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && suppressCatalogRelease) {
            suppressCatalogRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private int catalogIndexAt(int localX, int localY) {
        if (localX < CATALOG_X || localX >= CATALOG_X + COLUMNS * CATALOG_STEP_X
                || localY < CATALOG_Y || localY >= CATALOG_Y + 4 * CATALOG_STEP_Y) return -1;
        int column = (localX - CATALOG_X) / CATALOG_STEP_X;
        int row = (localY - CATALOG_Y) / CATALOG_STEP_Y;
        if ((localX - CATALOG_X) % CATALOG_STEP_X >= CATALOG_CELL_WIDTH
                || (localY - CATALOG_Y) % CATALOG_STEP_Y >= CATALOG_CELL_HEIGHT) return -1;
        return page * PAGE_SIZE + row * COLUMNS + column;
    }

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

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String shortNumber(long value) {
        if (value < 10000) return formatNumber(value);
        if (value < 1_000_000) return (value / 1000) + "k";
        if (value < 1_000_000_000) return (value / 1_000_000) + "m";
        return (value / 1_000_000_000) + "b";
    }

    private enum Category {
        ALL("gui.ore_craft.conversion.category.all"),
        BLOCKS("gui.ore_craft.conversion.category.blocks"),
        MATERIALS("gui.ore_craft.conversion.category.materials"),
        TOOLS("gui.ore_craft.conversion.category.tools"),
        EQUIPMENT("gui.ore_craft.conversion.category.equipment"),
        OTHER("gui.ore_craft.conversion.category.other");

        private final String translation;

        Category(String translation) { this.translation = translation; }

        private boolean matches(Item item) {
            if (this == ALL) return true;
            Category actual;
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

    private static final class FlatButton extends Button {
        private boolean selected;

        private FlatButton(Builder builder) { super(builder); }

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
