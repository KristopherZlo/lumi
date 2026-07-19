package io.github.lumi.client;

import io.github.lumi.client.state.ClientSelection;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

/** Draws only the inclusive cyan sword selection, never a dark outside mask. */
public final class LumiSelectionOverlay {
    static final int COLOR = 0xff35c6ff;
    private static final int FILL_ALPHA = 42;
    private final ClientSelection selection;

    public LumiSelectionOverlay(ClientSelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public void register() {
        WorldRenderEvents.END_MAIN.register(this::render);
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
        var fills = context.consumers().getBuffer(
                LumiCompareRenderTypes.fill(false));
        LumiCompareOverlayRenderer.renderSolidBox(
                context.matrices(), fills,
                (float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) maxZ,
                0x35, 0xc6, 0xff, FILL_ALPHA);
        var lines = context.consumers().getBuffer(
                LumiCompareRenderTypes.outline(false));
        ShapeRenderer.renderShape(
                context.matrices(), lines,
                Shapes.create(new AABB(
                        0, 0, 0,
                        maxX - minX, maxY - minY, maxZ - minZ)),
                minX, minY, minZ, COLOR, 2.5F);
    }
}
