package io.github.luma.minecraft.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredActionFalloutGuardTest {

    private final DeferredActionFalloutGuard guard = DeferredActionFalloutGuard.getInstance();

    @AfterEach
    void clearGuard() {
        this.guard.clear();
    }

    @Test
    void suppressesDeferredCallbacksForReplayedActionTemporarily() {
        this.guard.suppressAction("action-1", 100L);

        assertTrue(this.guard.shouldSuppress("action-1", 100L));
        assertTrue(this.guard.shouldSuppress("action-1", 199L));
        assertFalse(this.guard.shouldSuppress("action-1", 201L));
    }

    @Test
    void ignoresBlankAndDifferentActions() {
        this.guard.suppressAction("action-1", 100L);

        assertFalse(this.guard.shouldSuppress("", 100L));
        assertFalse(this.guard.shouldSuppress("action-2", 100L));
    }
}
