package io.github.luma.ui.overlay;

import io.github.luma.domain.model.VersionDiff;
import io.github.luma.ui.controller.AsyncCompareCache;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.CompareRequestKey;
import net.minecraft.client.Minecraft;

/**
 * Periodically refreshes current-world compare overlays so new edits are
 * reflected without rebuilding the screen manually.
 */
public final class CompareOverlayCoordinator {

    private static final int REFRESH_INTERVAL_TICKS = 40;
    private static final int LARGE_DIFF_AUTO_REFRESH_LIMIT = CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT;
    private static final CompareOverlayCoordinator INSTANCE = new CompareOverlayCoordinator();

    private final CompareScreenController controller = new CompareScreenController();
    private final AsyncCompareCache asyncCompareCache = AsyncCompareCache.getInstance();
    private int refreshCooldown = 0;
    private CompareRequestKey pendingRefreshKey;

    private CompareOverlayCoordinator() {
    }

    public static CompareOverlayCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft client) {
        CompareOverlayRenderer.RefreshRequest request = CompareOverlayRenderer.refreshRequest();
        if (request == null) {
            this.logSkip(false, "no-request", "", "");
            this.refreshCooldown = 0;
            return;
        }
        if (!request.involvesCurrentWorld()) {
            this.refreshCooldown = 0;
            return;
        }
        if (!request.visible()) {
            this.refreshCooldown = 0;
            return;
        }
        if (client == null || client.player == null || client.level == null || !client.hasSingleplayerServer()) {
            this.logSkip(request.debugEnabled(), "client-not-ready", request.leftVersionId(), request.rightVersionId());
            this.refreshCooldown = 0;
            return;
        }

        this.refreshCooldown -= 1;
        if (this.refreshCooldown > 0) {
            return;
        }
        this.refreshCooldown = REFRESH_INTERVAL_TICKS;
        if (request.changedBlockCount() > LARGE_DIFF_AUTO_REFRESH_LIMIT) {
            this.logSkip(request.debugEnabled(), "large-diff-auto-refresh-disabled", request.leftVersionId(), request.rightVersionId());
            return;
        }

        try {
            CompareRequestKey key = new CompareRequestKey(
                    request.projectName(),
                    request.leftVersionId(),
                    request.rightVersionId()
            );
            boolean startNewRefresh = !key.equals(this.pendingRefreshKey);
            if (startNewRefresh) {
                this.pendingRefreshKey = key;
            }
            AsyncCompareCache.CompareResultState asyncState = this.asyncCompareCache.request(
                    key,
                    () -> new AsyncCompareCache.CompareResult(
                            this.controller.buildDiff(
                                    ClientProjectAccess.requireSingleplayerServer(client),
                                    request.projectName(),
                                    request.leftVersionId(),
                                    request.rightVersionId()
                            ),
                            java.util.List.of()
                    ),
                    startNewRefresh
            );
            if (asyncState.status() == AsyncCompareCache.Status.LOADING) {
                this.logSkip(request.debugEnabled(), "refresh-loading", request.leftVersionId(), request.rightVersionId());
                return;
            }
            this.pendingRefreshKey = null;
            if (asyncState.status() == AsyncCompareCache.Status.FAILED) {
                this.logSkip(request.debugEnabled(), "refresh-failed", request.leftVersionId(), request.rightVersionId());
                return;
            }
            VersionDiff diff = asyncState.result() == null ? null : asyncState.result().diff();
            if (diff == null) {
                this.logSkip(request.debugEnabled(), "diff-null", request.leftVersionId(), request.rightVersionId());
                CompareOverlayRenderer.clear();
                return;
            }

            CompareOverlayRenderer.refresh(
                    request.projectName(),
                    request.leftVersionId(),
                    request.rightVersionId(),
                    diff.changedBlocks(),
                    request.debugEnabled()
            );
        } catch (Exception exception) {
            OverlayDiagnostics.getInstance().log(
                    request.debugEnabled(),
                    "compare-coordinator-failed",
                    "compare-overlay",
                    "Refresh coordinator failed left={} right={} with {}: {}",
                    request.leftVersionId(),
                    request.rightVersionId(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            // Keep the last known overlay state until the next successful refresh.
        }
    }

    private void logSkip(boolean debugEnabled, String reason, String leftVersionId, String rightVersionId) {
        OverlayDiagnostics.getInstance().log(
                debugEnabled,
                "compare-coordinator-" + reason,
                "compare-overlay",
                "Refresh coordinator skipped reason={} left={} right={}",
                reason,
                leftVersionId,
                rightVersionId
        );
    }
}
