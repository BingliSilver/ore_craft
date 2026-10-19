package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.entity.SoulBodyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 身体外观委托原版玩家渲染器，复用在线皮肤、粗细手臂、护甲、手持物、披风和鞘翅层。
 * 展示代理不加入客户端世界；弱键缓存随身体移除而释放，不会产生第二个真实玩家。
 */
public final class SoulBodyRenderer extends EntityRenderer<SoulBodyEntity> {
    /** 每个临时身体一个纯渲染代理；代理只复制档案与皮肤掩码，不反向持有身体。 */
    private final Map<SoulBodyEntity, BodyPlayer> proxies = new WeakHashMap<>();

    /** @param context 客户端渲染器上下文，阴影半径与原版玩家保持一致 */
    public SoulBodyRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5F;
    }

    /**
     * 把服务端同步的身体装备和固定朝向交给玩家渲染器；代理不读取真实玩家的旁观状态。
     * @param body 待绘制身体
     * @param yaw 当前实体朝向
     * @param partialTick 渲染插值比例
     * @param poseStack 相对实体位置的矩阵栈
     * @param buffers 渲染缓冲
     * @param light 当前亮度
     */
    @Override
    public void render(SoulBodyEntity body, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        BodyPlayer proxy = proxy(body);
        proxy.setPos(body.getX(), body.getY(), body.getZ());
        proxy.xo = body.getX();
        proxy.yo = body.getY();
        proxy.zo = body.getZ();
        // 代理不执行玩家刻，披风的跟随坐标必须手动固定，否则远离世界原点时会异常飘动。
        proxy.xCloak = proxy.xCloakO = body.getX();
        proxy.yCloak = proxy.yCloakO = body.getY();
        proxy.zCloak = proxy.zCloakO = body.getZ();
        proxy.yBodyRot = proxy.yBodyRotO = body.yBodyRot;
        proxy.yHeadRot = proxy.yHeadRotO = body.yHeadRot;
        proxy.setYRot(body.getYRot());
        proxy.yRotO = body.getYRot();
        proxy.setXRot(body.getXRot());
        proxy.xRotO = body.getXRot();
        proxy.tickCount = body.tickCount;
        proxy.hurtTime = body.hurtTime;
        proxy.mainArm = body.getMainArm();
        proxy.setPose(body.getPose());
        proxy.setShiftKeyDown(body.isShiftKeyDown());
        proxy.setInvisible(body.isInvisible());
        proxy.setHealth(body.getHealth());
        for (EquipmentSlot slot : EquipmentSlot.values()) proxy.setItemSlot(slot, body.getItemBySlot(slot));
        // 直接调用所选玩家渲染器，不能再次通过 dispatcher.render 递归绘制身体。
        entityRenderDispatcher.getRenderer(proxy).render(proxy, yaw, partialTick, poseStack, buffers, light);
    }

    /** @param body 身体实体 @return 根据原玩家 UUID 查到的皮肤资源 */
    @Override
    public ResourceLocation getTextureLocation(SoulBodyEntity body) { return proxy(body).getSkinTextureLocation(); }

    /** @param body 身体实体 @return 仅供渲染的玩家代理，生命周期不超过当前身体 */
    private BodyPlayer proxy(SoulBodyEntity body) {
        return proxies.computeIfAbsent(body, key -> new BodyPlayer(Minecraft.getInstance().level, key));
    }

    /** 玩家渲染代理，屏蔽真实玩家出窍时的旁观者头部专用渲染。 */
    private static final class BodyPlayer extends RemotePlayer {
        /** 出窍瞬间记录的皮肤外层掩码，代理不保留身体引用以允许弱缓存回收。 */
        private final int skinParts;
        /** 主手方向逐帧从身体同步，左利手玩家的手持装备不会反转。 */
        private HumanoidArm mainArm = HumanoidArm.RIGHT;

        /** @param level 当前客户端世界 @param body 提供档案和外层设置的身体 */
        private BodyPlayer(ClientLevel level, SoulBodyEntity body) {
            super(level, body.profile());
            int mask = 0;
            for (PlayerModelPart part : PlayerModelPart.values()) if (body.showsSkinPart(part)) mask |= part.getMask();
            skinParts = mask;
        }

        /** @return 身体始终展示完整玩家模型，即使对应在线玩家处于旁观模式 */
        @Override
        public boolean isSpectator() { return false; }

        /** @return 原玩家的主手方向，用于原版手持物和手臂渲染 */
        @Override
        public HumanoidArm getMainArm() { return mainArm; }

        /** @param part 当前外层部件 @return 出窍时记录的外层显示设置 */
        @Override
        public boolean isModelPartShown(PlayerModelPart part) { return (skinParts & part.getMask()) != 0; }
    }
}
