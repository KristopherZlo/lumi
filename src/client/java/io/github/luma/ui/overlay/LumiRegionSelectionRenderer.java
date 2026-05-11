package io.github.luma.ui.overlay;

import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class LumiRegionSelectionRenderer {

    private static final ProjectService PROJECT_SERVICE = new ProjectService();
    private static final float OUTSET = 0.01F;
    private static final int RED = 0x35;
    private static final int GREEN = 0xC6;
    private static final int BLUE = 0xFF;
    private static final int FILL_ALPHA = 42;
    private static final int OUTLINE_COLOR = 0xFF35C6FF;
    private static final float OUTLINE_WIDTH = 2.5F;
    private static SelectionMesh cachedMesh;

    private LumiRegionSelectionRenderer() {
    }

    public static void render(WorldRenderContext context) {
        if (context == null || context.matrices() == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!LumiRegionSelectionController.getInstance().shouldRenderSelection(client)) {
            clearCachedMesh();
            return;
        }
        Optional<BuildProject> project = currentProject(client);
        if (project.isEmpty()) {
            clearCachedMesh();
            return;
        }
        Optional<Bounds3i> bounds = LumiRegionSelectionController.getInstance().selectedBounds(
                project.get().name(),
                project.get().dimensionId()
        );
        if (bounds.isEmpty()) {
            clearCachedMesh();
            return;
        }
        renderBounds(context, bounds.get());
    }

    private static Optional<BuildProject> currentProject(Minecraft client) {
        if (client == null || client.level == null || !client.hasSingleplayerServer()) {
            return Optional.empty();
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(client);
            ServerLevel level = server.getLevel(client.level.dimension());
            return level == null ? Optional.empty() : PROJECT_SERVICE.findWorldProject(level);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static void renderBounds(WorldRenderContext context, Bounds3i bounds) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        selectionMesh(bounds).batch().render(
                CompareOverlayRenderTypes.fill(false),
                CompareOverlayRenderTypes.outline(false),
                camera,
                renderDistanceChunks(),
                1
        );
    }

    private static SelectionMesh selectionMesh(Bounds3i bounds) {
        SelectionMesh current = cachedMesh;
        if (current != null && current.bounds().equals(bounds)) {
            return current;
        }
        clearCachedMesh();
        OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
        builder.addBox(
                bounds.min().x(),
                bounds.min().y(),
                bounds.min().z(),
                bounds.max().x() + 1,
                bounds.max().y() + 1,
                bounds.max().z() + 1,
                RED,
                GREEN,
                BLUE,
                FILL_ALPHA,
                OUTLINE_COLOR,
                OUTLINE_WIDTH,
                OUTSET,
                OUTSET
        );
        cachedMesh = new SelectionMesh(bounds, builder.build());
        return cachedMesh;
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

    private record SelectionMesh(Bounds3i bounds, OverlayMeshBatch batch) {
    }
}
