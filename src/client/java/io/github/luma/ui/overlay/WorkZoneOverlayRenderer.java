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
import io.github.luma.storage.ProjectLayout;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

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
    private static CachedProjectLayout cachedProjectLayout;
    private static String lastEnteredZoneId = "";
    private static int refreshCooldown;
    private static DisplayMode displayMode = DisplayMode.FOCUSED;

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
        if (displayMode == DisplayMode.HIDDEN
                || context == null
                || context.matrices() == null
                || state == null
                || state.faces().isEmpty()) {
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

    public static boolean showAllZones() {
        return displayMode == DisplayMode.ALL;
    }

    public static void toggleShowAllZones() {
        cycleDisplayMode();
    }

    public static DisplayMode displayMode() {
        return displayMode;
    }

    public static void cycleDisplayMode() {
        displayMode = switch (displayMode) {
            case FOCUSED -> DisplayMode.ALL;
            case ALL -> DisplayMode.HIDDEN;
            case HIDDEN -> DisplayMode.FOCUSED;
        };
        clearCachedMesh();
    }

    private static State resolve(Minecraft client) {
        try {
            BuildProject project = ClientProjectAccess.findCurrentWorldProject(client).orElse(null);
            MinecraftServer server = client.getSingleplayerServer();
            if (project == null) {
                cachedProjectLayout = null;
                return null;
            }

            WorkZoneState zones = WORK_ZONE_SERVICE.load(projectLayout(server, project));
            String actor = client.getUser() == null ? "player" : client.getUser().getName();
            return stateFrom(zones, actor, WorkZoneCell.from(BlockPoint.from(client.player.blockPosition())));
        } catch (Exception ignored) {
            cachedProjectLayout = null;
            return null;
        }
    }

    private static ProjectLayout projectLayout(MinecraftServer server, BuildProject project) throws java.io.IOException {
        CachedProjectLayout current = cachedProjectLayout;
        if (current != null && current.server() == server && current.project() == project) {
            return current.layout();
        }
        ProjectLayout layout = PROJECT_SERVICE.resolveLayout(server, project.name());
        cachedProjectLayout = new CachedProjectLayout(server, project, layout);
        return layout;
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
        if (displayMode == DisplayMode.ALL) {
            return new State(
                    renderFaces(zones.zones()),
                    enteredZone == null ? "" : enteredZone.id(),
                    enteredZone == null ? "" : enteredZone.name()
            );
        }
        WorkZone renderedZone = activeZone == null ? enteredZone : activeZone;
        return new State(
                renderedZone == null ? List.of() : renderFaces(renderedZone, playerCell).stream()
                        .map(face -> new RenderedFace(face, renderedZone.color()))
                        .toList(),
                enteredZone == null ? "" : enteredZone.id(),
                enteredZone == null ? "" : enteredZone.name()
        );
    }

    public enum DisplayMode {
        FOCUSED,
        ALL,
        HIDDEN
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

    private static List<RenderedFace> renderFaces(List<WorkZone> zones) {
        return zones.stream()
                .filter(zone -> !zone.cells().isEmpty())
                .flatMap(zone -> renderFaces(zone, null).stream()
                        .map(face -> new RenderedFace(face, zone.color())))
                .toList();
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
        OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
        for (RenderedFace renderedFace : state.faces()) {
            int color = renderedFace.color();
            builder.addShellFace(
                    renderedFace.face(),
                    color >> 16 & 0xFF,
                    color >> 8 & 0xFF,
                    color & 0xFF,
                    FILL_ALPHA,
                    0xFF000000 | color,
                    OUTLINE_WIDTH,
                    OUTSET
            );
        }
        cachedMesh = new MeshState(state.faces(), builder.build());
        return cachedMesh;
    }

    private static void clear() {
        activeState = null;
        lastEnteredZoneId = "";
        refreshCooldown = 0;
        cachedShell = null;
        cachedProjectLayout = null;
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

    private record State(List<RenderedFace> faces, String enteredZoneId, String enteredZoneName) {

        private State {
            faces = faces == null ? List.of() : List.copyOf(faces);
            enteredZoneId = enteredZoneId == null ? "" : enteredZoneId;
            enteredZoneName = enteredZoneName == null ? "" : enteredZoneName;
        }
    }

    private record RenderedFace(WorkZoneShellFace face, int color) {
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

    private record MeshState(List<RenderedFace> faces, OverlayMeshBatch batch) {

        private boolean matches(State state) {
            return this.faces.equals(state.faces());
        }
    }

    private record CachedProjectLayout(MinecraftServer server, BuildProject project, ProjectLayout layout) {
    }
}
