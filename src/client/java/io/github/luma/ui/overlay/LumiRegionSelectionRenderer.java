package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

public final class LumiRegionSelectionRenderer {

    private static final ProjectService PROJECT_SERVICE = new ProjectService();
    private static final float OUTSET = 0.01F;
    private static final int RED = 0x35;
    private static final int GREEN = 0xC6;
    private static final int BLUE = 0xFF;
    private static final int FILL_ALPHA = 42;
    private static final int OUTLINE_COLOR = 0xFF35C6FF;
    private static final float OUTLINE_WIDTH = 2.5F;

    private LumiRegionSelectionRenderer() {
    }

    public static void render(WorldRenderContext context) {
        if (context == null || context.matrices() == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Optional<BuildProject> project = currentProject(client);
        if (project.isEmpty()) {
            return;
        }
        Optional<Bounds3i> bounds = LumiRegionSelectionController.getInstance().selectedBounds(
                project.get().name(),
                project.get().dimensionId()
        );
        if (bounds.isEmpty()) {
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
        float minX = (float) (bounds.min().x() - camera.x) - OUTSET;
        float minY = (float) (bounds.min().y() - camera.y) - OUTSET;
        float minZ = (float) (bounds.min().z() - camera.z) - OUTSET;
        float maxX = (float) (bounds.max().x() + 1.0D - camera.x) + OUTSET;
        float maxY = (float) (bounds.max().y() + 1.0D - camera.y) + OUTSET;
        float maxZ = (float) (bounds.max().z() + 1.0D - camera.z) + OUTSET;

        var fillType = CompareOverlayRenderTypes.fill(false);
        var fillBuffer = OverlayImmediateRenderer.begin(fillType);
        addBoxFaces(context.matrices(), fillBuffer, minX, minY, minZ, maxX, maxY, maxZ);
        OverlayImmediateRenderer.draw(fillType, fillBuffer);

        var outlineType = CompareOverlayRenderTypes.outline(false);
        var lineBuffer = OverlayImmediateRenderer.begin(outlineType);
        ShapeRenderer.renderShape(
                context.matrices(),
                lineBuffer,
                Shapes.create(new AABB(0.0D, 0.0D, 0.0D, maxX - minX, maxY - minY, maxZ - minZ)),
                minX,
                minY,
                minZ,
                OUTLINE_COLOR,
                OUTLINE_WIDTH
        );
        OverlayImmediateRenderer.draw(outlineType, lineBuffer);
    }

    private static void addBoxFaces(
            PoseStack matrices,
            VertexConsumer consumer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        PoseStack.Pose pose = matrices.last();
        addQuad(pose, consumer, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
        addQuad(pose, consumer, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        addQuad(pose, consumer, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
        addQuad(pose, consumer, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        addQuad(pose, consumer, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
        addQuad(pose, consumer, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
    }

    private static void addQuad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4
    ) {
        consumer.addVertex(pose, x1, y1, z1).setColor(RED, GREEN, BLUE, FILL_ALPHA);
        consumer.addVertex(pose, x2, y2, z2).setColor(RED, GREEN, BLUE, FILL_ALPHA);
        consumer.addVertex(pose, x3, y3, z3).setColor(RED, GREEN, BLUE, FILL_ALPHA);
        consumer.addVertex(pose, x4, y4, z4).setColor(RED, GREEN, BLUE, FILL_ALPHA);
    }
}
