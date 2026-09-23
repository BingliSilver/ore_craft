package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.entity.MiningPrimedTntEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.TntMinecartRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * 使用采矿 TNT 方块模型渲染已点燃实体的客户端渲染器。
 */
public class MiningTntRenderer extends EntityRenderer<MiningPrimedTntEntity> {
    /** 用于渲染实体所携带方块状态的方块渲染器。 */
    private final BlockRenderDispatcher blockRenderer;

    /**
     * 创建采矿 TNT 实体渲染器。
     *
     * @param context 实体渲染注册提供的客户端上下文
     */
    public MiningTntRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5F;
        blockRenderer = context.getBlockRenderDispatcher();
    }

    /** 按照原版 TNT 的闪烁和引信末段膨胀效果渲染采矿 TNT。 */
    @Override
    public void render(
            MiningPrimedTntEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight
    ) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.5F, 0.0F);
        int fuse = entity.getFuse();

        if (fuse - partialTick + 1.0F < 10.0F) {
            float scaleProgress = 1.0F - (fuse - partialTick + 1.0F) / 10.0F;
            scaleProgress = Mth.clamp(scaleProgress, 0.0F, 1.0F);
            scaleProgress *= scaleProgress;
            scaleProgress *= scaleProgress;
            float scale = 1.0F + scaleProgress * 0.3F;
            poseStack.scale(scale, scale, scale);
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.translate(-0.5F, -0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
        TntMinecartRenderer.renderWhiteSolidBlock(
                blockRenderer,
                entity.getBlockState(),
                poseStack,
                buffer,
                packedLight,
                fuse / 5 % 2 == 0
        );
        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    /** 采矿 TNT 使用方块纹理图集。 */
    @Override
    public ResourceLocation getTextureLocation(MiningPrimedTntEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
