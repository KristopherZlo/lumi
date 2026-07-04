package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Loads and exposes the recent action overlay while the configured action key is held.
 */
public final class RecentChangesOverlayCoordinator {

    private static final RecentChangesOverlayCoordinator INSTANCE = new RecentChangesOverlayCoordinator();
    private static final int PREVIEW_ACTION_COUNT = 10;

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumi-recent-overlay-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final RecentChangesPreviewSession previewSession = new RecentChangesPreviewSession();
    private volatile RecentChangesPreviewSession.PreviewKey requestedPreview;
    private volatile RecentChangesPreviewSession.PreviewKey pendingPreview;
    private volatile RecentChangesPreviewSession.PreviewKey preparedPreview;

    private RecentChangesOverlayCoordinator() {
    }

    public static RecentChangesOverlayCoordinator getInstance() {
        return INSTANCE;
    }

    public boolean tick(Minecraft client, boolean altHeld) {
        return this.tick(client, altHeld, PreviewTarget.UNDO);
    }

    public boolean tick(Minecraft client, boolean altHeld, PreviewTarget previewTarget) {
        if (client == null) {
            this.logSkip("no-client", previewTarget);
            this.clearPreview();
            return false;
        }
        if (client.player == null) {
            this.logSkip("no-player", previewTarget);
            this.clearPreview();
            return false;
        }
        if (client.level == null) {
            this.logSkip("no-level", previewTarget);
            this.clearPreview();
            return false;
        }
        if (!altHeld) {
            this.logSkip("overlay-key-not-held", previewTarget);
            this.clearPreview();
            return false;
        }
        if (CompareOverlayRenderer.visible()) {
            this.logSkip("compare-overlay-visible", previewTarget);
            this.clearPreview();
            return false;
        }

        try {
            var project = ClientProjectAccess.findCurrentWorldProject(client, this.projectService);
            if (project.isEmpty()) {
                this.logSkip("no-project", previewTarget);
                this.clearPreview();
                return false;
            }

            String projectId = project.get().id().toString();
            long streamRevision = this.historyManager.revision(projectId);
            RecentChangesPreviewSession.PreviewKey pending = this.pendingPreview;
            if (pending != null
                    && projectId.equals(pending.projectId())
                    && previewTarget == pending.previewTarget()) {
                return true;
            }
            RecentChangesPreviewSession.PinnedPreview pinnedPreview = this.previewSession.request(
                    projectId,
                    previewTarget,
                    streamRevision,
                    () -> this.recentActionsSnapshot(projectId, previewTarget)
            );
            if (!pinnedPreview.hasBlockPreview()) {
                this.clearPreview();
                return false;
            }
            RecentChangesPreviewSession.PreviewKey previewKey = pinnedPreview.key();
            this.requestedPreview = previewKey;
            if (RecentChangesOverlayRenderer.visibleFor(projectId, previewKey.revision(), previewTarget)) {
                this.preparedPreview = previewKey;
                return true;
            }
            if (previewKey.equals(this.preparedPreview)) {
                return true;
            }
            if (previewKey.equals(this.pendingPreview)) {
                return true;
            }

            RecentChangesOverlayRenderer.clear();
            boolean debugEnabled = LumaDebugLog.enabled(project.get());
            this.preparePreview(pinnedPreview, debugEnabled);
            return true;
        } catch (Exception exception) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "recent-coordinator-failed",
                    "recent-overlay",
                    "Coordinator failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            this.clearPreview();
            return false;
        }
    }

    private void preparePreview(RecentChangesPreviewSession.PinnedPreview pinnedPreview, boolean debugEnabled) {
        RecentChangesPreviewSession.PreviewKey previewKey = pinnedPreview.key();
        this.pendingPreview = previewKey;
        CompletableFuture
                .supplyAsync(() -> this.prepareOverlay(pinnedPreview, debugEnabled), this.previewExecutor)
                .whenComplete((prepared, exception) -> {
                    if (previewKey.equals(this.pendingPreview)) {
                        this.pendingPreview = null;
                    }
                    if (exception != null) {
                        OverlayDiagnostics.getInstance().log(
                                debugEnabled,
                                "recent-prepare-failed",
                                "recent-overlay",
                                "Preview prepare failed project={} revision={} preview={} with {}: {}",
                                previewKey.projectId(),
                                previewKey.revision(),
                                previewKey.previewTarget(),
                                exception.getClass().getSimpleName(),
                                exception.getMessage()
                        );
                        return;
                    }
                    if (previewKey.equals(this.requestedPreview)) {
                        RecentChangesOverlayRenderer.activate(prepared);
                        this.preparedPreview = previewKey;
                    }
                });
    }

    private RecentChangesOverlayRenderer.PreparedOverlay prepareOverlay(
            RecentChangesPreviewSession.PinnedPreview pinnedPreview,
            boolean debugEnabled
    ) {
        RecentChangesPreviewSession.PreviewKey previewKey = pinnedPreview.key();
        RecentChangesOverlaySnapshot snapshot = new RecentChangesOverlaySnapshot(
                previewKey.projectId(),
                previewKey.revision(),
                pinnedPreview.undoActions(),
                pinnedPreview.redoActions()
        );
        return RecentChangesOverlayRenderer.prepare(snapshot, debugEnabled, previewKey.previewTarget());
    }

    private void clearPreview() {
        this.previewSession.clear();
        this.requestedPreview = null;
        this.pendingPreview = null;
        this.preparedPreview = null;
        RecentChangesOverlayRenderer.clear();
    }

    private void logSkip(String reason, PreviewTarget previewTarget) {
        OverlayDiagnostics.getInstance().log(
                false,
                "recent-coordinator-" + reason,
                "recent-overlay",
                "Coordinator skipped reason={} preview={}",
                reason,
                previewTarget
        );
    }

    private RecentChangesPreviewSession.ActionSnapshot recentActionsSnapshot(
            String projectId,
            PreviewTarget previewTarget
    ) {
        if (previewTarget == PreviewTarget.BOTH) {
            UndoRedoHistoryManager.UndoRedoActionsSnapshot snapshot =
                    this.historyManager.recentUndoRedoPreviewActionsSnapshot(
                            projectId,
                            PREVIEW_ACTION_COUNT
                    );
            return new RecentChangesPreviewSession.ActionSnapshot(
                    snapshot.revision(),
                    snapshot.undoActions(),
                    snapshot.redoActions()
            );
        }
        UndoRedoHistoryManager.RecentActionsSnapshot snapshot;
        if (previewTarget == PreviewTarget.REDO) {
            snapshot = this.historyManager.recentRedoPreviewActionsSnapshot(
                    projectId,
                    PREVIEW_ACTION_COUNT
            );
            return new RecentChangesPreviewSession.ActionSnapshot(
                    snapshot.revision(),
                    List.of(),
                    snapshot.actions()
            );
        } else {
            snapshot = this.historyManager.recentUndoPreviewActionsSnapshot(
                    projectId,
                    PREVIEW_ACTION_COUNT
            );
            return new RecentChangesPreviewSession.ActionSnapshot(
                    snapshot.revision(),
                    snapshot.actions(),
                    List.of()
            );
        }
    }

    public enum PreviewTarget {
        UNDO,
        REDO,
        BOTH
    }
}
