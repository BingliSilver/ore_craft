package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.entity.MineralTeleportPortalEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 临时传送门的双面发光渲染：金色框架由竖缝展开，青绿门面持续旋转，关闭时缩回中心。
 * 使用 Minecraft 原生实体绘制管线；纹理与权杖图标配色一致，不依赖额外模型或客户端逻辑判定。
 */
public final class MineralTeleportPortalRenderer extends EntityRenderer<MineralTeleportPortalEntity> {
    /** 静止的晶石门框与独立旋转的能量门面，均为带透明背景的像素纹理。 */
    private static final ResourceLocation FRAME = ResourceLocation.fromNamespaceAndPath(
            Ore_craft.MODID, "textures/entity/mineral_portal_frame.png");
    private static final ResourceLocation CORE = ResourceLocation.fromNamespaceAndPath(
            Ore_craft.MODID, "textures/entity/mineral_portal_core.png");
    /** 用四十八段三角扇绘制椭圆，UV 单独旋转，门的世界轮廓始终固定。 */
    private static final int SEGMENTS = 48;

    /** @param context 客户端渲染上下文，入口不投射实体阴影 */
    public MineralTeleportPortalRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    /**
     * 根据服务端同步时间绘制展开、稳定旋转及收起阶段。
     * entityTranslucent 不剔除背面，玩家从入口两侧都能看见门面；顶点使用满亮度呈现能量发光。
     * @param portal 入口实体
     * @param yaw 插值朝向，入口固定后不旋转
     * @param partialTick 帧插值
     * @param poses 相对实体位置的矩阵栈
     * @param buffers 原版实体渲染缓冲
     * @param light 环境亮度，能量纹理使用固定满亮度
     */
    @Override
    public void render(MineralTeleportPortalEntity portal, float yaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light) {
        float open = portal.openness(partialTick);
        if (open <= 0) return;
        poses.pushPose();
        poses.translate(0, MineralTeleportPortalEntity.HEIGHT / 2, 0);
        poses.mulPose(Axis.YP.rotationDegrees(-portal.getYRot()));
        // 宽度从竖缝展开，高度同步伸展；收起时使用同一曲线反向播放。
        poses.scale(Math.max(0.025F, open), 0.35F + 0.65F * open, 1);
        float age = portal.age(partialTick);
        int alpha = Mth.clamp((int) (255 * open), 0, 255);
        float pulse = 0.96F + 0.04F * Mth.sin(age * 0.12F);
        drawCore(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(CORE)), age * 0.035F,
                (int) (alpha * 0.92F), pulse);
        drawFrame(poses, buffers.getBuffer(RenderType.entityTranslucent(FRAME)), alpha);
        poses.popPose();
        super.render(portal, yaw, partialTick, poses, buffers, light);
    }

    /** @param poses 当前姿态 @param consumer 门框纹理缓冲 @param alpha 开合透明度 */
    private static void drawFrame(PoseStack poses, VertexConsumer consumer, int alpha) {
        float x = MineralTeleportPortalEntity.WIDTH / 2;
        float y = MineralTeleportPortalEntity.HEIGHT / 2;
        vertex(poses, consumer, -x, -y, 0, 0, 1, alpha);
        vertex(poses, consumer, x, -y, 0, 1, 1, alpha);
        vertex(poses, consumer, x, y, 0, 1, 0, alpha);
        vertex(poses, consumer, -x, y, 0, 0, 0, alpha);
    }

    /**
     * 绘制椭圆门面；仅旋转纹理采样坐标，避免整扇竖门随旋涡倾斜或改变触发范围。
     * QUADS 管线中的最后一个顶点重复中心，将每段退化为一个有效三角形。
     * @param poses 当前姿态
     * @param consumer 能量纹理缓冲
     * @param rotation 旋涡旋转弧度
     * @param alpha 基础透明度
     * @param pulse 缓慢脉动的亮度比例
     */
    private static void drawCore(PoseStack poses, VertexConsumer consumer, float rotation, int alpha, float pulse) {
        float radiusX = MineralTeleportPortalEntity.WIDTH * 0.405F;
        float radiusY = MineralTeleportPortalEntity.HEIGHT * 0.405F;
        int opacity = (int) (alpha * pulse);
        for (int index = 0; index < SEGMENTS; index++) {
            float angle = index * Mth.TWO_PI / SEGMENTS;
            float next = (index + 1) * Mth.TWO_PI / SEGMENTS;
            vertex(poses, consumer, 0, 0, 0.002F, 0.5F, 0.5F, opacity);
            vertex(poses, consumer, Mth.cos(angle) * radiusX, Mth.sin(angle) * radiusY, 0.002F,
                    0.5F + 0.49F * Mth.cos(angle + rotation), 0.5F - 0.49F * Mth.sin(angle + rotation), opacity);
            vertex(poses, consumer, Mth.cos(next) * radiusX, Mth.sin(next) * radiusY, 0.002F,
                    0.5F + 0.49F * Mth.cos(next + rotation), 0.5F - 0.49F * Mth.sin(next + rotation), opacity);
            vertex(poses, consumer, 0, 0, 0.002F, 0.5F, 0.5F, opacity);
        }
    }

    /** @param poses 当前姿态 @param consumer 顶点缓冲 @param x 本地 X @param y 本地 Y @param z 本地 Z
     * @param u 纹理横坐标 @param v 纹理纵坐标 @param alpha 透明度，使用满亮度且不受实体受伤覆盖影响 */
    private static void vertex(PoseStack poses, VertexConsumer consumer, float x, float y, float z,
                               float u, float v, int alpha) {
        consumer.vertex(poses.last().pose(), x, y, z).color(255, 255, 255, alpha).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(poses.last().normal(), 0, 0, 1).endVertex();
    }

    /** @param portal 当前入口 @return 门框的主纹理位置 */
    @Override
    public ResourceLocation getTextureLocation(MineralTeleportPortalEntity portal) { return FRAME; }
}
