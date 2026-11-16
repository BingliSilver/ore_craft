package com.lazeroX.ore_craft.client;

import com.lazeroX.ore_craft.Ore_craft;
import com.lazeroX.ore_craft.item.GeologicalDetectorItem;
import com.lazeroX.ore_craft.network.GeologicalDetectorNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.joml.Matrix3f;

import java.util.OptionalDouble;
import java.util.ArrayList;
import java.util.List;

/** 仅客户端加载的探测粒子与目标高亮；共用已付费端点和倒计时，持续绘制不再扣费。 */
@Mod.EventBusSubscriber(modid = Ore_craft.MODID, value = Dist.CLIENT)
public final class GeologicalDetectorClient {
    /** 青色引导粒子，缩放系数保持细线可读性。 */
    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.1F, 0.95F, 1.0F), 0.8F);
    /** 专用线框缓冲，不借用世界批次，避免刷新其他渲染器尚未提交的顶点。 */
    private static final MultiBufferSource.BufferSource HIGHLIGHT_BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(256));
    /** 效果对应的客户端世界及服务端端点；世界对象变化即释放旧效果。 */
    private static ClientLevel effectLevel;
    private static GeologicalDetectorNetwork.Trace trace;
    /** 剩余游戏刻数；持续 30 秒，不按渲染帧率计时。 */
    private static int remainingTicks;
    /** 只记录当前探测生成且尚存活的粒子，停止引导时可精确移除，不影响挖掘和其他特效。 */
    private static final List<Particle> GUIDE_PARTICLES = new ArrayList<>();

    /** 粒子工具不创建实例。 */
    private GeologicalDetectorClient() {}

    /**
     * 在世界绘制结束后为当前目标绘制穿透地形的青色线框，与粒子共用 30 秒有效期。
     * 仅绘制目标所在的一个方块，不修改真实方块状态、光照或实体发光标记。
     * @param event 世界渲染阶段，提供当前相机；此阶段的事件矩阵不能当作世界视图使用
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!validateEffect(minecraft)) return;
        Vec3 camera = event.getCamera().getPosition();
        // 与引导粒子保持相同距离限制，远距离传送时不遗留孤立高亮。
        if (minecraft.player.position().distanceToSqr(Vec3.atCenterOf(trace.target())) > 256.0 * 256.0) return;
        // Forge 1.20.1 的 AFTER_LEVEL 传入 GameRenderer 的投影栈，而非渲染世界时的视图栈。
        // 再次使用该投影栈会重复投影，导致标记偏离目标甚至完全不可见。
        // 原版已保存实际相机视图旋转的逆矩阵，取逆恢复真实视图，包含第三人称及相机横滚。
        Matrix3f viewRotation = new Matrix3f(RenderSystem.getInverseViewRotationMatrix()).invert();
        PoseStack pose = new PoseStack();
        pose.last().pose().set(viewRotation);
        pose.last().normal().set(viewRotation);
        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            // 稍微扩大边框，避免玩家挖开目标后线框与矿石表面发生深度重叠闪烁。
            AABB bounds = new AABB(trace.target()).inflate(0.004);
            LevelRenderer.renderLineBox(pose, HIGHLIGHT_BUFFERS.getBuffer(HighlightStyle.LINES),
                    bounds, 0.1F, 0.95F, 1.0F, 1.0F);
            // NO_DEPTH_TEST 本身不切换深度开关，因此在提交专用批次时显式关闭，
            // 确保地下目标不被石头遮挡；渲染类型仅写颜色，不污染世界深度缓冲。
            RenderSystem.disableDepthTest();
            // 高亮承担远距离定位，不能随着世界雾效一起消失；仅对当前专用批次临时关闭雾。
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 2);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            HIGHLIGHT_BUFFERS.endBatch(HighlightStyle.LINES);
        } finally {
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            RenderSystem.enableDepthTest();
            pose.popPose();
        }
    }

    /** 集中声明高亮的线框渲染状态，复用原版线条着色器和状态恢复流程。 */
    private static final class HighlightStyle extends RenderStateShard {
        /** 青色线框使用两像素宽度、不剔除背面且仅写颜色；实际深度开关由提交处管理。 */
        private static final RenderType LINES = RenderType.create("ore_craft_detector_highlight",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false, false,
                RenderType.CompositeState.builder().setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(2.0)))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST).setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE).createCompositeState(false));

        /** 仅通过继承访问原版受保护状态常量，不创建状态容器实例。 */
        private HighlightStyle() { super("detector_highlight", () -> {}, () -> {}); }
    }

    /** @param result 服务端扣费成功的结果；同一玩家的新结果替换旧引导线 */
    public static void show(GeologicalDetectorNetwork.Trace result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(result.dimension())) return;
        clearEffect();
        effectLevel = minecraft.level;
        trace = result;
        remainingTicks = GeologicalDetectorItem.GUIDE_DURATION_SECONDS * 20;
    }

    /** @param event 客户端刻；每四刻重画一次，从玩家胸前连接到矿石中心 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || trace == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!validateEffect(minecraft)) return;
        GUIDE_PARTICLES.removeIf(particle -> !particle.isAlive());
        if (minecraft.isPaused()) return;
        if (--remainingTicks <= 0) {
            clearEffect();
            return;
        }
        if (remainingTicks % 4 != 0) return;
        Vec3 origin = minecraft.player.getEyePosition().add(0, -0.3, 0);
        Vec3 target = Vec3.atCenterOf(trace.target());
        double distance = origin.distanceTo(target);
        // 远离原探测范围后停止，防止传送后生成跨越大量未加载区块的粒子。
        if (distance > 256) {
            clearEffect();
            return;
        }
        int count = Math.max(1, Math.min(192, (int) Math.ceil(distance * 2)));
        for (int index = 1; index <= count; index++) {
            Vec3 point = origin.lerp(target, (double) index / count);
            spawnGuideParticle(CYAN, point, 0);
        }
        spawnGuideParticle(ParticleTypes.END_ROD, target, 0.01);
    }

    /**
     * 每刻及绘制高亮前复核目标，收到方块破坏或替换同步后立即结束整组效果。
     * 未加载区块不能以空气判定目标被破坏，等待区块同步后再比较服务端发送的原矿石类型。
     * @param minecraft 当前客户端
     * @return 当前世界、玩家、倒计时和已加载目标仍有效时为 true
     */
    private static boolean validateEffect(Minecraft minecraft) {
        if (trace == null) return false;
        if (minecraft.level != effectLevel || minecraft.player == null || !minecraft.player.isAlive()
                || remainingTicks <= 0) {
            clearEffect();
            return false;
        }
        if (effectLevel.hasChunkAt(trace.target()) && !BuiltInRegistries.BLOCK
                .getKey(effectLevel.getBlockState(trace.target()).getBlock()).equals(trace.ore())) {
            clearEffect();
            return false;
        }
        return true;
    }

    /**
     * 创建并记录一枚引导粒子，绕过距离裁剪且保留正常地形遮挡。
     * @param options 原版粒子类型及颜色
     * @param position 粒子世界坐标
     * @param speedY 纵向初速度，单位为每刻方块数
     */
    private static void spawnGuideParticle(ParticleOptions options, Vec3 position, double speedY) {
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(options,
                position.x, position.y, position.z, 0, speedY, 0);
        if (particle != null) GUIDE_PARTICLES.add(particle);
    }

    /** 停止当前目标的高亮和后续发射，并立即移除本次已生成的粒子及其引用。 */
    private static void clearEffect() {
        for (Particle particle : GUIDE_PARTICLES) particle.remove();
        GUIDE_PARTICLES.clear();
        trace = null;
        effectLevel = null;
        remainingTicks = 0;
    }
}
