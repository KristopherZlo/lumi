package io.github.luma.ui.overlay;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;

public final class CompareOverlayRenderer {

    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);

    private CompareOverlayRenderer() {
    }

    public static void show(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks) {
        ACTIVE_STATE.set(new OverlayState(leftVersionId, rightVersionId, List.copyOf(changedBlocks)));
    }

    public static void clear() {
        ACTIVE_STATE.set(null);
    }

    public static boolean active() {
        return ACTIVE_STATE.get() != null;
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null || state.changedBlocks().isEmpty()) {
            return;
        }

        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        var consumer = context.consumers().getBuffer(RenderTypes.lines());
        int limit = Math.min(MAX_RENDERED_BLOCKS, state.changedBlocks().size());
        for (int index = 0; index < limit; index++) {
            DiffBlockEntry entry = state.changedBlocks().get(index);
            ShapeRenderer.renderShape(
                    context.matrices(),
                    consumer,
                    Shapes.block(),
                    entry.pos().x() - camera.x,
                    entry.pos().y() - camera.y,
                    entry.pos().z() - camera.z,
                    color(entry.changeType()),
                    0.95F
            );
        }
    }

    private static int color(ChangeType type) {
        return switch (type) {
            case ADDED -> 0xFF55FF55;
            case REMOVED -> 0xFFFF5555;
            case CHANGED -> 0xFFFFD455;
        };
    }

    private record OverlayState(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks) {
    }
}
