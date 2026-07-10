package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Prepares the cumulative pending-draft overlay off the client tick.
 */
public final class PendingChangesOverlayCoordinator {

    private static final PendingChangesOverlayCoordinator INSTANCE = new PendingChangesOverlayCoordinator();
    private static final int SNAPSHOT_REQUEST_INTERVAL_TICKS = 10;
    private static final Duration ACTIVE_DRAFT_PREVIEW_QUIET_PERIOD = Duration.ofMillis(500);

    private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumi-pending-overlay-preview");
        thread.setDaemon(true);
        return thread;
    });
    private volatile RequestKey requestedKey;
    private volatile RequestKey pendingKey;
    private volatile RequestKey preparedKey;
    private int requestCooldown = 0;

    private PendingChangesOverlayCoordinator() {
    }

    public static PendingChangesOverlayCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft client, boolean active) {
        if (client == null || client.player == null || client.level == null || !active) {
            this.clearPreview();
            return;
        }
        if (CompareOverlayRenderer.visible() || RecentChangesOverlayRenderer.visible()) {
            this.clearPreview();
            return;
        }

        try {
            var project = ClientProjectAccess.findCurrentWorldProject(client);
            if (project.isEmpty()) {
                this.clearPreview();
                return;
            }
            String projectId = project.get().id().toString();
            this.requestedKey = new RequestKey(projectId);
            if (this.requestCooldown > 0) {
                this.requestCooldown -= 1;
                return;
            }
            if (this.pendingKey != null && this.pendingKey.projectId().equals(projectId)) {
                return;
            }
            this.requestCooldown = SNAPSHOT_REQUEST_INTERVAL_TICKS;
            boolean debugEnabled = LumaDebugLog.enabled(project.get());
            this.preparePreview(client, new RequestKey(projectId), debugEnabled);
        } catch (Exception exception) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "pending-coordinator-failed",
                    "pending-overlay",
                    "Coordinator failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            this.clearPreview();
        }
    }

    private void preparePreview(Minecraft client, RequestKey requestKey, boolean debugEnabled) {
        this.pendingKey = requestKey;
        CompletableFuture
                .supplyAsync(() -> this.loadSnapshot(client, requestKey.projectId()), this.previewExecutor)
                .thenApply(snapshot -> snapshot == null
                        ? null
                        : PendingChangesOverlayRenderer.prepare(snapshot, debugEnabled))
                .whenComplete((prepared, exception) -> {
                    if (requestKey.equals(this.pendingKey)) {
                        this.pendingKey = null;
                    }
                    if (exception != null) {
                        PendingChangesOverlayRenderer.discard(prepared);
                        OverlayDiagnostics.getInstance().log(
                                debugEnabled,
                                "pending-prepare-failed",
                                "pending-overlay",
                                "Pending overlay prepare failed project={} with {}: {}",
                                requestKey.projectId(),
                                exception.getClass().getSimpleName(),
                                exception.getMessage()
                        );
                        return;
                    }
                    if (prepared == null) {
                        return;
                    }
                    if (!requestKey.equals(this.requestedKey)) {
                        PendingChangesOverlayRenderer.discard(prepared);
                        return;
                    }
                    if (prepared.state() == null) {
                        PendingChangesOverlayRenderer.discard(prepared);
                        PendingChangesOverlayRenderer.clear();
                        this.preparedKey = null;
                        return;
                    }
                    if (PendingChangesOverlayRenderer.visibleFor(prepared.projectId(), prepared.revision())) {
                        PendingChangesOverlayRenderer.discard(prepared);
                        this.preparedKey = requestKey;
                        return;
                    }
                    PendingChangesOverlayRenderer.activate(prepared);
                    this.preparedKey = requestKey;
                });
    }

    private PendingChangesOverlaySnapshot loadSnapshot(Minecraft client, String projectId) {
        try {
            Instant now = Instant.now();
            var server = ClientProjectAccess.requireSingleplayerServer(client);
            if (this.captureManager.activeDraftUpdatedAfter(
                    server,
                    projectId,
                    now.minus(ACTIVE_DRAFT_PREVIEW_QUIET_PERIOD)
            )) {
                return null;
            }
            Optional<RecoveryDraft> draft = this.captureManager.snapshotDraft(
                    server,
                    projectId
            );
            if (draft.isPresent() && shouldDeferHotDraft(draft.get(), Instant.now())) {
                return null;
            }
            return draft
                    .map(value -> PendingChangesOverlaySnapshot.fromDraft(projectId, value))
                    .orElseGet(() -> PendingChangesOverlaySnapshot.empty(projectId));
        } catch (Exception exception) {
            throw new IllegalStateException("Pending draft snapshot failed", exception);
        }
    }

    private void clearPreview() {
        this.requestedKey = null;
        this.pendingKey = null;
        this.preparedKey = null;
        this.requestCooldown = 0;
        PendingChangesOverlayRenderer.clear();
    }

    static boolean shouldDeferHotDraft(RecoveryDraft draft, Instant now) {
        if (draft == null || draft.updatedAt() == null || now == null) {
            return false;
        }
        return Duration.between(draft.updatedAt(), now).compareTo(ACTIVE_DRAFT_PREVIEW_QUIET_PERIOD) < 0;
    }

    private record RequestKey(String projectId) {
    }
}
