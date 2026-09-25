package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.block.entity.OreConversionMachineBlockEntity;
import com.lazeroX.ore_craft.menu.OreConversionMachineMenu;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.value.OreConversionPrices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 矿质转化器界面，沿用传输接口的暗色面板和金边，右侧展示可搜索的已学习目录。
 * 目录点击只发送物品 ID；模板、支付和产出均由服务端菜单或方块实体负责。
 */
public final class OreConversionMachineScreen extends AbstractContainerScreen<OreConversionMachineMenu> {
    /** 左侧保持与传输接口相同的背包布局，右侧留出目录宽度。 */
    private static final int WIDTH = 292;
    private static final int HEIGHT = 166;
    /** 一屏展示六个已学习物品，超出部分由滚轮翻阅。 */
    private static final int LIST_X = 184;
    private static final int LIST_Y = 37;
    private static final int LIST_ROWS = 6;
    private static final int LIST_STEP = 18;
    /** 与传输接口一致的深色面板、金边和青蓝进度色。 */
    private static final int PANEL = 0xFF232630;
    private static final int BORDER = 0xFFE8AD3D;
    private static final int CYAN = 0xFF37DDF3;
    /** 已学习目录的搜索框。 */
    private EditBox search;
    /** 目录滚动起点，按单条物品移动。 */
    private int firstRow;
    /** 阻止一次目录点击的释放事件落到下方原版物品槽。 */
    private boolean suppressRelease;

    /** 为当前矿质转化器菜单创建客户端屏幕。 */
    public OreConversionMachineScreen(OreConversionMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 72;
    }

    /** 在目录上方建立搜索框，输入新词时从首项开始显示。 */
    @Override
    protected void init() {
        String previous = search == null ? "" : search.getValue();
        super.init();
        search = new EditBox(font, leftPos + LIST_X, topPos + 19, 100, 12,
                Component.translatable("gui.ore_craft.machine.search"));
        search.setMaxLength(64);
        search.setBordered(false);
        search.setTextColor(0xFFE9F1F3);
        search.setHint(Component.translatable("gui.ore_craft.machine.search"));
        search.setValue(previous);
        search.setResponder(value -> firstRow = 0);
        addRenderableWidget(search);
    }

    /** 按输入词从玩家已学习、仍可提取的目录中筛选结果。 */
    private List<OreConversionNetwork.PriceEntry> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        return OreConversionClient.catalog().stream().filter(entry -> {
            Item item = BuiltInRegistries.ITEM.get(entry.id());
            return OreConversionPrices.isPlain(new ItemStack(item)) && (query.isEmpty()
                    || entry.id().toString().contains(query)
                    || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query));
        }).toList();
    }

    /** 绘制机器槽位、五秒进度条和右侧可搜索的物品目录。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BORDER);
        graphics.fill(x + 2, y + 2, x + WIDTH - 2, y + HEIGHT - 2, PANEL);
        graphics.fill(x + 176, y + 2, x + 178, y + HEIGHT - 2, BORDER);
        for (int slotX : new int[]{25, 79, 133}) drawSlot(graphics, x + slotX, y + 34);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) drawSlot(graphics, x + 7 + column * 18, y + 83 + row * 18);
        }
        for (int column = 0; column < 9; column++) drawSlot(graphics, x + 7 + column * 18, y + 141);
        graphics.fill(x + 63, y + 63, x + 115, y + 69, 0xFF111820);
        int progress = Math.clamp(menu.progressTicks(), 0, OreConversionMachineBlockEntity.INTERVAL_TICKS);
        graphics.fill(x + 64, y + 64, x + 64 + 50 * progress / OreConversionMachineBlockEntity.INTERVAL_TICKS,
                y + 68, CYAN);

        List<OreConversionNetwork.PriceEntry> entries = filtered();
        firstRow = Math.clamp(firstRow, 0, Math.max(0, entries.size() - LIST_ROWS));
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.ore_craft.machine.empty"),
                    x + LIST_X, y + LIST_Y + 5, 0xFFAFBBC5, false);
        }
        ItemStack selected = menu.getSlot(OreConversionMachineMenu.SELECTION_SLOT).getItem();
        for (int row = 0; row < LIST_ROWS && firstRow + row < entries.size(); row++) {
            OreConversionNetwork.PriceEntry entry = entries.get(firstRow + row);
            ItemStack shown = new ItemStack(BuiltInRegistries.ITEM.get(entry.id()));
            int rowY = y + LIST_Y + row * LIST_STEP;
            boolean active = !selected.isEmpty() && ItemStack.isSameItemSameComponents(selected, shown);
            graphics.fill(x + LIST_X, rowY, x + WIDTH - 7, rowY + 17,
                    active ? 0xFF375B64 : 0xFF111820);
            graphics.renderItem(shown, x + LIST_X + 1, rowY);
            String name = shown.getHoverName().getString();
            while (font.width(name) > 78 && name.length() > 1) name = name.substring(0, name.length() - 1);
            graphics.drawString(font, name, x + LIST_X + 19, rowY + 5, 0xFFE9F1F3, false);
        }
    }

    /** 绘制一个与原传输接口相同的槽位边框。 */
    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF5D6170);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF111820);
    }

    /** 标识支付容器、虚拟选择和真实产物的用途。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xFFF5D58C, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.container"), 19, 23, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.selection"), 73, 23, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.output"), 127, 23, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.rate"), 61, 53, 0xFF9BDCE8, false);
        graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFDDE9F4, false);
        graphics.drawString(font, Component.translatable("gui.ore_craft.machine.learned"), LIST_X, 6, 0xFFF5D58C, false);
    }

    /** 目录行命中时发送选择请求，其余点击交由原版槽位与搜索框处理。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int localX = (int) mouseX - leftPos;
        int localY = (int) mouseY - topPos;
        int row = (localY - LIST_Y) / LIST_STEP;
        if (button == 0 && localX >= LIST_X && localX < WIDTH - 7
                && localY >= LIST_Y && row >= 0 && row < LIST_ROWS
                && (localY - LIST_Y) % LIST_STEP < 17) {
            List<OreConversionNetwork.PriceEntry> entries = filtered();
            int index = firstRow + row;
            if (index < entries.size()) {
                PacketDistributor.sendToServer(new OreConversionNetwork.ActionPayload(menu.containerId,
                        OreConversionNetwork.SELECT_MACHINE_ITEM, entries.get(index).id(), 0));
                suppressRelease = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 配对消费目录点击的鼠标释放事件，避免拖动或取物。 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && suppressRelease) {
            suppressRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 鼠标位于目录时按一行滚动，其他区域保留原版行为。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int localX = (int) mouseX - leftPos;
        int localY = (int) mouseY - topPos;
        if (localX >= LIST_X && localX < WIDTH && localY >= LIST_Y && localY < LIST_Y + LIST_ROWS * LIST_STEP
                && scrollY != 0) {
            int max = Math.max(0, filtered().size() - LIST_ROWS);
            firstRow = Math.clamp(firstRow + (scrollY > 0 ? -1 : 1), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 原版先绘制物品，再为目录条目显示名称及当前单价提示。 */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        int row = (localY - LIST_Y) / LIST_STEP;
        if (localX < LIST_X || localX >= WIDTH - 7 || localY < LIST_Y || row < 0 || row >= LIST_ROWS
                || (localY - LIST_Y) % LIST_STEP >= 17) return;
        List<OreConversionNetwork.PriceEntry> entries = filtered();
        int index = firstRow + row;
        if (index >= entries.size()) return;
        OreConversionNetwork.PriceEntry entry = entries.get(index);
        Item item = BuiltInRegistries.ITEM.get(entry.id());
        graphics.renderTooltip(font, List.of(item.getDescription(),
                Component.literal(entry.price() + " ME")), Optional.empty(), mouseX, mouseY);
    }
}
