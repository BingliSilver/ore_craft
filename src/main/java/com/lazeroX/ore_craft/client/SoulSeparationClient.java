package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.network.OreConversionNetwork;
import com.lazeroX.ore_craft.network.SoulSeparationNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 仅客户端加载的出窍输入与 HUD；服务端确认有效后才限制交互，断线立即清理。
 * 剩余五秒在物品栏上方持续绘制倒计时，不受聊天栏或其他 action bar 消息覆盖。
 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT)
public final class SoulSeparationClient {
    /** 仅服务器状态包可以开启；客户端计时归零不能自行解除限制。 */
    private static boolean active;
    /** 供 HUD 平滑倒计时的剩余刻数，每秒由服务器校正。 */
    private static int remainingTicks;
    /** 启动键释放后才接受新的回归按键，防止长按使用瞬间结束出窍。 */
    private static boolean returnArmed;
    /** 发过回归请求后等待服务端确认，避免双手交互事件重复发包。 */
    private static boolean returnRequested;

    /** 静态客户端事件工具类不实例化。 */
    private SoulSeparationClient() {}

    /** @param enabled 服务端状态 @param ticks 服务端剩余刻数，按合法持续范围钳制 */
    public static void receive(boolean enabled, int ticks) {
        if (enabled && !active) {
            returnArmed = !Minecraft.getInstance().options.keyUse.isDown();
            returnRequested = false;
        }
        active = enabled;
        remainingTicks = Math.max(0, Math.min(600, ticks));
        if (!enabled) {
            returnArmed = false;
            returnRequested = false;
        }
    }

    /** @param event START 阶段阻断旁观传送菜单热键，END 阶段推进未暂停的本地倒计时 */
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (!active) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) { receive(false, 0); return; }
        if (event.phase == TickEvent.Phase.START) {
            // 原版旁观者还可通过数字键打开传送菜单，需在 handleKeybinds 消费前清空点击。
            for (KeyMapping key : minecraft.options.keyHotbarSlots) while (key.consumeClick()) { /* 丢弃本刻快捷栏请求。 */ }
            return;
        }
        if (!minecraft.options.keyUse.isDown()) returnArmed = true;
        if (!minecraft.isPaused() && remainingTicks > 0) remainingTicks--;
    }

    /**
     * 直接拦截鼠标按下，旁观者原版特有的观察实体和传送菜单也不能抢占左键、中键。
     * @param event 鼠标按钮输入，界面里的按钮仍按界面规则处理
     */
    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Pre event) {
        if (!active || Minecraft.getInstance().screen != null) return;
        if (event.getAction() == GLFW.GLFW_PRESS && event.getButton() <= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) requestReturn();
            event.setCanceled(true);
        }
    }

    /** @param event 重绑定后的攻击、使用和选取按键也必须禁止执行物品或旁观交互 */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isUseItem()) requestReturn();
    }

    /** @param event 出窍期间不打开背包或容器，避免移动装备后与身体副本发生冲突 */
    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (active && event.getNewScreen() instanceof AbstractContainerScreen<?>) event.setCanceled(true);
    }

    /** 验证已释放启动键且未发送过请求，再通过可靠通道请求服务端回归。 */
    private static void requestReturn() {
        if (!returnArmed || returnRequested) return;
        returnRequested = true;
        OreConversionNetwork.sendToServer(new SoulSeparationNetwork.ReturnRequest());
    }

    /**
     * 只在剩余五秒时绘制高对比文字，显示 5、4、3、2、1 秒，等待服务器结束时保持 1。
     * @param graphics GUI 绘制上下文
     * @param width 当前缩放后的界面宽度
     * @param height 当前缩放后的界面高度
     */
    public static void renderCountdown(GuiGraphics graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || remainingTicks > 100 || minecraft.options.hideGui || minecraft.player == null) return;
        int seconds = Math.max(1, (remainingTicks + 19) / 20);
        Component text = Component.translatable("gui.ore_craft.soul_eye.countdown", seconds);
        int textWidth = minecraft.font.width(text);
        int x = (width - textWidth) / 2;
        int y = height - 61;
        graphics.fill(x - 5, y - 3, x + textWidth + 5, y + 12, 0xA0000000);
        graphics.drawString(minecraft.font, text, x, y, seconds <= 2 ? 0xFF7777 : 0xFFE18A, true);
    }

    /** @param event 断线清除计时和按键锁定，不能影响下一个世界或普通旁观模式 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { receive(false, 0); }
}
