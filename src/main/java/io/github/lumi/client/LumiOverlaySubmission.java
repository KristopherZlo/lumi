package io.github.lumi.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Keeps overlay geometry in the native renderer's submission and buffer lifetime. */
final class LumiOverlaySubmission {
    void submit(WorldRenderContext context, RenderType type, BiConsumer<PoseStack.Pose, VertexConsumer> geometry) {
        //? if >=26.2 {
        /*context.submitNodeCollector().submitCustomGeometry(context.matrices(), type, geometry::accept);
        *///?} else {
        geometry.accept(context.matrices().last(), context.consumers().getBuffer(type));
        //?}
    }
}
