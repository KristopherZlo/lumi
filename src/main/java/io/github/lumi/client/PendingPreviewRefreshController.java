package io.github.lumi.client;

import java.util.Objects;

/** Requests one fresh pending preview on each eligible Alt key press. */
public final class PendingPreviewRefreshController {
    private final Runnable refresh;
    private boolean altWasDown;

    public PendingPreviewRefreshController(Runnable refresh) {
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    public void tick(boolean altDown, boolean canRefresh) {
        if (altDown && !altWasDown && canRefresh) {
            refresh.run();
        }
        altWasDown = altDown;
    }
}
