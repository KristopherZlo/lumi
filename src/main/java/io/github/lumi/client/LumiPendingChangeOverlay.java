package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders bounded changed-block outlines while Alt is held in normal play. */
public final class LumiPendingChangeOverlay {
    private static final VoxelShape BLOCK = Shapes.block();
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private final ClientHistoryStore history;
    private final ClientCompareStore comparisons;
    private final PendingPreviewRefreshController refresh;
    private final LumiCompareOverlayRenderer compareOverlay =
            new LumiCompareOverlayRenderer();

    public LumiPendingChangeOverlay(
            ClientHistoryStore history,
            ClientCompareStore comparisons,
            Runnable refresh) {
        this.history = Objects.requireNonNull(history, "history");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.refresh = new PendingPreviewRefreshController(refresh);
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END_MAIN.register(this::render);
    }

    private void tick(Minecraft client) {
        boolean altDown = altDown(client);
        refresh.tick(altDown, client.player != null && client.screen == null
                && history.state().snapshot().isPresent());
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = history.state().snapshot().orElse(null);
        var comparison = comparisons.visibleChanges();
        boolean showPending = snapshot != null && client.screen == null
                && altDown(client) && !snapshot.pendingBlocks().isEmpty();
        if (client.player == null || (!showPending
                && comparison.isEmpty())) {
            return;
        }
        var camera = context.worldState().cameraRenderState.pos;
        var lines = context.consumers().getBuffer(RenderTypes.linesTranslucent());
        if (showPending) {
            for (var block : snapshot.pendingBlocks()) {
                renderBlock(
                        context, lines, camera,
                        block.x(), block.y(), block.z(),
                        0xffffd166);
            }
        }
        compareOverlay.render(context, comparison, altDown(client));
    }

    private static void renderBlock(
            WorldRenderContext context,
            com.mojang.blaze3d.vertex.VertexConsumer lines,
            net.minecraft.world.phys.Vec3 camera,
            int x,
            int y,
            int z,
            int color) {
        double centerX = x + 0.5 - camera.x;
        double centerY = y + 0.5 - camera.y;
        double centerZ = z + 0.5 - camera.z;
        if (centerX * centerX + centerY * centerY + centerZ * centerZ
                > MAX_DISTANCE_SQUARED) {
            return;
        }
        ShapeRenderer.renderShape(
                context.matrices(), lines, BLOCK,
                x - camera.x, y - camera.y, z - camera.z,
                color, 1.0F);
    }

    private static boolean altDown(Minecraft client) {
        return InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RALT);
    }
}
