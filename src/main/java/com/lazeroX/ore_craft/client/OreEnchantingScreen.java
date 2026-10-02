package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import com.lazeroX.ore_craft.menu.OreEnchantingMenu;
import com.lazeroX.ore_craft.network.OreEnchantingNetwork;
import com.lazeroX.ore_craft.value.EnchantMeCostCalculator;
import com.lazeroX.ore_craft.value.EnchantLevelLimits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
public final class OreEnchantingScreen extends AbstractOreContainerScreen<OreEnchantingMenu> {
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
    /** 一键清空右侧全部等级选择；只生成预览，领取输出时才确认交易。 */
    private Button clearEnchantmentsButton;
    /** 当前目标物品的上一次完整快照，用于检测组件及附魔变化。 */
    private ItemStack seenTarget = ItemStack.EMPTY;
    /** 与目标物品兼容且符合搜索条件的附魔。 */
    private List<Holder.Reference<Enchantment>> filtered = List.of();
    /** 每条附魔当前选择的目标等级；零代表移除，未记录时沿用输入物等级。 */
    private final Map<ResourceLocation, Integer> selectedLevels = new HashMap<>();
    /** 用户主动选中的附魔注册 ID；初始或选择失效时为 null，不自动高亮首项。 */
    private ResourceLocation selectedId;
    /** 附魔列表的首条可见记录。 */
    private int scroll;
    /** 保留设计尺寸 85% 的紧凑布局，再由 Minecraft 的界面尺寸设置决定实际显示大小。 */
    private static final float MAX_UI_SCALE = 0.85F;

    /**
     * 创建附魔界面并采用参考图画布大小。
     *
     * @param menu 附魔台菜单
     * @param inventory 玩家背包
     * @param title 菜单标题
     */
    public OreEnchantingScreen(OreEnchantingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    /** 返回附魔台既有的紧凑布局倍率，窗口适配与坐标换算仍由公共父类处理。 */
    @Override
    protected float maximumMenuScale() {
        return MAX_UI_SCALE;
    }

    /** 保留附魔台原有的深色暗幕，突出法阵与附魔列表。 */
    @Override
    protected int backgroundColor() {
        return 0xB0000000;
    }

    /** 初始化附魔搜索框与一键清空按钮，并根据当前目标重建列表。 */
    @Override
    protected void init() {
        String previousSearch = search == null ? "" : search.getValue();
        super.init();
        // 父类先按游戏 GUI 设置居中画布，再用统一的逻辑位置创建搜索框与按钮。
        search = new EditBox(font, leftPos + 310, topPos + 57, 125, 14,
                Component.translatable("gui.ore_craft.enchanting.search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setMaxLength(64);
        search.setHint(Component.translatable("gui.ore_craft.enchanting.search"));
        search.setValue(previousSearch);
        search.setResponder(value -> { scroll = 0; refresh(); });
        addRenderableWidget(search);
        // 按钮置于法阵右下方，位于输出槽下方、背包上方，方便在取物前清空选择。
        clearEnchantmentsButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.ore_craft.enchanting.clear_all"), button -> clearEnchantments())
                .bounds(leftPos + 210, topPos + 172, 54, 22).build());
        refresh();
    }

    /** 目标物品组件更新后清除旧选择并重建候选列表；容器余额由每帧直接读取。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (!ItemStack.matches(seenTarget, menu.target())) {
            selectedLevels.clear();
            selectedId = null;
            scroll = 0;
            refresh();
        }
    }

    /**
     * 用当前世界的附魔注册表构建候选项，已有附魔也保留以供降级或移除。
     * 书可以接受所有附魔；普通装备遵守 NeoForge 的物品扩展规则。
     * 刷新仅保留仍可选择的用户高亮项，不自动选择首项，也不提交新的等级选择。
     */
    private void refresh() {
        ItemStack target = menu.target();
        seenTarget = target.copy();
        clearEnchantmentsButton.active = !target.isEmpty();
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
        if (selectedId != null && filtered.stream().noneMatch(holder ->
                holder.key().location().equals(selectedId) && !isConflictLocked(holder))) {
            // 搜索隐藏或冲突导致原选择失效时取消高亮，等待用户下一次主动点击。
            selectedId = null;
        }
    }

    /**
     * 把全部可编辑附魔设为无，并请求服务端一次性重建结果预览。
     * 操作覆盖搜索隐藏和未滚动到的条目，同时撤销尚未领取的新增附魔。
     * 输入物与 ME 保持原状，玩家仍需领取输出物品才能确认清空。
     */
    private void clearEnchantments() {
        if (minecraft == null || minecraft.gameMode == null || menu.target().isEmpty()) return;
        var registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemEnchantments current = EnchantmentHelper.getEnchantmentsForCrafting(menu.target());
        ItemEnchantments effective = menu.target().getAllEnchantments(registry);
        // 未记录的条目默认沿用原等级；先撤销所有旧选择，再显式将已有附魔置零。
        selectedLevels.clear();
        registry.listElements().forEach(holder -> {
            int currentLevel = current.getLevel(holder);
            // 外部机制提供或增强的附魔不属于右侧可编辑列表，继续遵守原有编辑限制。
            if (currentLevel > 0 && effective.getLevel(holder) <= currentLevel) {
                selectedLevels.put(holder.key().location(), 0);
            }
        });
        // 使用原版菜单按钮包提交整体操作，服务端不依赖搜索结果或客户端提交的附魔清单。
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, OreEnchantingMenu.CLEAR_ENCHANTMENTS_BUTTON);
        refresh();
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

    /** 返回目标等级；未手动调整时保留实际原等级，避免打开界面就自动降级超限附魔。 */
    private int selectedLevel(Holder.Reference<Enchantment> holder) {
        int current = EnchantmentHelper.getEnchantmentsForCrafting(menu.target()).getLevel(holder);
        return selectedLevels.getOrDefault(holder.key().location(), current);
    }

    /**
     * 判断候选项是否和当前生效附魔冲突，与悬停提示共用冲突来源计算。
     *
     * @param candidate 待检查的右侧附魔项
     * @return 存在其他生效且不兼容的附魔时为 true
     */
    private boolean isConflictLocked(Holder.Reference<Enchantment> candidate) {
        return !conflictingEnchantments(candidate).isEmpty();
    }

    /**
     * 根据输入物已有附魔与暂存等级选择，列出阻止候选项被选中的全部附魔。
     * 等级为零的选择会移除冲突源，新增或升级选择也会即时纳入检查。
     *
     * @param candidate 待检查的右侧附魔项
     * @return 按当前语言名称排序的冲突附魔列表；没有冲突时为空
     */
    private List<Holder<Enchantment>> conflictingEnchantments(Holder.Reference<Enchantment> candidate) {
        var registry = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        // 保留物品扩展机制提供的有效附魔，再合并用户尚未领取的等级调整。
        Set<Holder<Enchantment>> active = new HashSet<>(menu.target().getAllEnchantments(registry).keySet());
        for (Map.Entry<ResourceLocation, Integer> choice : selectedLevels.entrySet()) {
            var found = registry.get(ResourceKey.create(Registries.ENCHANTMENT, choice.getKey()));
            if (found.isEmpty()) continue;
            if (choice.getValue() > 0) active.add(found.get());
            else active.remove(found.get());
        }
        // 排除自身，并稳定提示顺序，避免 HashSet 的遍历顺序导致多条提示跳动。
        return active.stream().filter(applied -> !applied.equals(candidate)
                        && !Enchantment.areCompatible(applied, candidate))
                .sorted(Comparator.comparing(applied -> applied.value().description().getString())).toList();
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

    /** 覆盖贴图内固定的列表边框，再根据用户选择动态绘制附魔行及其高亮。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, WIDTH, HEIGHT, 0, 0,
                TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        // 原贴图首行自带发光边框，范围超过动态行框；覆盖整个列表底层以统一各行外观。
        graphics.fill(leftPos + 283, topPos + 76, leftPos + 468, topPos + 316, 0xFF101C28);
        // 底图的金框按整幅插画制作，远大于 16×16 物品图标；覆盖旧框后重画紧凑槽。
        compactSlot(graphics, leftPos + 64, topPos + 79, 48, 44,
                leftPos + 77, topPos + 89);
        compactSlot(graphics, leftPos + 64, topPos + 135, 48, 45,
                leftPos + 77, topPos + 145);
        compactSlot(graphics, leftPos + 168, topPos + 105, 51, 54,
                leftPos + 181, topPos + 123);
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
        // 交易反馈收窄到清空按钮左侧，避免状态面板与按钮重叠。
        if (feedbackStatus() != OreEnchantingMenu.RESULT_NONE) {
            graphics.fill(leftPos + 111, topPos + 176, leftPos + 205, topPos + 207, 0xE31B2D3C);
            outline(graphics, leftPos + 111, topPos + 176, 94, 31,
                    feedbackStatus() >= OreEnchantingMenu.RESULT_INSUFFICIENT ? 0xFFFF8E88 : CYAN);
        }
    }

    /** 绘制附魔等级与 ME 变动；交易反馈文字限制在清空按钮左侧的面板内。 */
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
            graphics.drawCenteredString(font, font.plainSubstrByWidth(title.getString(), 82), 158,
                    status == OreEnchantingMenu.RESULT_SPENT || status == OreEnchantingMenu.RESULT_STORED ? 180 : 187,
                    color);
            if (status == OreEnchantingMenu.RESULT_SPENT || status == OreEnchantingMenu.RESULT_STORED) {
                Component amount = Component.translatable("gui.ore_craft.enchanting.result.amount",
                        compactBalance(menu.resultAmount()));
                graphics.drawCenteredString(font, amount, 158, 193, TEXT);
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
            // 已到制作上限或持有超限附魔时禁用加号；减号仍可把超限等级降至制作上限。
            boolean canIncrease = !locked && level < EnchantLevelLimits.maxCraftableLevel(holder);
            graphics.drawCenteredString(font, "+", 452, y + 5, canIncrease ? TEXT : 0xFF617381);
        }
        if (filtered.isEmpty()) {
            Component hint = target.isEmpty()
                    ? Component.translatable("gui.ore_craft.enchanting.insert_target")
                    : Component.translatable("gui.ore_craft.enchanting.no_matches");
            graphics.drawCenteredString(font, hint, 376, 115, MUTED);
        }
    }

    /** 按逻辑坐标识别输入槽、交易反馈及附魔冲突，在原 GUI 坐标中显示提示。 */
    @Override
    protected void renderExtraTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int logicalMouseX = (int) menuCoordinate(mouseX);
        int logicalMouseY = (int) menuCoordinate(mouseY);
        int x = logicalMouseX - leftPos;
        int y = logicalMouseY - topPos;
        if (clearEnchantmentsButton.isMouseOver(logicalMouseX, logicalMouseY)) {
            graphics.renderTooltip(font, Component.translatable("gui.ore_craft.enchanting.clear_all_hint"),
                    mouseX, mouseY);
        } else if (hoveredSlot != null && !hoveredSlot.hasItem()) {
            String key = hoveredSlot.index == OreEnchantingMenu.TARGET_SLOT ? "target"
                    : hoveredSlot.index == OreEnchantingMenu.CONTAINER_SLOT ? "container" : null;
            if (key != null) graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.slot." + key), mouseX, mouseY);
        } else if (x >= 215 && x < 271 && y >= 84 && y < 101) {
            graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.balance_full", format(availableMe())),
                    mouseX, mouseY);
        } else if (x >= 111 && x < 205 && y >= 176 && y < 207
                && (menu.resultStatus() == OreEnchantingMenu.RESULT_SPENT
                || menu.resultStatus() == OreEnchantingMenu.RESULT_STORED)) {
            graphics.renderTooltip(font,
                    Component.translatable("gui.ore_craft.enchanting.result.amount", format(menu.resultAmount())),
                    mouseX, mouseY);
        } else if (x >= 288 && x < 464 && y >= ROW_Y && y < ROW_Y + VISIBLE_ROWS * ROW_STEP) {
            int index = scroll + (y - ROW_Y) / ROW_STEP;
            if (index < filtered.size()) {
                List<Holder<Enchantment>> conflicts = conflictingEnchantments(filtered.get(index));
                if (!conflicts.isEmpty()) {
                    // 每个冲突来源独占一行，完整显示本地化名称，便于玩家逐项调整为无。
                    List<Component> hints = conflicts.stream().<Component>map(enchantment -> Component.translatable(
                            "gui.ore_craft.enchanting.conflict", enchantment.value().description())).toList();
                    graphics.renderComponentTooltip(font, hints, mouseX, mouseY);
                }
            }
        }
    }

    /** 选择附魔或调整等级后刷新服务端预览；输入与输出槽由菜单处理。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double logicalX = menuCoordinate(mouseX);
        double logicalY = menuCoordinate(mouseY);
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
                int maximum = EnchantLevelLimits.maxCraftableLevel(holder);
                // 超限附魔第一次降级直接进入可制作范围，差额按实际高等级计算，不能重建中间超限等级。
                if (x >= 394 && x < 409) level = Math.min(maximum, Math.max(0, level - 1));
                // 点击禁用的加号不会把已有高等级附魔偷偷压低，也不会产生超限升级请求。
                if (x >= 445 && x < 460 && level < maximum) level++;
                selectedLevels.put(selectedId, level);
                sendSelection();
            }
            return true;
        }
        // 父类只转换一次槽位及组件坐标，此处回退时保留原 GUI 鼠标位置。
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 鼠标滚轮翻阅已过滤的附魔目录。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double logicalX = menuCoordinate(mouseX);
        double logicalY = menuCoordinate(mouseY);
        int x = (int) logicalX - leftPos;
        int y = (int) logicalY - topPos;
        if (x >= 286 && x < 466 && y >= ROW_Y && y < ROW_Y + VISIBLE_ROWS * ROW_STEP) {
            scroll = Math.max(0, Math.min(Math.max(0, filtered.size() - VISIBLE_ROWS),
                    scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /**
     * 用深色面板遮住贴图中的大号装饰框，再绘制与原版 16×16 物品匹配的 22×22 槽框。
     * 物品实际坐标由菜单控制，框的左上角比物品各提前三像素。
     *
     * @param graphics 界面绘制上下文
     * @param coverX 原装饰框覆盖区域的左边界
     * @param coverY 原装饰框覆盖区域的上边界
     * @param coverWidth 覆盖区域宽度
     * @param coverHeight 覆盖区域高度
     * @param frameX 新槽框左边界
     * @param frameY 新槽框上边界
     */
    private static void compactSlot(GuiGraphics graphics, int coverX, int coverY,
                                    int coverWidth, int coverHeight, int frameX, int frameY) {
        graphics.fill(coverX, coverY, coverX + coverWidth, coverY + coverHeight, 0xFF111D2A);
        outline(graphics, coverX, coverY, coverWidth, coverHeight, 0xFF294357);
        graphics.fill(frameX, frameY, frameX + 22, frameY + 22, 0xFF9D692C);
        outline(graphics, frameX, frameY, 22, 22, 0xFFFFC968);
        graphics.fill(frameX + 2, frameY + 2, frameX + 20, frameY + 20, 0xFF172A3A);
        outline(graphics, frameX + 2, frameY + 2, 18, 18, 0xFF4A9AB2);
    }
}
