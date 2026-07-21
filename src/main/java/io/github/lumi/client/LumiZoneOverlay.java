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

/** Requests and renders bounded zone shells in the world. */
public final class LumiZoneOverlay {
    private static final double FACE_THICKNESS = 0.015;
    private final ClientZoneOverlayStore overlays;
    private final ClientHistoryStore history;
    private final Consumer<ZoneOverlayArgument.Mode> request;
    private Mode mode = Mode.FOCUSED;
    private RequestKey lastRequest;
    private UUID lastEntered;

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
        lastRequest = null;
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
        if (!key.equals(lastRequest)) {
            request.accept(mode == Mode.ALL
                    ? ZoneOverlayArgument.Mode.ALL
                    : ZoneOverlayArgument.Mode.FOCUSED);
            lastRequest = key;
        }
        notifyEntered(client);
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
        var camera = context.worldState().cameraRenderState.pos;
        var fills = context.consumers().getBuffer(
                LumiCompareRenderTypes.fill(false));
        for (var zone : snapshot.zones()) {
            int color = renderColor(
                    zone.color(), mode, zone.active(), zone.entered());
            int alpha = mode == Mode.FOCUSED || zone.active()
                    ? 38 : zone.entered() ? 30 : 18;
            for (ZoneShellFace face : zone.faces()) {
                AABB box = box(face).move(
                        -camera.x, -camera.y, -camera.z);
                LumiCompareOverlayRenderer.renderFace(
                        context.matrices(), fills,
                        (float) box.minX, (float) box.minY, (float) box.minZ,
                        (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                        Direction.valueOf(face.side().name()),
                        color >> 16 & 255, color >> 8 & 255, color & 255,
                        alpha);
            }
        }
        var lines = context.consumers().getBuffer(
                LumiCompareRenderTypes.outline(false));
        for (var zone : snapshot.zones()) {
            int color = renderColor(
                    zone.color(), mode, zone.active(), zone.entered());
            float width = mode == Mode.FOCUSED
                    ? 3.0F : zone.active() ? 2.75F
                    : zone.entered() ? 2.25F : 1.25F;
            for (ZoneShellFace face : zone.faces()) {
                AABB box = box(face).move(
                        -camera.x, -camera.y, -camera.z);
                ShapeRenderer.renderShape(
                        context.matrices(), lines,
                        Shapes.create(new AABB(
                                0, 0, 0,
                                box.getXsize(), box.getYsize(), box.getZsize())),
                        box.minX, box.minY, box.minZ, color, width);
            }
        }
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

    private record RequestKey(
            String dimension,
            UUID workspace,
            Mode mode,
            int cellX,
            int cellY,
            int cellZ,
            List<ZoneRevision> zones) { }
}
