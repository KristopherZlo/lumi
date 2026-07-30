package io.github.lumi.client;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientZoneOverlayStore;
import io.github.lumi.domain.model.ZoneShellFace;
import io.github.lumi.network.ZoneOverlayArgument;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Requests and renders bounded zone shells in the world. */
public final class LumiZoneOverlay {
    private static final double FACE_THICKNESS = 0.015;
    private static final int REQUEST_STABLE_TICKS = 4;
    private final ClientZoneOverlayStore overlays;
    private final ClientHistoryStore history;
    private final Consumer<ZoneOverlayArgument.Mode> request;
    private final LumiZoneColor zoneColors = new LumiZoneColor();
    private Mode mode = Mode.FOCUSED;
    private RequestKey candidateRequest;
    private int candidateStableTicks;
    private RequestKey lastRequestedKey;
    private UUID lastEntered;
    private ClientZoneOverlayStore.Snapshot renderedSnapshot;
    private List<RenderZone> renderedZones = List.of();

    public LumiZoneOverlay(
            ClientZoneOverlayStore overlays,
            ClientHistoryStore history,
            Consumer<ZoneOverlayArgument.Mode> request) {
        this.overlays = Objects.requireNonNull(overlays, "overlays");
        this.history = Objects.requireNonNull(history, "history");
        this.request = Objects.requireNonNull(request, "request");
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        WorldRenderEvents.END_MAIN.register(this::render);
    }

    public Mode mode() {
        return mode;
    }

    public Component label() {
        return Component.translatable(switch (mode) {
            case FOCUSED -> "luma.zones.render_focused";
            case ALL -> "luma.zones.render_all";
            case HIDDEN -> "luma.zones.render_hidden";
        });
    }

    public void cycle() {
        mode = switch (mode) {
            case FOCUSED -> Mode.ALL;
            case ALL -> Mode.HIDDEN;
            case HIDDEN -> Mode.FOCUSED;
        };
        candidateRequest = null;
        candidateStableTicks = 0;
        lastRequestedKey = null;
        if (mode == Mode.HIDDEN) {
            overlays.clear();
        }
    }

    private void tick(Minecraft client) {
        var snapshot = history.state().snapshot().orElse(null);
        if (client.player == null || snapshot == null || mode == Mode.HIDDEN) {
            return;
        }
        var position = client.player.blockPosition();
        RequestKey key = new RequestKey(
                snapshot.dimensionId(), snapshot.workspaceId(), mode,
                Math.floorDiv(position.getX(), 16),
                Math.floorDiv(position.getY(), 16),
                Math.floorDiv(position.getZ(), 16),
                snapshot.zones().stream()
                        .map(zone -> new ZoneRevision(
                                zone.id(), zone.revision()))
                        .toList());
        considerRequest(key, overlays.loading(
                snapshot.dimensionId(), snapshot.workspaceId()));
        notifyEntered(client);
    }

    void considerRequest(RequestKey key, boolean loading) {
        if (!key.equals(candidateRequest)) {
            candidateRequest = key;
            candidateStableTicks = 1;
        } else {
            candidateStableTicks++;
        }
        if (candidateStableTicks >= REQUEST_STABLE_TICKS
                && !key.equals(lastRequestedKey) && !loading) {
            request.accept(key.mode() == Mode.ALL
                    ? ZoneOverlayArgument.Mode.ALL
                    : ZoneOverlayArgument.Mode.FOCUSED);
            lastRequestedKey = key;
        }
    }

    private void notifyEntered(Minecraft client) {
        var entered = overlays.snapshot().stream()
                .flatMap(snapshot -> snapshot.zones().stream())
                .filter(ClientZoneOverlayStore.ZoneView::entered)
                .findFirst().orElse(null);
        UUID enteredId = entered == null ? null : entered.id();
        if (!Objects.equals(lastEntered, enteredId)) {
            lastEntered = enteredId;
            if (entered != null) {
                client.gui.setOverlayMessage(Component.translatable(
                        "luma.actionbar.zone_entered", entered.name()), false);
            }
        }
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (mode == Mode.HIDDEN || client.player == null
                || client.screen != null) {
            return;
        }
        var snapshot = overlays.snapshot().orElse(null);
        if (snapshot == null || !snapshot.error().isEmpty()) {
            return;
        }
        List<RenderZone> zones = renderZones(snapshot);
        var camera = context.worldState().cameraRenderState.pos;
        long now = System.currentTimeMillis();
        var fills = context.consumers().getBuffer(
                LumiCompareRenderTypes.fill(false));
        for (var zone : zones) {
            int color = renderColor(
                    zoneColors.resolve(
                            zone.source().name(), zone.source().color(), now),
                    mode,
                    zone.source().active(), zone.source().entered());
            int alpha = mode == Mode.FOCUSED || zone.source().active()
                    ? 38 : zone.source().entered() ? 30 : 18;
            for (RenderFace face : zone.faces()) {
                AABB box = face.bounds();
                LumiCompareOverlayRenderer.renderFace(
                        context.matrices(), fills,
                        (float) (box.minX - camera.x),
                        (float) (box.minY - camera.y),
                        (float) (box.minZ - camera.z),
                        (float) (box.maxX - camera.x),
                        (float) (box.maxY - camera.y),
                        (float) (box.maxZ - camera.z),
                        face.side(),
                        color >> 16 & 255, color >> 8 & 255, color & 255,
                        alpha);
            }
        }
        var lines = context.consumers().getBuffer(
                LumiCompareRenderTypes.outline(false));
        for (var zone : zones) {
            int color = renderColor(
                    zoneColors.resolve(
                            zone.source().name(), zone.source().color(), now),
                    mode,
                    zone.source().active(), zone.source().entered());
            float width = mode == Mode.FOCUSED
                    ? 3.0F : zone.source().active() ? 2.75F
                    : zone.source().entered() ? 2.25F : 1.25F;
            for (RenderFace face : zone.faces()) {
                AABB box = face.bounds();
                ShapeRenderer.renderShape(
                        context.matrices(), lines,
                        face.outline(),
                        box.minX - camera.x,
                        box.minY - camera.y,
                        box.minZ - camera.z,
                        color, width);
            }
        }
    }

    private List<RenderZone> renderZones(
            ClientZoneOverlayStore.Snapshot snapshot) {
        if (renderedSnapshot != snapshot) {
            renderedSnapshot = snapshot;
            renderedZones = snapshot.zones().stream()
                    .map(zone -> new RenderZone(
                            zone, zone.faces().stream()
                                    .map(LumiZoneOverlay::renderFace)
                                    .toList()))
                    .toList();
        }
        return renderedZones;
    }

    private static RenderFace renderFace(ZoneShellFace face) {
        AABB bounds = box(face);
        return new RenderFace(
                Direction.valueOf(face.side().name()),
                bounds,
                Shapes.create(new AABB(
                        0, 0, 0,
                        bounds.getXsize(),
                        bounds.getYsize(),
                        bounds.getZsize())));
    }

    static AABB box(ZoneShellFace face) {
        double low = face.plane() - FACE_THICKNESS;
        double high = face.plane() + FACE_THICKNESS;
        return switch (face.side()) {
            case WEST, EAST -> new AABB(
                    low, face.minA(), face.minB(),
                    high, face.maxA(), face.maxB());
            case DOWN, UP -> new AABB(
                    face.minA(), low, face.minB(),
                    face.maxA(), high, face.maxB());
            case NORTH, SOUTH -> new AABB(
                    face.minA(), face.minB(), low,
                    face.maxA(), face.maxB(), high);
        };
    }

    static int renderColor(
            int color, Mode mode, boolean active, boolean entered) {
        if (mode == Mode.FOCUSED) {
            return mix(color, 0xffffff, 0.18);
        }
        if (active) return color;
        return entered
                ? mix(color, 0xffffff, 0.28)
                : mix(color, 0x000000, 0.35);
    }

    private static int mix(int source, int target, double amount) {
        int red = (int) Math.round(
                (source >> 16 & 255) * (1 - amount)
                        + (target >> 16 & 255) * amount);
        int green = (int) Math.round(
                (source >> 8 & 255) * (1 - amount)
                        + (target >> 8 & 255) * amount);
        int blue = (int) Math.round(
                (source & 255) * (1 - amount)
                        + (target & 255) * amount);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    public enum Mode {
        FOCUSED,
        ALL,
        HIDDEN
    }

    private record ZoneRevision(UUID id, long revision) { }

    private record RenderZone(
            ClientZoneOverlayStore.ZoneView source,
            List<RenderFace> faces) { }

    private record RenderFace(
            Direction side, AABB bounds, VoxelShape outline) { }

    record RequestKey(
            String dimension,
            UUID workspace,
            Mode mode,
            int cellX,
            int cellY,
            int cellZ,
            List<ZoneRevision> zones) { }
}
