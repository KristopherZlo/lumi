package io.github.luma.ui.overlay;

import io.github.luma.domain.model.VersionDiff;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.controller.CompareScreenController;
import net.minecraft.client.Minecraft;

/**
 * Periodically refreshes current-world compare overlays so new edits are
 * reflected without rebuilding the screen manually.
 */
public final class CompareOverlayCoordinator {

    private static final int REFRESH_INTERVAL_TICKS = 10;
    private static final CompareOverlayCoordinator INSTANCE = new CompareOverlayCoordinator();

    private final CompareScreenController controller = new CompareScreenController();
    private int refreshCooldown = 0;

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

        try {
            VersionDiff diff = this.controller.buildDiff(
                    ClientProjectAccess.requireSingleplayerServer(client),
                    request.projectName(),
                    request.leftVersionId(),
                    request.rightVersionId()
            );
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
