package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.model.WorkZoneShellFace;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneShellPlanner;
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
    private static final WorkZoneShellPlanner SHELL_PLANNER = new WorkZoneShellPlanner();
    private static final int REFRESH_TICKS = 1;
    private static final int FILL_ALPHA = 30;
    private static final float OUTLINE_WIDTH = 2.0F;
    private static final float OUTSET = 0.01F;
    private static State activeState;
    private static ShellState cachedShell;
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
        if (context == null || context.matrices() == null || state == null || state.faces().isEmpty()) {
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
        WorkZone renderedZone = activeZone == null ? enteredZone : activeZone;
        return new State(
                renderedZone == null ? List.of() : renderFaces(renderedZone, playerCell),
                renderedZone == null ? 0 : renderedZone.color(),
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

    private static List<WorkZoneShellFace> renderFaces(WorkZone zone, WorkZoneCell playerCell) {
        ShellKey key = ShellKey.from(zone, playerCell);
        ShellState current = cachedShell;
        if (current != null && current.key().equals(key)) {
            return current.faces();
        }
        List<WorkZoneShellFace> faces = SHELL_PLANNER.plan(zone.cells().isEmpty() ? List.of(playerCell) : zone.cells());
        cachedShell = new ShellState(key, faces);
        return faces;
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
        for (WorkZoneShellFace face : state.faces()) {
            builder.addShellFace(
                    face,
                    red,
                    green,
                    blue,
                    FILL_ALPHA,
                    outlineColor,
                    OUTLINE_WIDTH,
                    OUTSET
            );
        }
        cachedMesh = new MeshState(state.faces(), state.color(), builder.build());
        return cachedMesh;
    }

    private static void clear() {
        activeState = null;
        lastEnteredZoneId = "";
        refreshCooldown = 0;
        cachedShell = null;
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

    private record State(List<WorkZoneShellFace> faces, int color, String enteredZoneId, String enteredZoneName) {

        private State {
            faces = faces == null ? List.of() : List.copyOf(faces);
            enteredZoneId = enteredZoneId == null ? "" : enteredZoneId;
            enteredZoneName = enteredZoneName == null ? "" : enteredZoneName;
        }
    }

    private record ShellKey(String zoneId, int cellCount, String updatedAt, WorkZoneCell previewCell) {

        private static ShellKey from(WorkZone zone, WorkZoneCell playerCell) {
            WorkZoneCell preview = zone.cells().isEmpty() ? playerCell : null;
            return new ShellKey(
                    zone.id(),
                    zone.cells().size(),
                    zone.updatedAt().toString(),
                    preview
            );
        }
    }

    private record ShellState(ShellKey key, List<WorkZoneShellFace> faces) {

        private ShellState {
            faces = faces == null ? List.of() : List.copyOf(faces);
        }
    }

    private record MeshState(List<WorkZoneShellFace> faces, int color, OverlayMeshBatch batch) {

        private boolean matches(State state) {
            return this.color == state.color() && this.faces.equals(state.faces());
        }
    }
}
