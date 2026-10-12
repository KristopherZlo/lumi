package io.github.lumi.client;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientZonePendingPreviewStore;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders changed blocks while the remappable Action key is held. */
public final class LumiPendingChangeOverlay {
    private static final VoxelShape BLOCK = Shapes.block();
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private final ClientHistoryStore history;
    private final ClientCompareStore comparisons;
    private final ClientZonePendingPreviewStore zonePreviews;
    private final PendingPreviewRefreshController refresh;
    private final LumiCompareOverlayRenderer compareOverlay =
            new LumiCompareOverlayRenderer();

    public LumiPendingChangeOverlay(
            ClientHistoryStore history,
            ClientCompareStore comparisons,
            ClientZonePendingPreviewStore zonePreviews,
            Runnable refresh) {
        this.history = Objects.requireNonNull(history, "history");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.zonePreviews = Objects.requireNonNull(zonePreviews, "zonePreviews");
        this.refresh = new PendingPreviewRefreshController(refresh);
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END_MAIN.register(this::render);
        WorldRenderEvents.END_EXTRACTION.register(context -> compareOverlay.setFrustum(context.frustum()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> compareOverlay.close());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> compareOverlay.close());
    }

    private void tick(Minecraft client) {
        compareOverlay.retainInput(comparisons.blockChanges());
        if (zonePreviews.hasScope() && zonePreviews.currentZone(
                history.state().snapshot().orElse(null)).isEmpty()) zonePreviews.clear();
        boolean actionDown = actionDown(client);
        refresh.tick(actionDown, client.player != null && client.screen == null
                && history.state().snapshot().isPresent());
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = history.state().snapshot().orElse(null);
        var comparison = comparisons.visibleChanges();
        var pendingBlocks = snapshot == null
                ? java.util.List.<io.github.lumi.network.HistorySnapshotPayload.PendingBlock>of()
                : zonePreviews.hasScope() ? zonePreviews.blocks(snapshot) : snapshot.pendingBlocks();
        boolean showPending = snapshot != null && client.screen == null
                && actionDown(client) && !pendingBlocks.isEmpty();
        if (client.player == null || (!showPending
                && comparison.isEmpty())) {
            return;
        }
        var camera = context.worldState().cameraRenderState.pos;
        var lines = context.consumers().getBuffer(RenderTypes.linesTranslucent());
        if (showPending) {
            for (var block : pendingBlocks) {
                renderBlock(
                        context, lines, camera,
                        block.x(), block.y(), block.z(),
                        0xffffd166);
            }
        }
        if (!comparison.isEmpty()) {
            compareOverlay.render(context, comparison, actionDown(client));
        }
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

    private static boolean actionDown(Minecraft client) {
        return LumiHotkeys.actionModifierDown(client.options.keyMappings);
    }
}
