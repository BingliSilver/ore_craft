package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.block.entity.OreEnchantingBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.BookModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * 使用原版附魔书模型和贴图渲染矿质附魔台上方的动态书本。
 * 方块模型只负责底座，避免静态书页与动画书页相互覆盖。
 */
public final class OreEnchantingBookRenderer implements BlockEntityRenderer<OreEnchantingBlockEntity> {
    /** 与原版附魔台相同的书本模型层。 */
    private final BookModel bookModel;

    /** 从客户端模型烘焙上下文构建书本模型。 */
    public OreEnchantingBookRenderer(BlockEntityRendererProvider.Context context) {
        this.bookModel = new BookModel(context.bakeLayer(ModelLayers.BOOK));
    }

    /**
     * 插值客户端刻之间的开合、转向和翻页状态，再绘制漂浮书本。
     *
     * @param entity 当前附魔台的动画状态
     * @param partialTick 刻间插值比例
     * @param poseStack 方块局部变换栈
     * @param buffers 渲染缓冲
     * @param packedLight 当前光照值
     * @param packedOverlay 当前覆盖层值
     */
    @Override
    public void render(OreEnchantingBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.75F, 0.5F);
        float animationTime = entity.time + partialTick;
        poseStack.translate(0.0F, 0.1F + Mth.sin(animationTime * 0.1F) * 0.01F, 0.0F);
        float deltaRotation = entity.rotation - entity.previousRotation;
        while (deltaRotation >= Math.PI) deltaRotation -= (float) (Math.PI * 2);
        while (deltaRotation < -Math.PI) deltaRotation += (float) (Math.PI * 2);
        float rotation = entity.previousRotation + deltaRotation * partialTick;
        poseStack.mulPose(Axis.YP.rotation(-rotation));
        poseStack.mulPose(Axis.ZP.rotationDegrees(80.0F));
        float flip = Mth.lerp(partialTick, entity.previousFlip, entity.flip);
        float pageLeft = Mth.clamp(Mth.frac(flip + 0.25F) * 1.6F - 0.3F, 0.0F, 1.0F);
        float pageRight = Mth.clamp(Mth.frac(flip + 0.75F) * 1.6F - 0.3F, 0.0F, 1.0F);
        float open = Mth.lerp(partialTick, entity.previousOpen, entity.open);
        bookModel.setupAnim(animationTime, pageLeft, pageRight, open);
        VertexConsumer vertices = EnchantTableRenderer.BOOK_LOCATION.buffer(buffers, RenderType::entitySolid);
        bookModel.render(poseStack, vertices, packedLight, packedOverlay, -1);
        poseStack.popPose();
    }

    /** 扩大渲染范围，允许书本漂浮到方块顶部之外。 */
    @Override
    public AABB getRenderBoundingBox(OreEnchantingBlockEntity entity) {
        var pos = entity.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.5, pos.getZ() + 1.0);
    }
}
