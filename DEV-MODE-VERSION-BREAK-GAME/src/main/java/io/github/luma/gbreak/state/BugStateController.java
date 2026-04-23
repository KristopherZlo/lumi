package io.github.luma.gbreak.state;

import io.github.luma.gbreak.bug.GameBreakingBug;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BugStateController {

    private static final BugStateController INSTANCE = new BugStateController();

    private final AtomicReference<GameBreakingBug> activeBug = new AtomicReference<>(GameBreakingBug.NONE);
    private final AtomicBoolean altClipRequested = new AtomicBoolean(false);

    private BugStateController() {
    }

    public static BugStateController getInstance() {
        return INSTANCE;
    }

    public GameBreakingBug activeBug() {
        return this.activeBug.get();
    }

    public void activate(GameBreakingBug bug) {
        GameBreakingBug resolved = bug == null ? GameBreakingBug.NONE : bug;
        this.activeBug.set(resolved);
        if (resolved != GameBreakingBug.GHOST_PLAYER) {
            this.altClipRequested.set(false);
        }
    }

    public boolean isActive(GameBreakingBug bug) {
        return this.activeBug.get() == bug;
    }

    public boolean altClipRequested() {
        return this.altClipRequested.get();
    }

    public void setAltClipRequested(boolean requested) {
        this.altClipRequested.set(requested);
    }
}
