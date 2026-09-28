package com.lazeroX.ore_craft.client;

import com.mojang.datafixers.util.Either;
import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.item.EnderOreContainerItem;
import com.lazeroX.ore_craft.item.OreContainerItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
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

/** 在物品提示中展示 ME 单价、已学习标记、可输入状态与末影容器连接的账户余额。 */
@EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT)
public final class OreConversionTooltip {
    private static final String ME_LINE = "tooltip.ore_craft.conversion.me";
    /** 末影容器的账户余额行，使用与普通 ME 提示相同的宝石图标。 */
    private static final String ACCOUNT_ME_LINE = "tooltip.ore_craft.ender_ore_container.balance";
    /** ME 数值后的已学习标记，文本交由语言文件提供。 */
    private static final String LEARNED_LABEL = "tooltip.ore_craft.conversion.learned";

    /** 工具类不允许创建实例。 */
    private OreConversionTooltip() {}

    /** 为末影容器显示个人账户余额，为其他已定价物品显示 ME 价格、学习状态和输入状态。 */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (Minecraft.getInstance().player == null || stack.isEmpty()) return;
        if (stack.getItem() instanceof EnderOreContainerItem) {
            // 末影容器读取玩家账户余额；余额行在绘制阶段补上与 ME 单价相同的宝石图标。
            event.getToolTip().add(Component.translatable(ACCOUNT_ME_LINE,
                    MeNumberFormat.compact(OreConversionClient.balance())));
            // 回收显示的是容器本体价格；全局账户余额仅供查看，不能重复计入。
            OreConversionClient.price(stack.getItem()).ifPresent(unit ->
                    event.getToolTip().add(meLine(format(unit), OreConversionClient.isLearned(stack.getItem()))));
            event.getToolTip().add(Component.translatable("tooltip.ore_craft.ender_ore_container.linked")
                    .withStyle(style -> style.withColor(0xA9A6B0)));
            event.getToolTip().add(Component.translatable("tooltip.ore_craft.ender_ore_container.recycle")
                    .withStyle(style -> style.withColor(0xA9A6B0)));
            if (OreConversionClient.canConvert(stack)) {
                event.getToolTip().add(Component.translatable("tooltip.ore_craft.conversion.convertible")
                        .withStyle(style -> style.withColor(0xA9A6B0)));
            }
            return;
        }
        OreConversionClient.price(stack.getItem()).ifPresent(unit -> {
            event.getToolTip().add(meLine(format(unit), OreConversionClient.isLearned(stack.getItem())));
            boolean convertible = OreConversionClient.canConvert(stack);
            if (stack.getItem() instanceof OreContainerItem container && convertible) {
                long stored = container.storedMe(stack);
                // 本体价格仍单独显示；有存储量时补充实际回收总额，避免误认为内部 ME 会丢失。
                if (stored > 0 && unit <= Long.MAX_VALUE - stored) {
                    event.getToolTip().add(Component.translatable("tooltip.ore_craft.ore_container.recycle_total",
                            format(unit + stored)).withStyle(style -> style.withColor(0xA9A6B0)));
                }
            }
            if (convertible) {
                event.getToolTip().add(Component.translatable("tooltip.ore_craft.conversion.convertible")
                        .withStyle(style -> style.withColor(0xA9A6B0)));
            }
        });
    }

    /** 注册 ME 单价与末影容器账户余额共用的客户端绘制器。 */
    @SubscribeEvent
    public static void registerTooltipComponent(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(MeValueComponent.class,
                value -> new MeValueClientComponent(value.value(), value.learned(), value.account()));
    }

    /** 将 ME 单价或末影容器余额翻译行替换为带宝石图标的提示组件。 */
    @SubscribeEvent
    public static void styleMeLine(RenderTooltipEvent.GatherComponents event) {
        List<Either<FormattedText, TooltipComponent>> lines = event.getTooltipElements();
        for (int index = 0; index < lines.size(); index++) {
            FormattedText text = lines.get(index).left().orElse(null);
            if (!(text instanceof Component component)
                    || !(component.getContents() instanceof TranslatableContents translation)) continue;
            Object[] args = translation.getArgs();
            // 普通 ME 行的第二个参数承载已学习标记，余额行只有格式化后的数值。
            if (ME_LINE.equals(translation.getKey()) && args.length == 2) {
                lines.set(index, Either.right(new MeValueComponent(args[0].toString(),
                        args[1] instanceof Component, false)));
            } else if (ACCOUNT_ME_LINE.equals(translation.getKey()) && args.length == 1) {
                lines.set(index, Either.right(new MeValueComponent(args[0].toString(), false, true)));
            }
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

    /** 生成可保留独立灰色学习标记的 ME 行；自定义绘制器也从这两个参数读取状态。 */
    static MutableComponent meLine(String value, boolean learned) {
        return Component.translatable(ME_LINE, value, learned
                ? Component.translatable(LEARNED_LABEL).withStyle(style -> style.withColor(0xA9A6B0)) : "");
    }

    /** 传递给绘制层的 ME 数值、学习状态和账户余额类型。 */
    private record MeValueComponent(String value, boolean learned, boolean account) implements TooltipComponent {}

    /** 绘制 ME 价格或账户余额行的客户端提示组件。 */
    private record MeValueClientComponent(String value, boolean learned, boolean account) implements ClientTooltipComponent {
        /** 返回宝石图标和单行文本所需的高度。 */
        @Override
        public int getHeight() { return 10; }

        /** 根据图标、金额文本及可选的已学习标记计算提示行宽度。 */
        @Override
        public int getWidth(Font font) {
            if (account) return 12 + font.width(Component.translatable(ACCOUNT_ME_LINE, value));
            return 12 + font.width("ME: ") + font.width(value)
                    + (learned ? font.width(Component.translatable(LEARNED_LABEL)) : 0);
        }

        /** 绘制宝石图标及对应的 ME 单价或账户余额。 */
        @Override
        public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
            drawGem(graphics, x, y);
            if (account) {
                // 保留原有“全局账户 ME”文案，仅为这一行增加图标和青色金额提示。
                graphics.drawString(font, Component.translatable(ACCOUNT_ME_LINE, value),
                        x + 12, y + 1, 0xFF64E7F1, false);
                return;
            }
            graphics.drawString(font, "ME: ", x + 12, y + 1, 0xFF84B7C0, false);
            graphics.drawString(font, value, x + 12 + font.width("ME: "), y + 1, 0xFF64E7F1, false);
            // 标记紧跟数值，采用独立颜色，不影响 ME 单价的青色显示。
            if (learned) graphics.drawString(font, Component.translatable(LEARNED_LABEL),
                    x + 12 + font.width("ME: ") + font.width(value), y + 1, 0xFFA9A6B0, false);
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
