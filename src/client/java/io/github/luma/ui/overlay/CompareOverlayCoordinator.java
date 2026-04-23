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
        if (request == null || !request.involvesCurrentWorld()) {
            this.refreshCooldown = 0;
            return;
        }
        if (client == null || client.player == null || client.level == null || !client.hasSingleplayerServer()) {
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
            // Keep the last known overlay state until the next successful refresh.
        }
    }
}
