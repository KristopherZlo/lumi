package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.UndoRedoAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;

public final class RecentChangesOverlayRenderer {

    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final int MAX_ACTIONS = 10;
    private static final int BASE_ALPHA = 80;
    private static final int ALPHA_STEP = 8;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);

    private RecentChangesOverlayRenderer() {
    }

    public static void show(String projectId, List<UndoRedoAction> actions) {
        List<RecentChangeEntry> entries = flatten(actions);
        ACTIVE_STATE.set(entries.isEmpty() ? null : new OverlayState(projectId, entries));
    }

    public static void clear() {
        ACTIVE_STATE.set(null);
    }

    public static boolean visible() {
        return ACTIVE_STATE.get() != null;
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null || state.entries().isEmpty()) {
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (IllegalStateException exception) {
            ACTIVE_STATE.compareAndSet(state, null);
            LumaMod.LOGGER.warn("Disabled recent changes overlay after a render pipeline failure", exception);
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        VertexConsumer lineConsumer = context.consumers().getBuffer(CompareOverlayRenderTypes.outline(false));
        for (SurfaceEntry surfaceEntry : state.visibleSurfaceEntries(camera.x, camera.y, camera.z)) {
            RecentChangeEntry entry = surfaceEntry.entry();
            ShapeRenderer.renderShape(
                    context.matrices(),
                    lineConsumer,
                    Shapes.block(),
                    entry.pos().x() - camera.x,
                    entry.pos().y() - camera.y,
                    entry.pos().z() - camera.z,
                    0xFFFF9C3A,
                    Math.max(0.08F, entry.alpha() / 255.0F));
        }
    }

    private static List<RecentChangeEntry> flatten(List<UndoRedoAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Long, RecentChangeEntry> flattened = new LinkedHashMap<>();
        int actionIndex = 0;
        for (UndoRedoAction action : actions) {
            if (action == null || action.isEmpty()) {
                continue;
            }
            int alpha = Math.max(ALPHA_STEP, BASE_ALPHA - (actionIndex * ALPHA_STEP));
            for (var change : action.redoChanges()) {
                long key = net.minecraft.core.BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
                flattened.putIfAbsent(key, new RecentChangeEntry(change.pos(), alpha));
            }
            actionIndex += 1;
            if (actionIndex >= MAX_ACTIONS) {
                break;
            }
        }
        return List.copyOf(flattened.values());
    }

    private static List<RecentChangeEntry> selectNearestEntries(
            List<RecentChangeEntry> entries,
            double cameraX,
            double cameraY,
            double cameraZ) {
        if (entries.isEmpty()) {
            return List.of();
        }

        PriorityQueue<RankedEntry> selected = new PriorityQueue<>(
                MAX_RENDERED_BLOCKS,
                Comparator.comparingDouble(RankedEntry::distanceSquared).reversed());
        for (RecentChangeEntry entry : entries) {
            double distanceSquared = distanceSquared(entry, cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RENDERED_BLOCKS) {
                selected.add(new RankedEntry(entry, distanceSquared));
                continue;
            }

            RankedEntry farthest = selected.peek();
            if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                selected.poll();
                selected.add(new RankedEntry(entry, distanceSquared));
            }
        }

        List<RankedEntry> ranked = new ArrayList<>(selected);
        ranked.sort(Comparator.comparingDouble(RankedEntry::distanceSquared));
        List<RecentChangeEntry> result = new ArrayList<>(ranked.size());
        for (RankedEntry entry : ranked) {
            result.add(entry.entry());
        }
        return List.copyOf(result);
    }

    private static double distanceSquared(RecentChangeEntry entry, double cameraX, double cameraY, double cameraZ) {
        double dx = (entry.pos().x() + 0.5D) - cameraX;
        double dy = (entry.pos().y() + 0.5D) - cameraY;
        double dz = (entry.pos().z() + 0.5D) - cameraZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private record RankedEntry(RecentChangeEntry entry, double distanceSquared) {
    }

    private record RecentChangeEntry(BlockPoint pos, int alpha) {
    }

    private record SurfaceEntry(
            RecentChangeEntry entry) {
    }

    private static final class OverlayState {

        private final String projectId;
        private final List<RecentChangeEntry> entries;
        private final Set<Long> occupiedPositions;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private List<SurfaceEntry> cachedVisibleEntries = List.of();

        private OverlayState(String projectId, List<RecentChangeEntry> entries) {
            this.projectId = projectId;
            this.entries = List.copyOf(entries);
            this.occupiedPositions = SURFACE_RESOLVER.indexPositions(entries.stream()
                    .map(entry -> new io.github.luma.domain.model.DiffBlockEntry(entry.pos(), "", "",
                            io.github.luma.domain.model.ChangeType.CHANGED))
                    .toList());
        }

        private List<RecentChangeEntry> entries() {
            return this.entries;
        }

        private synchronized List<SurfaceEntry> visibleSurfaceEntries(double cameraX, double cameraY, double cameraZ) {
            int cameraBlockX = Mth.floor(cameraX);
            int cameraBlockY = Mth.floor(cameraY);
            int cameraBlockZ = Mth.floor(cameraZ);
            if (cameraBlockX == this.cachedCameraBlockX
                    && cameraBlockY == this.cachedCameraBlockY
                    && cameraBlockZ == this.cachedCameraBlockZ) {
                return this.cachedVisibleEntries;
            }

            this.cachedCameraBlockX = cameraBlockX;
            this.cachedCameraBlockY = cameraBlockY;
            this.cachedCameraBlockZ = cameraBlockZ;
            List<RecentChangeEntry> nearestEntries = selectNearestEntries(this.entries, cameraX, cameraY, cameraZ);
            List<io.github.luma.domain.model.DiffBlockEntry> diffEntries = nearestEntries.stream()
                    .map(entry -> new io.github.luma.domain.model.DiffBlockEntry(
                            entry.pos(),
                            "",
                            "",
                            io.github.luma.domain.model.ChangeType.CHANGED))
                    .toList();
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks = SURFACE_RESOLVER.resolve(diffEntries,
                    this.occupiedPositions);
            List<SurfaceEntry> resolved = new ArrayList<>(surfaceBlocks.size());
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                RecentChangeEntry entry = this.findEntry(surfaceBlock.entry().pos());
                if (entry == null) {
                    continue;
                }
                resolved.add(new SurfaceEntry(entry));
            }
            this.cachedVisibleEntries = List.copyOf(resolved);
            return this.cachedVisibleEntries;
        }

        private RecentChangeEntry findEntry(BlockPoint pos) {
            for (RecentChangeEntry entry : this.entries) {
                if (entry.pos().equals(pos)) {
                    return entry;
                }
            }
            return null;
        }
    }
}
