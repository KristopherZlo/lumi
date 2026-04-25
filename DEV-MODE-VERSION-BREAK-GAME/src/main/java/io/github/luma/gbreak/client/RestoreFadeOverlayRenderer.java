package io.github.luma.gbreak.client;

import io.github.luma.gbreak.network.CorruptionHealingWavePayload;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

public final class RestoreFadeOverlayRenderer {

    private static final double EXPANSION = 0.018D;
    private static final int MAX_ACTIVE_OVERLAYS = 4096;
    private static final int MAX_ACTIVE_WAVES = 4;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final RestoreFadeAlphaCurve alphaCurve = new RestoreFadeAlphaCurve();
    private final ArrayDeque<RestoreFadeOverlay> overlays = new ArrayDeque<>();
    private final ArrayDeque<HealingWave> healingWaves = new ArrayDeque<>();
    private int clientTicks;

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> this.clientTicks++);
        ClientPlayNetworking.registerGlobalReceiver(CorruptionRestoreFadePayload.ID, this::receivePayload);
        ClientPlayNetworking.registerGlobalReceiver(CorruptionHealingWavePayload.ID, this::receiveWavePayload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> this.clear());
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::render);
    }

    private void receivePayload(CorruptionRestoreFadePayload payload, ClientPlayNetworking.Context context) {
        this.addOverlays(payload.positions());
    }

    private void receiveWavePayload(CorruptionHealingWavePayload payload, ClientPlayNetworking.Context context) {
        this.healingWaves.addLast(new HealingWave(
                payload.center().toImmutable(),
                this.clientTicks,
                payload.maxRadiusBlocks(),
                payload.blocksPerStep(),
                payload.intervalTicks()
        ));
        while (this.healingWaves.size() > MAX_ACTIVE_WAVES) {
            this.healingWaves.removeFirst();
        }
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
        this.healingWaves.clear();
    }

    private void render(WorldRenderContext context) {
        if (this.overlays.isEmpty() && this.healingWaves.isEmpty()) {
            return;
        }
        if (context.worldState().cameraRenderState == null) {
            return;
        }

        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null) {
            return;
        }

        VertexConsumer consumer = context.consumers().getBuffer(CorruptionRestoreFadeRenderLayer.LAYER);
        this.renderHealingWaves(consumer, camera);
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

            this.renderBlockTop(
                    consumer,
                    overlay.pos().getX(),
                    overlay.pos().getY() + 1.0D,
                    overlay.pos().getZ(),
                    camera,
                    alpha
            );
        }
    }

    private void renderHealingWaves(VertexConsumer consumer, Vec3d camera) {
        var world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }

        Iterator<HealingWave> iterator = this.healingWaves.iterator();
        while (iterator.hasNext()) {
            HealingWave wave = iterator.next();
            int ageTicks = this.clientTicks - wave.startedAtTick();
            double radius = wave.radius(ageTicks);
            double halfWidth = wave.halfWidth();
            if (radius > wave.maxRadiusBlocks() + halfWidth) {
                iterator.remove();
                continue;
            }

            int minX = (int) Math.floor(wave.center().getX() - radius - halfWidth);
            int maxX = (int) Math.ceil(wave.center().getX() + radius + halfWidth);
            int minZ = (int) Math.floor(wave.center().getZ() - radius - halfWidth);
            int maxZ = (int) Math.ceil(wave.center().getZ() + radius + halfWidth);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double distance = Math.sqrt(wave.horizontalDistanceSquared(x, z));
                    double frontDistance = Math.abs(distance - radius);
                    if (frontDistance > halfWidth) {
                        continue;
                    }

                    int alpha = wave.alpha(frontDistance, halfWidth);
                    if (alpha <= 0) {
                        continue;
                    }

                    int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    this.renderBlockTop(consumer, x, topY, z, camera, alpha);
                }
            }
        }
    }

    private void renderBlockTop(VertexConsumer consumer, int x, double y, int z, Vec3d camera, int alpha) {
        float x0 = (float) (x - EXPANSION - camera.x);
        float y0 = (float) (y + EXPANSION - camera.y);
        float z0 = (float) (z - EXPANSION - camera.z);
        float x1 = (float) (x + 1.0D + EXPANSION - camera.x);
        float z1 = (float) (z + 1.0D + EXPANSION - camera.z);

        this.emitQuad(consumer, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, alpha);
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

    private record HealingWave(
            BlockPos center,
            int startedAtTick,
            int maxRadiusBlocks,
            int blocksPerStep,
            int intervalTicks
    ) {

        double radius(int ageTicks) {
            return Math.max(0.0D, (double) ageTicks * (double) this.blocksPerStep / (double) this.intervalTicks);
        }

        double halfWidth() {
            return Math.max(2.0D, Math.min(8.0D, this.blocksPerStep * 0.75D));
        }

        double horizontalDistanceSquared(int x, int z) {
            double dx = x + 0.5D - this.center.getX();
            double dz = z + 0.5D - this.center.getZ();
            return dx * dx + dz * dz;
        }

        int alpha(double frontDistance, double halfWidth) {
            double widthAlpha = 1.0D - frontDistance / halfWidth;
            double smoothAlpha = widthAlpha * widthAlpha * (3.0D - 2.0D * widthAlpha);
            return (int) Math.round(235.0D * Math.max(0.0D, smoothAlpha));
        }
    }
}
