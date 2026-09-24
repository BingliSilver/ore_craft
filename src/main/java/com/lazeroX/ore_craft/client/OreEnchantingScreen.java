package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.network.OreEnchantingNetwork;
import com.lazeroX.ore_craft.value.EnchantMeCostCalculator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.network.PacketDistributor;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 矿质附魔台界面。附魔列表和等级控件由注册表绘制，输出槽显示服务端生成的结果预览。
 * 客户端计算仅供实时预览，最终价格及物品状态由服务端重新验证。
 */
public final class OreEnchantingScreen extends AbstractContainerScreen<OreEnchantingMenu> {
    /** 参考图按三分之一比例映射后的画布尺寸。 */
    private static final int WIDTH = 512;
    private static final int HEIGHT = 341;
    /** 原图资源尺寸。 */
    private static final int TEXTURE_WIDTH = 1536;
    private static final int TEXTURE_HEIGHT = 1024;
    /** 每页显示七条不带附魔图标的候选记录。 */
    private static final int VISIBLE_ROWS = 7;
    private static final int ROW_Y = 79;
    private static final int ROW_STEP = 34;
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            "ore_craft", "textures/gui/ore_enchanting_table.png");
    /** 界面前景色。 */
    private static final int CYAN = 0xFF80EEFF;
    private static final int TEXT = 0xFFE4EDF6;
    private static final int MUTED = 0xFF9EB3C4;

    /** 附魔搜索框，只改变客户端列表。 */
    private EditBox search;
    /** 当前目标物品的上一次完整快照，用于检测组件及附魔变化。 */
    private ItemStack seenTarget = ItemStack.EMPTY;
    /** 与目标物品兼容且符合搜索条件的附魔。 */
    private List<Holder.Reference<Enchantment>> filtered = List.of();
    /** 每条附魔当前选择的目标等级；零代表移除，未记录时沿用输入物等级。 */
    private final Map<ResourceLocation, Integer> selectedLevels = new HashMap<>();
    /** 当前高亮附魔的注册 ID。 */
    private ResourceLocation selectedId;
    /** 附魔列表的首条可见记录。 */
    private int scroll;
    /** 当前窗口对应的界面绘制倍率；鼠标事件使用其逆变换。 */
    private float uiScale = 1.0F;

    /**
     * 创建附魔界面并采用参考图画布大小。
     *
     * @param menu 附魔台菜单
     * @param inventory 玩家背包
     * @param title 菜单标题
     */
    public OreEnchantingScreen(OreEnchantingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    /** 初始化真正可输入的附魔搜索框，并根据当前目标重建列表。 */
    @Override
    protected void init() {
        String previousSearch = search == null ? "" : search.getValue();
        super.init();
        // 槽位和组件仍使用 512×341 的设计坐标，整体缩放后重新计算逻辑原点。
        uiScale = Math.min(2.0F, Math.min(width / 540.0F, height / 365.0F));
        leftPos = Math.round((width / uiScale - WIDTH) / 2.0F);
        topPos = Math.round((height / uiScale - HEIGHT) / 2.0F);
        search = new EditBox(font, leftPos + 310, topPos + 57, 125, 14,
                Component.translatable("gui.ore_craft.enchanting.search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setMaxLength(64);
        search.setHint(Component.translatable("gui.ore_craft.enchanting.search"));
        search.setValue(previousSearch);
        search.setResponder(value -> { scroll = 0; refresh(); });
        addRenderableWidget(search);
        refresh();
    }

    /** 目标物品组件更新后重建候选列表；容器余额由每帧直接读取。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (!ItemStack.matches(seenTarget, menu.target())) {
            selectedLevels.clear();
            scroll = 0;
            refresh();
        }
    }

    /**
     * 用当前世界的附魔注册表构建候选项，已有附魔也保留以供降级或移除。
     * 书可以接受所有附魔；普通装备遵守 NeoForge 的物品扩展规则。
     */
    private void refresh() {
        ItemStack target = menu.target();
        seenTarget = target.copy();
        if (target.isEmpty()) {
            filtered = List.of();
            selectedId = null;
            return;
        }
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        var registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemEnchantments existing = target.getAllEnchantments(registry);
        ItemEnchantments stored = EnchantmentHelper.getEnchantmentsForCrafting(target);
        filtered = registry.listElements().filter(holder -> {
            if (!target.is(Items.BOOK) && !target.is(Items.ENCHANTED_BOOK)
                    && !target.supportsEnchantment(holder) && existing.getLevel(holder) == 0) return false;
            if (existing.getLevel(holder) > stored.getLevel(holder)) return false;
            // 冲突关系由服务端在所有选择合并后验证，以支持先移除旧附魔再加入新附魔。
            ResourceLocation id = holder.key().location();
            return query.isEmpty() || id.toString().contains(query)
                    || holder.value().description().getString().toLowerCase(Locale.ROOT).contains(query);
        }).sorted(Comparator.comparing(holder -> holder.value().description().getString())).toList();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - VISIBLE_ROWS)));
        if (selectedId == null || filtered.stream().noneMatch(holder ->
                holder.key().location().equals(selectedId) && !isConflictLocked(holder))) {
            selectedId = filtered.stream().filter(holder -> !isConflictLocked(holder))
                    .map(holder -> holder.key().location()).findFirst().orElse(null);
        }
        sendSelection();
    }

    /** 将选中的附魔发送给服务端以刷新结果预览。 */
    private void sendSelection() {
        Holder.Reference<Enchantment> holder = selected();
        if (holder != null) PacketDistributor.sendToServer(new OreEnchantingNetwork.EnchantPayload(
                menu.containerId, holder.key().location(), selectedLevel(holder)));
    }

    /** 返回当前高亮的 Holder；列表更新后无选择时返回 null。 */
    private Holder.Reference<Enchantment> selected() {
        for (Holder.Reference<Enchantment> holder : filtered) {
            if (holder.key().location().equals(selectedId)) return holder;
        }
        return null;
    }

    /** 返回一条附魔的目标等级；首次显示时直接使用输入物当前等级。 */
    private int selectedLevel(Holder.Reference<Enchantment> holder) {
        int current = menu.target().getAllEnchantments(minecraft.level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)).getLevel(holder);
        int maximum = Math.min(255, holder.value().getMaxLevel());
        return Math.clamp(selectedLevels.getOrDefault(holder.key().location(), current), 0, maximum);
    }

    /**
     * 根据输入物已有附魔与右侧暂存的等级选择，判断候选项是否和生效附魔冲突。
     * 等级为零的已选附魔会从集合移除，因此玩家移除冲突源后候选项可立即解锁。
     *
     * @param candidate 待检查的右侧附魔项
     * @return 存在其他生效且不兼容的附魔时为 true
     */
    private boolean isConflictLocked(Holder.Reference<Enchantment> candidate) {
        var registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Set<Holder<Enchantment>> active = new HashSet<>(menu.target().getAllEnchantments(registry).keySet());
        for (Map.Entry<ResourceLocation, Integer> choice : selectedLevels.entrySet()) {
            var found = registry.get(ResourceKey.create(Registries.ENCHANTMENT, choice.getKey()));
            if (found.isEmpty()) continue;
            if (choice.getValue() > 0) active.add(found.get());
            else active.remove(found.get());
        }
        for (Holder<Enchantment> applied : active) {
            if (!applied.equals(candidate) && !Enchantment.areCompatible(applied, candidate)) return true;
        }
        return false;
    }

    /** 当前支付容器中的 ME；末影容器显示最近同步的账户余额。 */
    private long availableMe() {
        ItemStack source = menu.container();
        if (source.getItem() instanceof OreContainerItem normal) return normal.storedMe(source);
        if (source.getItem() instanceof EnderOreContainerItem) return OreConversionClient.balance();
        return 0L;
    }

    /** 判断降级返还的 ME 能否完整存入当前输入的容器。 */
    private boolean canStore(long amount) {
        ItemStack source = menu.container();
        long balance = availableMe();
        if (source.getItem() instanceof OreContainerItem normal) return amount <= normal.capacity() - balance;
        return source.getItem() instanceof EnderOreContainerItem && amount <= Long.MAX_VALUE - balance;
    }

    /** 将所有已选择附魔的价格差合并，供输出槽的提示判断使用。 */
    private BigInteger totalDifference() {
        ItemStack target = menu.target();
        ItemEnchantments current = EnchantmentHelper.getEnchantmentsForCrafting(target);
        BigInteger total = BigInteger.ZERO;
        var registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (Map.Entry<ResourceLocation, Integer> choice : selectedLevels.entrySet()) {
            var found = registry.get(ResourceKey.create(Registries.ENCHANTMENT, choice.getKey()));
            if (found.isEmpty()) continue;
            Holder.Reference<Enchantment> holder = found.get();
            long difference = EnchantMeCostCalculator.calculateDifference(
                    holder, current.getLevel(holder), choice.getValue());
            total = total.add(BigInteger.valueOf(difference));
        }
        return total;
    }

    /** 客户端只提供提示，实际 ME 交易仍由服务端再次验证。 */
    private boolean canSettlePreview() {
        BigInteger difference = totalDifference();
        if (difference.signum() > 0) return difference.compareTo(BigInteger.valueOf(availableMe())) <= 0;
        if (difference.signum() < 0) {
            BigInteger refund = difference.negate();
            return refund.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0 && canStore(refund.longValue());
        }
        return true;
    }

    /** 优先显示服务端交易结果；交易前也可提示当前预览的余额或容量问题。 */
    private int feedbackStatus() {
        if (menu.resultStatus() != OreEnchantingMenu.RESULT_NONE) return menu.resultStatus();
        if (!menu.getSlot(OreEnchantingMenu.OUTPUT_SLOT).hasItem() || canSettlePreview())
            return OreEnchantingMenu.RESULT_NONE;
        return totalDifference().signum() > 0
                ? OreEnchantingMenu.RESULT_INSUFFICIENT : OreEnchantingMenu.RESULT_FULL;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, WIDTH, HEIGHT, 0, 0,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        // 余额从左下角移到法阵右上方；独立暗底保证发光纹样后面的数字清晰。
        graphics.fill(leftPos + 215, topPos + 84, leftPos + 271, topPos + 101, 0xD9142634);
        outline(graphics, leftPos + 215, topPos + 84, 56, 17, 0xFF398FA7);
        for (int index = 0; index < VISIBLE_ROWS; index++) {
            int y = topPos + ROW_Y + index * ROW_STEP;
            boolean selected = scroll + index < filtered.size()
                    && filtered.get(scroll + index).key().location().equals(selectedId);
            boolean locked = scroll + index < filtered.size() && isConflictLocked(filtered.get(scroll + index));
            graphics.fill(leftPos + 288, y, leftPos + 464, y + 31,
                    locked ? 0xFF111A23 : selected ? 0xFF203E50 : 0xFF1A2938);
            outline(graphics, leftPos + 288, y, 176, 31,
                    locked ? 0xFF354552 : selected ? 0xFF34CDEB : 0xFF43586A);
            if (scroll + index < filtered.size() && !locked) {
                buttonBackground(graphics, leftPos + 394, y + 3, 14, 14);
                buttonBackground(graphics, leftPos + 445, y + 3, 14, 14);
            }
        }
        // 重新绘制动态滚动条，避免参考图固定在顶部的装饰滑块误导玩家。
        graphics.fill(leftPos + 470, topPos + 82, leftPos + 477, topPos + 314, 0xFF101C28);
        int range = Math.max(0, filtered.size() - VISIBLE_ROWS);
        int handleHeight = range == 0 ? 230 : Math.max(18, 230 * VISIBLE_ROWS / filtered.size());
        int handleY = topPos + 83 + (range == 0 ? 0 : (229 - handleHeight) * scroll / range);
        graphics.fill(leftPos + 471, handleY, leftPos + 476, handleY + handleHeight, CYAN);
        // 快捷栏占用参考图下沿的空白区域，确保手持工具也能送入目标槽。
        for (int column = 0; column < 9; column++) {
            int x = leftPos + 50 + column * 23;
            graphics.fill(x, topPos + 297, x + 20, topPos + 317, 0xFF192635);
            outline(graphics, x, topPos + 297, 20, 20, 0xFF556D7C);
        }
        // 法阵下方沿用原本的空白区域展示结果，避免弹出聊天提示。
        if (feedbackStatus() != OreEnchantingMenu.RESULT_NONE) {
            graphics.fill(leftPos + 111, topPos + 176, leftPos + 273, topPos + 207, 0xE31B2D3C);
            outline(graphics, leftPos + 111, topPos + 176, 162, 31,
                    feedbackStatus() >= OreEnchantingMenu.RESULT_INSUFFICIENT ? 0xFFFF8E88 : CYAN);
        }
    }

    /** 绘制输入物当前等级、目标等级和对应的 ME 变动；附魔行不显示图标。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        ItemStack target = menu.target();
        Component balanceLabel = Component.translatable("gui.ore_craft.enchanting.balance", compactBalance(availableMe()));
        graphics.drawString(font, balanceLabel, 268 - font.width(balanceLabel), 88, CYAN, false);
        int status = feedbackStatus();
        if (status != OreEnchantingMenu.RESULT_NONE) {
            String key = switch (status) {
                case OreEnchantingMenu.RESULT_SPENT -> "spent";
                case OreEnchantingMenu.RESULT_STORED -> "stored";
                case OreEnchantingMenu.RESULT_UNCHANGED -> "unchanged_result";
                case OreEnchantingMenu.RESULT_INSUFFICIENT -> "insufficient";
                default -> "full";
            };
            int color = status >= OreEnchantingMenu.RESULT_INSUFFICIENT ? 0xFFFF8E88 : CYAN;
            Component title = Component.translatable("gui.ore_craft.enchanting.result." + key);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(title.getString(), 150), 192,
                    status == OreEnchantingMenu.RESULT_SPENT || status == OreEnchantingMenu.RESULT_STORED ? 180 : 187,
                    color);
            if (status == OreEnchantingMenu.RESULT_SPENT || status == OreEnchantingMenu.RESULT_STORED) {
                Component amount = Component.translatable("gui.ore_craft.enchanting.result.amount",
                        compactBalance(menu.resultAmount()));
                graphics.drawCenteredString(font, amount, 192, 193, TEXT);
            }
        }
        for (int index = 0; index < VISIBLE_ROWS && scroll + index < filtered.size(); index++) {
            Holder.Reference<Enchantment> holder = filtered.get(scroll + index);
            boolean locked = isConflictLocked(holder);
            int y = ROW_Y + index * ROW_STEP;
            int level = selectedLevel(holder);
            int currentLevel = EnchantmentHelper.getEnchantmentsForCrafting(target).getLevel(holder);
            long difference = EnchantMeCostCalculator.calculateDifference(holder, currentLevel, level);
            String name = holder.value().description().getString();
            graphics.drawString(font, font.plainSubstrByWidth(name, 72), 294, y + 4,
                    locked ? 0xFF677987 : TEXT, false);
            String key = difference > 0 ? "cost" : difference < 0 ? "store" : "unchanged";
            Component amount = difference == 0
                    ? Component.translatable("gui.ore_craft.enchanting.unchanged")
                    : Component.translatable("gui.ore_craft.enchanting." + key, format(Math.abs(difference)));
            // 同时调整多条附魔时按净差额交易，颜色也应反映整笔交易能否完成。
            boolean affordable = canSettlePreview();
            graphics.drawString(font, amount, 294, y + 18,
                    locked ? 0xFF617381 : affordable ? CYAN : 0xFFFF8E88, false);
            graphics.drawCenteredString(font, "−", 401, y + 5, locked ? 0xFF617381 : TEXT);
            graphics.drawCenteredString(font, levelName(level), 426, y + 5, locked ? 0xFF7C8E9C : TEXT);
            graphics.drawCenteredString(font, "+", 452, y + 5, locked ? 0xFF617381 : TEXT);
        }
        if (filtered.isEmpty()) {
            Component hint = target.isEmpty()
                    ? Component.translatable("gui.ore_craft.enchanting.insert_target")
                    : Component.translatable("gui.ore_craft.enchanting.no_matches");
            graphics.drawCenteredString(font, hint, 376, 115, MUTED);
        }
    }

    /** 渲染输入槽提示及 ME 不足时的输出槽提示。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 暗化背景使用真实窗口坐标；其余内容和物品悬停区域一起缩放。
        graphics.fill(0, 0, width, height, 0xB0000000);
        int logicalMouseX = Math.round(mouseX / uiScale);
        int logicalMouseY = Math.round(mouseY / uiScale);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1.0F);
        super.render(graphics, logicalMouseX, logicalMouseY, partialTick);
        renderTooltip(graphics, logicalMouseX, logicalMouseY);
        int x = logicalMouseX - leftPos;
        int y = logicalMouseY - topPos;
        if (hoveredSlot != null && !hoveredSlot.hasItem()) {
            String key = hoveredSlot.index == OreEnchantingMenu.TARGET_SLOT ? "target"
                    : hoveredSlot.index == OreEnchantingMenu.CONTAINER_SLOT ? "container" : null;
            if (key != null) graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.slot." + key), logicalMouseX, logicalMouseY);
        } else if (x >= 215 && x < 271 && y >= 84 && y < 101) {
            graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.balance_full", format(availableMe())),
                    logicalMouseX, logicalMouseY);
        } else if (x >= 111 && x < 273 && y >= 176 && y < 207
                && (menu.resultStatus() == OreEnchantingMenu.RESULT_SPENT
                || menu.resultStatus() == OreEnchantingMenu.RESULT_STORED)) {
            graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.result.amount", format(menu.resultAmount())),
                    logicalMouseX, logicalMouseY);
        } else if (x >= 288 && x < 464 && y >= ROW_Y && y < ROW_Y + VISIBLE_ROWS * ROW_STEP) {
            int index = scroll + (y - ROW_Y) / ROW_STEP;
            if (index < filtered.size() && isConflictLocked(filtered.get(index))) {
                graphics.renderTooltip(font, Component.translatable("gui.ore_craft.enchanting.conflict"),
                        logicalMouseX, logicalMouseY);
            }
        }
        graphics.pose().popPose();
    }

    /** 避免容器基类在已缩放坐标系内再次铺满窗口背景。 */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    /** 选择附魔或调整等级后刷新服务端预览；输入与输出槽由菜单处理。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double logicalX = mouseX / uiScale;
        double logicalY = mouseY / uiScale;
        int x = (int) logicalX - leftPos;
        int y = (int) logicalY - topPos;
        if (button == 0 && x >= 288 && x < 464 && y >= ROW_Y && y < ROW_Y + VISIBLE_ROWS * ROW_STEP) {
            int index = scroll + (y - ROW_Y) / ROW_STEP;
            if (index < filtered.size()) {
                Holder.Reference<Enchantment> holder = filtered.get(index);
                // 整行（含等级加减按钮）不可交互，防止客户端发送冲突选择。
                if (isConflictLocked(holder)) return true;
                selectedId = holder.key().location();
                int level = selectedLevel(holder);
                if (x >= 394 && x < 409) level = Math.max(0, level - 1);
                if (x >= 445 && x < 460) level = Math.min(Math.min(255, holder.value().getMaxLevel()), level + 1);
                selectedLevels.put(selectedId, level);
                sendSelection();
            }
            return true;
        }
        return super.mouseClicked(logicalX, logicalY, button);
    }

    /** 把松开鼠标事件换算回未缩放的菜单坐标，保证拖拽物品落到正确槽位。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    /** 拖拽物品及组件时同步换算位置与位移。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button,
                dragX / uiScale, dragY / uiScale);
    }

    /** 搜索框的鼠标悬停状态也使用与绘制一致的逻辑坐标。 */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale);
    }

    /** 鼠标滚轮翻阅已过滤的附魔目录。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double logicalX = mouseX / uiScale;
        double logicalY = mouseY / uiScale;
        int x = (int) logicalX - leftPos;
        int y = (int) logicalY - topPos;
        if (x >= 286 && x < 466 && y >= ROW_Y && y < ROW_Y + VISIBLE_ROWS * ROW_STEP) {
            scroll = Math.max(0, Math.min(Math.max(0, filtered.size() - VISIBLE_ROWS),
                    scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, scrollX, scrollY);
    }

    /** 用四条一像素线绘制统一方框。 */
    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /** 绘制附魔等级加减控件的深色背景。 */
    private static void buttonBackground(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF263B50);
        outline(graphics, x, y, width, height, 0xFF667F93);
    }

    /** 以千位分隔符显示 ME 金额。 */
    private static String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** 将余额压缩到右上角面板中；悬停提示仍展示完整数值。 */
    private static String compactBalance(long amount) {
        if (amount < 10_000L) return format(amount);
        String[] suffixes = {"k", "m", "b", "t", "q", "Q"};
        double scaled = amount;
        int index = -1;
        do {
            scaled /= 1000.0;
            index++;
        } while (scaled >= 1000.0 && index < suffixes.length - 1);
        return String.format(Locale.ROOT, scaled >= 100.0 ? "%.0f%s" : "%.1f%s", scaled, suffixes[index]);
    }

    /** 原版仅为常见等级提供罗马数字翻译；更高的模组等级用阿拉伯数字。 */
    private static String levelName(int level) {
        if (level == 0) return Component.translatable("gui.ore_craft.enchanting.none").getString();
        return level <= 10 ? Component.translatable("enchantment.level." + level).getString() : Integer.toString(level);
    }
}
