package io.github.lumi.client;

import java.util.Objects;
import java.util.Optional;

/** Refreshes held previews and retains the visibility chosen from the GUI. */
public final class PendingPreviewRefreshController {
    private final Runnable refresh;
    private boolean altWasDown;
    private boolean pinned;
    private boolean visible;

    public PendingPreviewRefreshController(Runnable refresh) {
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    public void tick(boolean altDown, boolean canRefresh) {
        if (altDown && !altWasDown && canRefresh) {
            refresh.run();
        }
        altWasDown = altDown && canRefresh;
    }

    public void show() {
        pinned = visible = true;
    }

    public boolean visible() {
        return visible;
    }

    public Optional<Boolean> toggleVisibility() {
        if (!pinned) return Optional.empty();
        visible = !visible;
        if (visible) refresh.run();
        return Optional.of(visible);
    }

    public void clear() {
        pinned = visible = altWasDown = false;
    }
}
