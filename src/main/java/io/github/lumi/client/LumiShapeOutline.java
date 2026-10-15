package io.github.lumi.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Submits a camera-relative outline through the native rendering path. */
final class LumiShapeOutline {
    void render(WorldRenderContext context, VoxelShape shape, RenderType type,
            double x, double y, double z, int color, float width) {
        //? if >=26.2 {
        /*var poses = context.matrices();
        poses.pushPose();
        try {
            poses.translate(x, y, z);
            context.submitNodeCollector().submitShapeOutline(poses, shape, type, color, width, false);
        } finally { poses.popPose(); }
        *///?} else {
        net.minecraft.client.renderer.ShapeRenderer.renderShape(context.matrices(),
                context.consumers().getBuffer(type), shape, x, y, z, color, width);
        //?}
    }
}
