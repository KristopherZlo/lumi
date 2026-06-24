package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.network.WorkZoneClientNetworking;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class WorkZoneOverlayRenderer {

    private static final ProjectService PROJECT_SERVICE = new ProjectService();
    private static final WorkZoneService WORK_ZONE_SERVICE = new WorkZoneService();
    private static final int REFRESH_TICKS = 10;
    private static final int FILL_ALPHA = 30;
    private static final float OUTLINE_WIDTH = 2.0F;
    private static final float OUTSET = 0.01F;
    private static State activeState;
    private static MeshState cachedMesh;
    private static String lastEnteredZoneId = "";
    private static int refreshCooldown;

    private WorkZoneOverlayRenderer() {
    }

    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            clear();
            return;
        }
        if (--refreshCooldown > 0) {
            return;
        }
        refreshCooldown = REFRESH_TICKS;

        State next = client.hasSingleplayerServer() ? resolve(client) : resolveRemote(client);
        activeState = next;
        String enteredZoneId = next == null ? "" : next.enteredZoneId();
        if (!enteredZoneId.equals(lastEnteredZoneId)) {
            lastEnteredZoneId = enteredZoneId;
            if (!enteredZoneId.isBlank()) {
                client.gui.setOverlayMessage(ActionBarMessagePresenter.zoneEntered(next.enteredZoneName()), false);
            }
        }
    }

    public static void render(WorldRenderContext context) {
        State state = activeState;
        if (context == null || context.matrices() == null || state == null || state.boxes().isEmpty()) {
            clearCachedMesh();
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        mesh(state).batch().render(
                CompareOverlayRenderTypes.fill(false),
                CompareOverlayRenderTypes.outline(false),
                camera,
                renderDistanceChunks(),
                1
        );
    }

    private static State resolve(Minecraft client) {
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(client);
            ServerLevel level = server.getLevel(client.level.dimension());
            if (level == null) {
                return null;
            }
            BuildProject project = PROJECT_SERVICE.findWorldProject(level).orElse(null);
            if (project == null) {
                return null;
            }

            WorkZoneState zones = WORK_ZONE_SERVICE.load(PROJECT_SERVICE.resolveLayout(server, project.name()));
            String actor = client.getUser() == null ? "player" : client.getUser().getName();
            return stateFrom(zones, actor, WorkZoneCell.from(BlockPoint.from(client.player.blockPosition())));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static State resolveRemote(Minecraft client) {
        WorkZoneSnapshot snapshot = WorkZoneClientNetworking.getInstance().state("");
        if (snapshot == null || snapshot.project() == null || client.player == null) {
            return null;
        }
        return stateFrom(snapshot.zones(), snapshot.actor(), WorkZoneCell.from(BlockPoint.from(client.player.blockPosition())));
    }

    private static State stateFrom(WorkZoneState zones, String actor, WorkZoneCell playerCell) {
        WorkZone activeZone = zones.zones().stream()
                .filter(zone -> zone.id().equals(zones.activeZoneId(actor)))
                .findFirst()
                .orElse(null);
        WorkZone enteredZone = enteredZone(zones.zones(), activeZone, playerCell);
        return new State(
                activeZone == null ? List.of() : renderBoxes(activeZone, playerCell),
                activeZone == null ? 0 : activeZone.color(),
                enteredZone == null ? "" : enteredZone.id(),
                enteredZone == null ? "" : enteredZone.name()
        );
    }

    private static WorkZone enteredZone(List<WorkZone> zones, WorkZone activeZone, WorkZoneCell playerCell) {
        if (activeZone != null && activeZone.contains(playerCell)) {
            return activeZone;
        }
        return zones.stream()
                .filter(zone -> zone.contains(playerCell))
                .findFirst()
                .orElse(null);
    }

    private static List<Bounds3i> renderBoxes(WorkZone zone, WorkZoneCell playerCell) {
        if (zone.cells().isEmpty()) {
            return List.of(cellBounds(playerCell));
        }
        if (!zone.contains(playerCell)) {
            return List.of();
        }
        return zone.cells().stream().map(WorkZoneOverlayRenderer::cellBounds).toList();
    }

    private static Bounds3i cellBounds(WorkZoneCell cell) {
        int x = cell.x() * WorkZoneCell.SIZE;
        int y = cell.y() * WorkZoneCell.SIZE;
        int z = cell.z() * WorkZoneCell.SIZE;
        return new Bounds3i(new BlockPoint(x, y, z), new BlockPoint(x + 15, y + 15, z + 15));
    }

    private static MeshState mesh(State state) {
        MeshState current = cachedMesh;
        if (current != null && current.matches(state)) {
            return current;
        }
        clearCachedMesh();
        int red = state.color() >> 16 & 0xFF;
        int green = state.color() >> 8 & 0xFF;
        int blue = state.color() & 0xFF;
        int outlineColor = 0xFF000000 | state.color();
        OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
        for (Bounds3i box : state.boxes()) {
            builder.addBox(
                    box.min().x(),
                    box.min().y(),
                    box.min().z(),
                    box.max().x() + 1,
                    box.max().y() + 1,
                    box.max().z() + 1,
                    red,
                    green,
                    blue,
                    FILL_ALPHA,
                    outlineColor,
                    OUTLINE_WIDTH,
                    OUTSET,
                    OUTSET
            );
        }
        cachedMesh = new MeshState(state.boxes(), state.color(), builder.build());
        return cachedMesh;
    }

    private static void clear() {
        activeState = null;
        lastEnteredZoneId = "";
        refreshCooldown = 0;
        clearCachedMesh();
    }

    private static void clearCachedMesh() {
        if (cachedMesh != null) {
            cachedMesh.batch().close();
            cachedMesh = null;
        }
    }

    private static int renderDistanceChunks() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.options == null ? 8 : client.options.getEffectiveRenderDistance();
    }

    private record State(List<Bounds3i> boxes, int color, String enteredZoneId, String enteredZoneName) {

        private State {
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
            enteredZoneId = enteredZoneId == null ? "" : enteredZoneId;
            enteredZoneName = enteredZoneName == null ? "" : enteredZoneName;
        }
    }

    private record MeshState(List<Bounds3i> boxes, int color, OverlayMeshBatch batch) {

        private boolean matches(State state) {
            return this.color == state.color() && this.boxes.equals(state.boxes());
        }
    }
}
