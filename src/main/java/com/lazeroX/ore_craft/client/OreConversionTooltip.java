package com.lazeroX.ore_craft.client;

import com.mojang.datafixers.util.Either;
import com.lazeroX.ore_craft.Ore_craft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.Locale;

/** 在物品提示中展示服务端同步的 ME 单价和可输入状态。 */
@EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT)
public final class OreConversionTooltip {
    private static final String ME_LINE = "tooltip.ore_craft.conversion.me";

    /** 工具类不允许创建实例。 */
    private OreConversionTooltip() {}

    /** 为已定价物品追加 ME 价格和可输入状态提示。 */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (Minecraft.getInstance().player == null || stack.isEmpty()) return;
        OreConversionClient.price(stack.getItem()).ifPresent(unit -> {
            event.getToolTip().add(Component.translatable(ME_LINE, format(unit)));
            if (OreConversionClient.canConvert(stack)) {
                event.getToolTip().add(Component.translatable("tooltip.ore_craft.conversion.convertible")
                        .withStyle(style -> style.withColor(0xA9A6B0)));
            }
        });
    }

    /** 注册自定义 ME 价格提示行的客户端绘制器。 */
    @SubscribeEvent
    public static void registerTooltipComponent(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(MeValueComponent.class, value -> new MeValueClientComponent(value.value()));
    }

    /** 将 ME 翻译文本替换为带专属宝石图标的提示组件。 */
    @SubscribeEvent
    public static void styleMeLine(RenderTooltipEvent.GatherComponents event) {
        List<Either<FormattedText, TooltipComponent>> lines = event.getTooltipElements();
        for (int index = 0; index < lines.size(); index++) {
            FormattedText text = lines.get(index).left().orElse(null);
            // 仅将本模组带单一数字参数的 ME 翻译行替换，其他模组提示保持原样。
            if (!(text instanceof Component component)
                    || !(component.getContents() instanceof TranslatableContents translation)
                    || !ME_LINE.equals(translation.getKey()) || translation.getArgs().length != 1) continue;
            lines.set(index, Either.right(new MeValueComponent(translation.getArgs()[0].toString())));
        }
    }

    /** 玩家退出服务器后清除客户端缓存。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        OreConversionClient.clear();
    }

    /** 使用固定区域设置格式化价格，确保分组符号不受系统语言影响。 */
    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 传递给客户端绘制层的 ME 文本数据。 */
    private record MeValueComponent(String value) implements TooltipComponent {}

    /** 绘制 ME 价格行的客户端提示组件。 */
    private record MeValueClientComponent(String value) implements ClientTooltipComponent {
        /** 返回宝石图标和单行文本所需的高度。 */
        @Override
        public int getHeight() { return 10; }

        /** 根据图标、前缀和价格文本计算提示行宽度。 */
        @Override
        public int getWidth(Font font) { return 12 + font.width("ME: ") + font.width(value); }

        /** 绘制宝石图标及 ME 价格文本。 */
        @Override
        public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
            drawGem(graphics, x, y);
            graphics.drawString(font, "ME: ", x + 12, y + 1, 0xFF84B7C0, false);
            graphics.drawString(font, value, x + 12 + font.width("ME: "), y + 1, 0xFF64E7F1, false);
        }

        /** 使用绘图矩形拼出简化的青色宝石图标。 */
        private static void drawGem(GuiGraphics graphics, int x, int y) {
            int edge = 0xFF176179;
            graphics.fill(x + 4, y, x + 6, y + 1, edge);
            graphics.fill(x + 2, y + 1, x + 8, y + 3, edge);
            graphics.fill(x + 1, y + 3, x + 9, y + 7, edge);
            graphics.fill(x + 2, y + 7, x + 8, y + 9, edge);
            graphics.fill(x + 4, y + 9, x + 6, y + 10, edge);
            graphics.fill(x + 4, y + 1, x + 6, y + 9, 0xFF50DDEC);
            graphics.fill(x + 3, y + 3, x + 7, y + 7, 0xFF27B8D1);
            graphics.fill(x + 2, y + 4, x + 4, y + 6, 0xFF60EBF2);
            graphics.fill(x + 6, y + 4, x + 8, y + 6, 0xFF60EBF2);
            graphics.fill(x + 4, y + 2, x + 6, y + 8, 0xFF83F3F3);
            graphics.fill(x + 4, y + 4, x + 6, y + 6, 0xFF0D7593);
        }
    }
}
