package io.github.lumi.client;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientZonePendingPreviewStore;
import io.github.lumi.client.state.HighlightPreparationProgress;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders changed blocks while the remappable Action key is held. */
public final class LumiPendingChangeOverlay {
    private final LumiShapeOutline shapes = new LumiShapeOutline();
    private static final VoxelShape BLOCK = Shapes.block();
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private final ClientHistoryStore history;
    private final ClientCompareStore comparisons;
    private final ClientZonePendingPreviewStore zonePreviews;
    private final PendingPreviewRefreshController refresh;
    private final LumiCompareOverlayRenderer compareOverlay =
            new LumiCompareOverlayRenderer();
    private java.util.List<io.github.lumi.domain.model.BlockChange> announced = java.util.List.of();

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
        //? if >=26.2 {
        /*WorldRenderEvents.COLLECT_SUBMITS.register(this::render);
        WorldRenderEvents.END_MAIN.register(context -> {
            var client = Minecraft.getInstance();
            renderComparison(context, client);
        });
        *///?} else {
        WorldRenderEvents.END_MAIN.register(this::render);
        //?}
        //? if >=26.1 {
        /*WorldRenderEvents.END_EXTRACTION.register(context ->
                compareOverlay.setFrustum(context.levelState().cameraRenderState.cullFrustum));
        *///?} else {
        WorldRenderEvents.END_EXTRACTION.register(context -> compareOverlay.setFrustum(context.frustum()));
        //?}
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(compareOverlay::close));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> compareOverlay.close());
    }

    public HighlightPreparationProgress highlightProgress() {
        return !comparisonMatches(Minecraft.getInstance()) || comparisons.visibleChanges().isEmpty()
                ? HighlightPreparationProgress.NONE : compareOverlay.progress();
    }

    private void tick(Minecraft client) {
        compareOverlay.retainInput(comparisonMatches(client) ? comparisons.blockChanges() : java.util.List.of());
        if (comparisons.blockChanges().isEmpty() || !comparisonMatches(client)) announced = java.util.List.of();
        if (zonePreviews.hasScope() && zonePreviews.currentZone(
                history.state().snapshot().orElse(null)).isEmpty()) zonePreviews.clear();
        boolean actionDown = actionDown(client);
        refresh.tick(actionDown, client.player != null && client.screen == null
                && history.state().snapshot().isPresent());
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = history.state().snapshot().orElse(null);
        var comparison = comparisonMatches(client) ? comparisons.visibleChanges() : java.util.List.<io.github.lumi.domain.model.BlockChange>of();
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
        if (showPending) {
            for (var block : pendingBlocks) {
                renderBlock(
                        context, camera,
                        block.x(), block.y(), block.z(),
                        0xffffd166);
            }
        }
        //? if <26.2 {
        if (!comparison.isEmpty()) {
            renderComparison(context, client);
        }
        //?}
    }

    private boolean comparisonMatches(Minecraft client) {
        return client.level != null && comparisons.isInDimension(client.level.dimension().identifier().toString());
    }

    private void renderComparison(WorldRenderContext context, Minecraft client) {
        if (client.player == null || !comparisonMatches(client)) return;
        var visible = comparisons.visibleChanges();
        if (visible.isEmpty()) return;
        compareOverlay.render(context, visible, actionDown(client));
        if (compareOverlay.isReady() && visible != announced) {
            announced = visible;
            LumiClient.showFeedback("luma.status.compare_ready", net.minecraft.ChatFormatting.GREEN);
        }
    }

    private void renderBlock(
            WorldRenderContext context,
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
        shapes.render(
                context, BLOCK, RenderTypes.linesTranslucent(),
                x - camera.x, y - camera.y, z - camera.z,
                color, 1.0F);
    }

    private static boolean actionDown(Minecraft client) {
        return LumiHotkeys.actionModifierDown(client.options.keyMappings);
    }
}
