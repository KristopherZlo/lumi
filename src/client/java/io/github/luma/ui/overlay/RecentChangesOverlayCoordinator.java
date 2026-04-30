package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Loads and exposes the recent action overlay while Alt is held.
 */
public final class RecentChangesOverlayCoordinator {

    private static final RecentChangesOverlayCoordinator INSTANCE = new RecentChangesOverlayCoordinator();
    private static final int PREVIEW_ACTION_COUNT = 10;

    private final ProjectService projectService = new ProjectService();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumi-recent-overlay-preview");
        thread.setDaemon(true);
        return thread;
    });
    private volatile PreviewKey requestedPreview;
    private volatile PreviewKey pendingPreview;
    private volatile PreviewKey preparedPreview;

    private RecentChangesOverlayCoordinator() {
    }

    public static RecentChangesOverlayCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft client, boolean altHeld) {
        this.tick(client, altHeld, PreviewTarget.UNDO);
    }

    public void tick(Minecraft client, boolean altHeld, PreviewTarget previewTarget) {
        if (client == null) {
            this.logSkip("no-client", previewTarget);
            this.clearPreview();
            return;
        }
        if (client.player == null) {
            this.logSkip("no-player", previewTarget);
            this.clearPreview();
            return;
        }
        if (client.level == null) {
            this.logSkip("no-level", previewTarget);
            this.clearPreview();
            return;
        }
        if (!altHeld) {
            this.logSkip("overlay-key-not-held", previewTarget);
            this.clearPreview();
            return;
        }
        if (CompareOverlayRenderer.visible()) {
            this.logSkip("compare-overlay-visible", previewTarget);
            this.clearPreview();
            return;
        }

        try {
            var project = ClientProjectAccess.findCurrentWorldProject(client, this.projectService);
            if (project.isEmpty()) {
                this.logSkip("no-project", previewTarget);
                this.clearPreview();
                return;
            }

            String projectId = project.get().id().toString();
            long revision = UndoRedoHistoryManager.getInstance().revision(projectId);
            PreviewKey previewKey = new PreviewKey(projectId, revision, previewTarget);
            this.requestedPreview = previewKey;
            if (RecentChangesOverlayRenderer.visibleFor(projectId, revision, previewTarget)) {
                this.preparedPreview = previewKey;
                return;
            }
            if (previewKey.equals(this.preparedPreview)) {
                return;
            }
            if (previewKey.equals(this.pendingPreview)) {
                return;
            }

            RecentChangesOverlayRenderer.clear();
            boolean debugEnabled = LumaDebugLog.enabled(project.get());
            this.preparePreview(previewKey, debugEnabled);
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
        }
    }

    private void preparePreview(PreviewKey previewKey, boolean debugEnabled) {
        this.pendingPreview = previewKey;
        CompletableFuture
                .supplyAsync(() -> this.prepareOverlay(previewKey, debugEnabled), this.previewExecutor)
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

    private RecentChangesOverlayRenderer.PreparedOverlay prepareOverlay(PreviewKey previewKey, boolean debugEnabled) {
        List<UndoRedoAction> actions = this.recentActions(previewKey.projectId(), previewKey.previewTarget());
        return RecentChangesOverlayRenderer.prepare(
                previewKey.projectId(),
                actions,
                debugEnabled,
                previewKey.previewTarget(),
                previewKey.revision()
        );
    }

    private void clearPreview() {
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

    private List<UndoRedoAction> recentActions(String projectId, PreviewTarget previewTarget) {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        if (previewTarget == PreviewTarget.REDO) {
            return historyManager.recentRedoActions(projectId, PREVIEW_ACTION_COUNT);
        }
        return historyManager.recentUndoActions(projectId, PREVIEW_ACTION_COUNT);
    }

    private record PreviewKey(String projectId, long revision, PreviewTarget previewTarget) {

        private PreviewKey {
            previewTarget = Objects.requireNonNullElse(previewTarget, PreviewTarget.UNDO);
        }
    }

    public enum PreviewTarget {
        UNDO,
        REDO
    }
}
