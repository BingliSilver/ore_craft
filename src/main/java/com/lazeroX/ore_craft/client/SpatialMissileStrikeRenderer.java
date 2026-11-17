package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.entity.SpatialMissileStrikeEntity;
import com.lazeroX.ore_craft.event.SpatialMissileStrikes;
import com.lazeroX.ore_craft.register.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 空间打击的纯客户端外观：半透明红色光柱、地面锁定环、天空裂隙、实体导弹与冲击波。
 * 导弹直接复用已存在的原生三维物品模型；所有发射时间和命中阶段均以服务端同步为准。
 */
public final class SpatialMissileStrikeRenderer extends EntityRenderer<SpatialMissileStrikeEntity> {
    /** 与背包物品共享同一个三维模型，资源包替换模型后飞行外观也会同步更新。 */
    private final ItemRenderer itemRenderer;
    private final ItemStack missile;
    /** 圆环使用四十八段，光柱使用十二面；最多六十四个标记也不需要高密度粒子。 */
    private static final int RING_SEGMENTS = 48;
    private static final int COLUMN_SEGMENTS = 12;

    /** @param context 客户端渲染上下文；光柱与空间弹体不绘制普通实体阴影 */
    public SpatialMissileStrikeRenderer(EntityRendererProvider.Context context) {
        super(context);
        itemRenderer = context.getItemRenderer();
        missile = new ItemStack(ModItems.MINERAL_SPATIAL_GUIDED_MISSILE.get());
        shadowRadius = 0;
    }

    /**
     * 依阶段绘制锁定、飞行或半秒消散；发光顶点与满亮度物品在夜间仍可辨认。
     * @param strike 空间打击实体
     * @param yaw 同步朝向
     * @param partialTick 帧插值
     * @param poses 实体局部姿态栈
     * @param buffers 客户端渲染缓冲
     * @param light 环境光照，空间效果使用满亮度
     */
    @Override
    public void render(SpatialMissileStrikeEntity strike, float yaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light) {
        float age = strike.phaseAge(partialTick);
        VertexConsumer energy = buffers.getBuffer(RenderType.lightning());
        if (strike.phase() == SpatialMissileStrikeEntity.LOCKING) {
            // 光柱从脚部贯穿目标上方天空，外壳低透明度、内芯较亮，避免完全遮住怪物。
            float height = (float) Math.max(96, strike.level().getMaxBuildHeight() - strike.getY());
            column(poses, energy, 0.34F, height, 22);
            column(poses, energy, 0.08F, height, 95);
            float radius = strike.ringRadius();
            ring(poses, energy, radius, 0.07F, 0.04F, 150, 1, age * 0.015F);
            // 外侧进度弧随五秒倒计时缩短，不会改变实际发射时机。
            ring(poses, energy, radius + 0.2F, 0.035F, 0.045F, 180,
                    Math.max(0, 1 - age / SpatialMissileStrikes.LOCK_TICKS), -age * 0.02F);
            if (age >= SpatialMissileStrikes.LOCK_TICKS - 20) {
                poses.pushPose();
                poses.translate(0, SpatialMissileStrikeEntity.DROP_HEIGHT, 0);
                // 两条椭圆能量边缘组合为空中裂隙，最后一秒展开并连接光柱。
                float open = Mth.clamp((age - 80) / 20, 0, 1);
                poses.scale(1.8F * open, 1, 0.6F * open);
                ring(poses, energy, 1, 0.16F, 0, 200, 1, age * 0.06F);
                ring(poses, energy, 0.72F, 0.035F, 0.02F, 140, 0.8F, -age * 0.1F);
                poses.popPose();
            }
        } else if (strike.phase() == SpatialMissileStrikeEntity.FLYING) {
            poses.pushPose();
            // 模型弹头沿本地 +Y，使用同步俯仰和偏航把它旋转到导弹前进方向。
            float pitch = Mth.lerp(partialTick, strike.xRotO, strike.getXRot());
            float heading = Mth.rotLerp(partialTick, strike.yRotO, strike.getYRot());
            Vec3 direction = Vec3.directionFromRotation(pitch, heading);
            poses.mulPose(new Quaternionf().rotationTo(0, 1, 0,
                    (float) direction.x, (float) direction.y, (float) direction.z));
            poses.scale(1.7F, 1.7F, 1.7F);
            itemRenderer.renderStatic(missile, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, poses, buffers, strike.level(), strike.getId());
            poses.popPose();
        } else {
            float progress = Mth.clamp(age / 10, 0, 1);
            int alpha = (int) (210 * (1 - progress));
            // 冲击波仅是可见圆环，不附带额外碰撞、范围伤害、火焰或方块操作。
            ring(poses, energy, 0.2F + progress * 3, 0.1F, 0, alpha, 1, 0);
            ring(poses, energy, 0.1F + progress * 1.8F, 0.06F, 0.18F, alpha, 1, 0);
        }
        super.render(strike, yaw, partialTick, poses, buffers, light);
    }

    /**
     * 光柱可能出现在屏幕中而脚部标记在屏幕外，不能只按脚部的小包围盒做视锥剔除。
     * @param strike 当前打击实体
     * @param frustum 当前视锥
     * @param x 摄像机 X
     * @param y 摄像机 Y
     * @param z 摄像机 Z
     * @return 一百六十格内允许绘制；锁定以完整光柱范围做可见性判断
     */
    @Override
    public boolean shouldRender(SpatialMissileStrikeEntity strike, Frustum frustum, double x, double y, double z) {
        if (strike.distanceToSqr(x, y, z) > 160 * 160) return false;
        double height = strike.phase() == SpatialMissileStrikeEntity.LOCKING
                ? Math.max(96, strike.level().getMaxBuildHeight() - strike.getY()) : 3;
        return frustum.isVisible(strike.getBoundingBox().inflate(4).expandTowards(0, height, 0));
    }

    /**
     * 绘制无纹理的半透明多边形光柱，能量管线不受环境亮度影响。
     * @param poses 当前姿态 @param consumer 颜色顶点缓冲 @param radius 柱半径
     * @param height 柱高度 @param alpha 不透明度，0 至 255
     */
    private static void column(PoseStack poses, VertexConsumer consumer, float radius, float height, int alpha) {
        for (int index = 0; index < COLUMN_SEGMENTS; index++) {
            float a = index * Mth.TWO_PI / COLUMN_SEGMENTS;
            float b = (index + 1) * Mth.TWO_PI / COLUMN_SEGMENTS;
            float x = Mth.cos(a) * radius;
            float z = Mth.sin(a) * radius;
            float nextX = Mth.cos(b) * radius;
            float nextZ = Mth.sin(b) * radius;
            vertex(poses, consumer, x, 0, z, alpha);
            vertex(poses, consumer, nextX, 0, nextZ, alpha);
            vertex(poses, consumer, nextX, height, nextZ, alpha);
            vertex(poses, consumer, x, height, z, alpha);
        }
    }

    /**
     * 在水平面上绘制圆环或进度弧；裂隙、定位圈和命中波共用相同的几何绘制。
     * @param poses 当前姿态 @param consumer 颜色缓冲 @param radius 外半径
     * @param width 环宽 @param y 局部高度 @param alpha 不透明度
     * @param fraction 可见圆周比例 @param rotation 起始旋转弧度
     */
    private static void ring(PoseStack poses, VertexConsumer consumer, float radius, float width, float y,
                             int alpha, float fraction, float rotation) {
        int segments = Mth.ceil(RING_SEGMENTS * fraction);
        for (int index = 0; index < segments; index++) {
            float a = rotation + index * Mth.TWO_PI / RING_SEGMENTS;
            float b = rotation + Math.min(index + 1, RING_SEGMENTS * fraction) * Mth.TWO_PI / RING_SEGMENTS;
            vertex(poses, consumer, Mth.cos(a) * radius, y, Mth.sin(a) * radius, alpha);
            vertex(poses, consumer, Mth.cos(b) * radius, y, Mth.sin(b) * radius, alpha);
            vertex(poses, consumer, Mth.cos(b) * (radius - width), y, Mth.sin(b) * (radius - width), alpha);
            vertex(poses, consumer, Mth.cos(a) * (radius - width), y, Mth.sin(a) * (radius - width), alpha);
        }
    }

    /** @param poses 当前姿态 @param consumer 顶点缓冲 @param x 本地 X @param y 本地 Y
     * @param z 本地 Z @param alpha 不透明度；lightning 管线只接收位置和颜色属性 */
    private static void vertex(PoseStack poses, VertexConsumer consumer, float x, float y, float z, int alpha) {
        consumer.vertex(poses.last().pose(), x, y, z).color(255, 25, 18, alpha).endVertex();
    }

    /** @param strike 当前打击 @return 导弹物品所使用的原版纹理图集 */
    @Override
    public ResourceLocation getTextureLocation(SpatialMissileStrikeEntity strike) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
