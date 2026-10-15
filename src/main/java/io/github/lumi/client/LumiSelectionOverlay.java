package io.github.lumi.client;

import io.github.lumi.client.state.ClientSelection;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;

/** Draws only the inclusive cyan sword selection, never a dark outside mask. */
public final class LumiSelectionOverlay {
    static final int COLOR = 0xff35c6ff;
    private static final int FILL_ALPHA = 42;
    private static final int FRAME_ALPHA = 255;
    private static final float FRAME_RADIUS = 0.04F;
    private final LumiOverlaySubmission geometry = new LumiOverlaySubmission();
    private final ClientSelection selection;

    public LumiSelectionOverlay(ClientSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public void register() {
        //? if >=26.2 {
        /*WorldRenderEvents.COLLECT_SUBMITS.register(this::render);
        *///?} else {
        WorldRenderEvents.END_MAIN.register(this::render);
        //?}
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!LumiSelectionTool.held(client) || client.screen != null) {
            return;
        }
        var bounds = selection.bounds().orElse(null);
        if (bounds == null) return;
        var camera = context.worldState().cameraRenderState.pos;
        double minX = bounds.minX() - camera.x;
        double minY = bounds.minY() - camera.y;
        double minZ = bounds.minZ() - camera.z;
        double maxX = bounds.maxX() + 1.0 - camera.x;
        double maxY = bounds.maxY() + 1.0 - camera.y;
        double maxZ = bounds.maxZ() + 1.0 - camera.z;
        geometry.submit(context, LumiCompareRenderTypes.fill(false), (pose, fills) -> {
            LumiCompareOverlayRenderer.renderSolidBox(
                    pose, fills,
                    (float) minX, (float) minY, (float) minZ,
                    (float) maxX, (float) maxY, (float) maxZ,
                    0x35, 0xc6, 0xff, FILL_ALPHA);
            renderFrame(
                    pose, fills,
                    (float) minX, (float) minY, (float) minZ,
                    (float) maxX, (float) maxY, (float) maxZ);
        });
    }

    private static void renderFrame(
            com.mojang.blaze3d.vertex.PoseStack.Pose pose,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {
        for (int first = 0; first < 2; first++) {
            for (int second = 0; second < 2; second++) {
                float x = first == 0 ? minX : maxX;
                float firstY = first == 0 ? minY : maxY;
                float secondY = second == 0 ? minY : maxY;
                float z = second == 0 ? minZ : maxZ;
                frameBar(pose, consumer,
                        minX, firstY, z, maxX, firstY, z);
                frameBar(pose, consumer,
                        x, minY, z, x, maxY, z);
                frameBar(pose, consumer,
                        x, secondY, minZ, x, secondY, maxZ);
            }
        }
    }

    private static void frameBar(
            com.mojang.blaze3d.vertex.PoseStack.Pose pose,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {
        LumiCompareOverlayRenderer.renderSolidBox(
                pose, consumer,
                minX - FRAME_RADIUS, minY - FRAME_RADIUS, minZ - FRAME_RADIUS,
                maxX + FRAME_RADIUS, maxY + FRAME_RADIUS, maxZ + FRAME_RADIUS,
                0x35, 0xc6, 0xff, FRAME_ALPHA);
    }
}
