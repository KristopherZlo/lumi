package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuilderChangeSurfacePolicy;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class RecentChangesOverlayRenderer {

    private static final int MAX_SECTION_UPLOADS_PER_FRAME = 12;
    private static final int MAX_SECTION_DRAWS_PER_FRAME = 64;
    private static final int MAX_ACTIONS = 10;
    private static final int BASE_ALPHA = 136;
    private static final int ALPHA_STEP = 12;
    private static final float FILL_ALPHA_SCALE = 0.38F;
    private static final int MIN_FILL_ALPHA = 24;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float FACE_OUTSET = 0.003F;
    static final int RECENT_ACTION_OUTLINE = 0xFFFF9C3A;
    static final int UNDO_TARGET_OUTLINE = 0xFFFF5A5A;
    static final int REDO_TARGET_OUTLINE = 0xFF4ADE80;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);
    private static final BuilderChangeSurfacePolicy BUILDER_SURFACE = new BuilderChangeSurfacePolicy();

    private RecentChangesOverlayRenderer() {
    }

    public static void show(String projectId, List<UndoRedoAction> actions) {
        show(projectId, actions, LumaDebugLog.globalEnabled(), RecentChangesOverlayCoordinator.PreviewTarget.UNDO);
    }

    public static void show(
            String projectId,
            List<UndoRedoAction> actions,
            boolean debugEnabled,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        activate(prepare(
                RecentChangesOverlaySnapshot.forTarget(projectId, -1L, actions, previewTarget),
                debugEnabled,
                previewTarget
        ));
    }

    static PreparedOverlay prepare(
            String projectId,
            List<UndoRedoAction> actions,
            boolean debugEnabled,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            long revision
    ) {
        return prepare(
                RecentChangesOverlaySnapshot.forTarget(projectId, revision, actions, previewTarget),
                debugEnabled,
                previewTarget
        );
    }

    static PreparedOverlay prepare(
            RecentChangesOverlaySnapshot snapshot,
            boolean debugEnabled,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        RecentChangesOverlaySnapshot resolved = snapshot == null
                ? new RecentChangesOverlaySnapshot("", -1L, List.of(), List.of())
                : snapshot;
        List<RecentChangeEntry> entries = flatten(resolved, previewTarget);
        if (entries.isEmpty()) {
            return new PreparedOverlay(
                    resolved.projectId(),
                    resolved.revision(),
                    previewTarget,
                    resolved.actionCount(),
                    0,
                    0,
                    0,
                    debugEnabled,
                    null
            );
        }

        OverlayState state = new OverlayState(
                resolved.projectId(),
                resolved.revision(),
                previewTarget,
                entries,
                debugEnabled
        );
        return new PreparedOverlay(
                resolved.projectId(),
                resolved.revision(),
                previewTarget,
                resolved.actionCount(),
                entries.size(),
                state.surfaceEntryCount(),
                state.volumeBoxCount(),
                debugEnabled,
                state
        );
    }

    static void activate(PreparedOverlay prepared) {
        if (prepared == null) {
            ACTIVE_STATE.set(null);
            return;
        }
        OverlayState state = prepared.state();
        closePrevious(ACTIVE_STATE.getAndSet(state));
        OverlayDiagnostics.getInstance().log(
                prepared.debugEnabled(),
                "recent-show",
                "recent-overlay",
                "Loaded recent overlay project={} preview={} revision={} actions={} entries={} surfaceEntries={} volumeBoxes={} meshSections={}",
                prepared.projectId(),
                prepared.previewTarget(),
                prepared.revision(),
                prepared.actionCount(),
                prepared.entryCount(),
                prepared.surfaceEntryCount(),
                prepared.volumeBoxCount(),
                state == null ? 0 : state.meshSectionCount()
        );
    }

    public static void clear() {
        closePrevious(ACTIVE_STATE.getAndSet(null));
    }

    public static boolean visible() {
        return ACTIVE_STATE.get() != null;
    }

    static boolean visibleFor(String projectId, long revision, RecentChangesOverlayCoordinator.PreviewTarget previewTarget) {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.matches(projectId, revision, previewTarget);
    }

    static int visibleSurfaceEntryCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.surfaceEntryCount();
    }

    static int visibleAggregateBoxCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.volumeBoxCount();
    }

    static int meshSectionCountForTest() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.meshSectionCount();
    }

    static int meshPrimitiveCountForTest() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.meshPrimitiveCount();
    }

    static int visibleMeshSectionCountForTest(
            double cameraX,
            double cameraY,
            double cameraZ,
            int renderDistanceChunks
    ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null
                ? 0
                : state.visibleMeshSectionCount(cameraX, cameraY, cameraZ, renderDistanceChunks);
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "recent-skip-no-state",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "no-state",
                    0,
                    0
            );
            return;
        }
        if (state.entries().isEmpty()) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-empty",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "empty",
                    0,
                    state.surfaceEntryCount()
            );
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (RuntimeException exception) {
            OverlayDiagnostics.getInstance().logNow(
                    state.debugEnabled(),
                    "recent-overlay",
                    "Render failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            if (ACTIVE_STATE.compareAndSet(state, null)) {
                state.close();
            }
            LumaMod.LOGGER.warn("Disabled recent changes overlay after a render pipeline failure", exception);
        }
    }

    private static void closePrevious(OverlayState state) {
        if (state != null) {
            state.close();
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        if (context == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-null-context",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "null-context",
                    state.entries().size(),
                    state.surfaceEntryCount()
            );
            return;
        }
        var matrices = context.matrices();
        if (matrices == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-null-matrices",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "null-matrices",
                    state.entries().size(),
                    state.surfaceEntryCount()
            );
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        OverlayMeshBatch.RenderStats renderStats = state.meshBatch().render(
                CompareOverlayRenderTypes.fill(false),
                CompareOverlayRenderTypes.outline(false),
                camera,
                renderDistanceChunks(),
                MAX_SECTION_UPLOADS_PER_FRAME,
                MAX_SECTION_DRAWS_PER_FRAME
        );
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "recent-frame",
                "recent-overlay",
                "Render frame entries={} surfaceEntries={} volumeBoxes={} meshSections={}/{} uploaded={} skipped={} denseBlob={} camera={}:{}:{}",
                state.entries().size(),
                state.surfaceEntryCount(),
                state.volumeBoxCount(),
                renderStats.filledSections(),
                renderStats.totalSections(),
                renderStats.uploadedSections(),
                renderStats.skippedSections(),
                state.denseBlob(),
                camera.x,
                camera.y,
                camera.z
        );
    }

    private static int renderDistanceChunks() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.options == null ? 8 : client.options.getEffectiveRenderDistance();
    }

    static List<Integer> outlineColorsForTest(
            List<UndoRedoAction> actions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        return flatten(RecentChangesOverlaySnapshot.forTarget("", -1L, actions, previewTarget), previewTarget).stream()
                .map(RecentChangeEntry::outlineArgb)
                .toList();
    }

    static List<Integer> outlineColorsForTest(
            List<UndoRedoAction> undoActions,
            List<UndoRedoAction> redoActions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        return flatten(new RecentChangesOverlaySnapshot("", -1L, undoActions, redoActions), previewTarget).stream()
                .map(RecentChangeEntry::outlineArgb)
                .toList();
    }

    static List<BlockPoint> previewPositionsForTest(
            List<UndoRedoAction> actions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        return flatten(RecentChangesOverlaySnapshot.forTarget("", -1L, actions, previewTarget), previewTarget).stream()
                .map(RecentChangeEntry::pos)
                .toList();
    }

    static List<BlockPoint> previewPositionsForTest(
            List<UndoRedoAction> undoActions,
            List<UndoRedoAction> redoActions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        return flatten(new RecentChangesOverlaySnapshot("", -1L, undoActions, redoActions), previewTarget).stream()
                .map(RecentChangeEntry::pos)
                .toList();
    }

    private static List<RecentChangeEntry> flatten(
            RecentChangesOverlaySnapshot snapshot,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        if (snapshot == null) {
            return List.of();
        }

        LinkedHashMap<Long, RecentChangeEntry> flattened = new LinkedHashMap<>();
        if (previewTarget == RecentChangesOverlayCoordinator.PreviewTarget.REDO) {
            flattenActions(flattened, snapshot.redoActions(), RecentChangesOverlayCoordinator.PreviewTarget.REDO);
        } else if (previewTarget == RecentChangesOverlayCoordinator.PreviewTarget.BOTH) {
            flattenActions(flattened, snapshot.undoActions(), RecentChangesOverlayCoordinator.PreviewTarget.UNDO);
            flattenActions(flattened, snapshot.redoActions(), RecentChangesOverlayCoordinator.PreviewTarget.REDO);
        } else {
            flattenActions(flattened, snapshot.undoActions(), RecentChangesOverlayCoordinator.PreviewTarget.UNDO);
        }
        return List.copyOf(flattened.values());
    }

    private static void flattenActions(
            LinkedHashMap<Long, RecentChangeEntry> flattened,
            List<UndoRedoAction> actions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        if (actions == null || actions.isEmpty()) {
            return;
        }

        int actionIndex = 0;
        for (UndoRedoAction action : actions) {
            if (action == null || action.isEmpty()) {
                continue;
            }
            int alpha = Math.max(ALPHA_STEP, BASE_ALPHA - (actionIndex * ALPHA_STEP));
            int outlineArgb = outlineArgb(actionIndex, previewTarget);
            int red = (outlineArgb >> 16) & 0xFF;
            int green = (outlineArgb >> 8) & 0xFF;
            int blue = outlineArgb & 0xFF;
            for (var change : previewChanges(action, previewTarget)) {
                long key = net.minecraft.core.BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
                RecentChangeEntry entry = new RecentChangeEntry(
                        change.pos(),
                        alpha,
                        red,
                        green,
                        blue,
                        outlineArgb,
                        entryPriority(actionIndex)
                );
                flattened.merge(key, entry, RecentChangesOverlayRenderer::selectVisibleEntry);
            }
            actionIndex += 1;
            if (actionIndex >= MAX_ACTIONS) {
                break;
            }
        }
    }

    private static List<StoredBlockChange> previewChanges(
            UndoRedoAction action,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        if (previewTarget == RecentChangesOverlayCoordinator.PreviewTarget.REDO) {
            return visibleChanges(action.redoChanges());
        }
        return visibleChanges(action.undoChanges());
    }

    private static List<StoredBlockChange> visibleChanges(List<StoredBlockChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return BUILDER_SURFACE.visibleBlockChanges(changes);
    }

    private static int outlineArgb(
            int actionIndex,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        if (actionIndex != 0) {
            return RECENT_ACTION_OUTLINE;
        }
        return previewTarget == RecentChangesOverlayCoordinator.PreviewTarget.REDO
                ? REDO_TARGET_OUTLINE
                : UNDO_TARGET_OUTLINE;
    }

    private static int entryPriority(int actionIndex) {
        return actionIndex == 0 ? 2 : 1;
    }

    private static RecentChangeEntry selectVisibleEntry(RecentChangeEntry current, RecentChangeEntry candidate) {
        return candidate.priority() > current.priority() ? candidate : current;
    }

    private record RecentChangeEntry(
            BlockPoint pos,
            int alpha,
            int red,
            int green,
            int blue,
            int outlineArgb,
            int priority
    ) {
    }

    private record SurfaceEntry(
            RecentChangeEntry entry,
            CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock) {
    }

    record PreparedOverlay(
            String projectId,
            long revision,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            int actionCount,
            int entryCount,
            int surfaceEntryCount,
            int volumeBoxCount,
            boolean debugEnabled,
            OverlayState state) {
    }

    private static final class OverlayState {

        private final String projectId;
        private final long revision;
        private final RecentChangesOverlayCoordinator.PreviewTarget previewTarget;
        private final List<RecentChangeEntry> entries;
        private final List<SurfaceEntry> surfaceEntries;
        private final OverlayMeshBatch meshBatch;
        private final boolean debugEnabled;

        private OverlayState(
                String projectId,
                long revision,
                RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
                List<RecentChangeEntry> entries,
                boolean debugEnabled
        ) {
            this.projectId = projectId;
            this.revision = revision;
            this.previewTarget = previewTarget == null
                    ? RecentChangesOverlayCoordinator.PreviewTarget.UNDO
                    : previewTarget;
            this.entries = List.copyOf(entries);
            this.debugEnabled = debugEnabled;
            this.surfaceEntries = this.buildSurfaceEntries(this.entries);
            this.meshBatch = this.buildMeshBatch();
        }

        private List<RecentChangeEntry> entries() {
            return this.entries;
        }

        private boolean debugEnabled() {
            return this.debugEnabled;
        }

        private boolean denseBlob() {
            return false;
        }

        private boolean matches(
                String projectId,
                long revision,
                RecentChangesOverlayCoordinator.PreviewTarget previewTarget
        ) {
            return java.util.Objects.equals(this.projectId, projectId)
                    && this.revision == revision
                    && this.previewTarget == (previewTarget == null
                    ? RecentChangesOverlayCoordinator.PreviewTarget.UNDO
                    : previewTarget);
        }

        private int surfaceEntryCount() {
            return this.surfaceEntries.size();
        }

        private int volumeBoxCount() {
            return 0;
        }

        private int meshSectionCount() {
            return this.meshBatch.sectionCount();
        }

        private int meshPrimitiveCount() {
            return this.meshBatch.primitiveCountForTest();
        }

        private int visibleMeshSectionCount(
                double cameraX,
                double cameraY,
                double cameraZ,
                int renderDistanceChunks
        ) {
            return this.meshBatch.visibleSectionCountForTest(
                    cameraX,
                    cameraY,
                    cameraZ,
                    renderDistanceChunks
            );
        }

        private OverlayMeshBatch meshBatch() {
            return this.meshBatch;
        }

        private void close() {
            this.meshBatch.close();
        }

        private OverlayMeshBatch buildMeshBatch() {
            OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
            for (SurfaceEntry surfaceEntry : this.surfaceEntries) {
                int fillAlpha = Math.max(MIN_FILL_ALPHA, Math.round(surfaceEntry.entry().alpha() * FILL_ALPHA_SCALE));
                builder.addSurfaceBlock(
                        surfaceEntry.surfaceBlock(),
                        surfaceEntry.entry().red(),
                        surfaceEntry.entry().green(),
                        surfaceEntry.entry().blue(),
                        fillAlpha,
                        surfaceEntry.entry().outlineArgb(),
                        OUTLINE_WIDTH,
                        FACE_OUTSET
                );
            }
            return builder.build();
        }

        private List<SurfaceEntry> buildSurfaceEntries(List<RecentChangeEntry> entries) {
            List<io.github.luma.domain.model.DiffBlockEntry> diffEntries = entries.stream()
                    .map(entry -> new io.github.luma.domain.model.DiffBlockEntry(
                            entry.pos(),
                            "",
                            "",
                            io.github.luma.domain.model.ChangeType.CHANGED))
                    .toList();
            Set<Long> occupiedPositions = SURFACE_RESOLVER.indexPositions(diffEntries);
            Map<Long, RecentChangeEntry> entriesByPosition = new LinkedHashMap<>();
            for (RecentChangeEntry entry : entries) {
                entriesByPosition.put(BlockPos.asLong(entry.pos().x(), entry.pos().y(), entry.pos().z()), entry);
            }
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks = SURFACE_RESOLVER.resolve(
                    diffEntries,
                    occupiedPositions
            );
            List<SurfaceEntry> resolved = new ArrayList<>(surfaceBlocks.size());
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                RecentChangeEntry entry = entriesByPosition.get(BlockPos.asLong(
                        surfaceBlock.entry().pos().x(),
                        surfaceBlock.entry().pos().y(),
                        surfaceBlock.entry().pos().z()
                ));
                if (entry != null) {
                    resolved.add(new SurfaceEntry(entry, surfaceBlock));
                }
            }
            return List.copyOf(resolved);
        }
    }

}
