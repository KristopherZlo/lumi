package io.github.luma.gbreak.client;

import io.github.luma.gbreak.network.CorruptionRestoreFadePayload;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class RestoreFadeOverlayRenderer {

    private static final double EXPANSION = 0.018D;
    private static final int MAX_ACTIVE_OVERLAYS = 4096;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final RestoreFadeAlphaCurve alphaCurve = new RestoreFadeAlphaCurve();
    private final ArrayDeque<RestoreFadeOverlay> overlays = new ArrayDeque<>();
    private int clientTicks;

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> this.clientTicks++);
        ClientPlayNetworking.registerGlobalReceiver(CorruptionRestoreFadePayload.ID, this::receivePayload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> this.clear());
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::render);
    }

    private void receivePayload(CorruptionRestoreFadePayload payload, ClientPlayNetworking.Context context) {
        this.addOverlays(payload.positions());
    }

    private void addOverlays(List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            this.overlays.addLast(new RestoreFadeOverlay(pos.toImmutable(), this.clientTicks));
        }

        while (this.overlays.size() > MAX_ACTIVE_OVERLAYS) {
            this.overlays.removeFirst();
        }
    }

    private void clear() {
        this.overlays.clear();
    }

    private void render(WorldRenderContext context) {
        if (this.overlays.isEmpty() || context.worldState().cameraRenderState == null) {
            return;
        }

        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null) {
            return;
        }

        VertexConsumer consumer = context.consumers().getBuffer(CorruptionRestoreFadeRenderLayer.LAYER);
        Iterator<RestoreFadeOverlay> iterator = this.overlays.iterator();
        while (iterator.hasNext()) {
            RestoreFadeOverlay overlay = iterator.next();
            int alpha = this.alphaCurve.alpha(
                    this.clientTicks - overlay.startedAtTick(),
                    this.settings.restoreFadeDurationTicks()
            );
            if (alpha <= 0) {
                iterator.remove();
                continue;
            }

            this.renderBlockShell(consumer, overlay.pos(), camera, alpha);
        }
    }

    private void renderBlockShell(VertexConsumer consumer, BlockPos pos, Vec3d camera, int alpha) {
        float x0 = (float) (pos.getX() - EXPANSION - camera.x);
        float y0 = (float) (pos.getY() - EXPANSION - camera.y);
        float z0 = (float) (pos.getZ() - EXPANSION - camera.z);
        float x1 = (float) (pos.getX() + 1.0D + EXPANSION - camera.x);
        float y1 = (float) (pos.getY() + 1.0D + EXPANSION - camera.y);
        float z1 = (float) (pos.getZ() + 1.0D + EXPANSION - camera.z);

        this.emitQuad(consumer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, alpha);
        this.emitQuad(consumer, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, alpha);
        this.emitQuad(consumer, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, alpha);
        this.emitQuad(consumer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, alpha);
        this.emitQuad(consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, alpha);
        this.emitQuad(consumer, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, alpha);
    }

    private void emitQuad(
            VertexConsumer consumer,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            int alpha
    ) {
        this.emitVertex(consumer, x0, y0, z0, alpha);
        this.emitVertex(consumer, x1, y1, z1, alpha);
        this.emitVertex(consumer, x2, y2, z2, alpha);
        this.emitVertex(consumer, x3, y3, z3, alpha);
    }

    private void emitVertex(VertexConsumer consumer, float x, float y, float z, int alpha) {
        consumer.vertex(x, y, z).color(255, 255, 255, alpha);
    }

    private record RestoreFadeOverlay(BlockPos pos, int startedAtTick) {}
}
