package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldOperationShutdownHandlerTest {

    @Test
    void nonTerminalOperationFailsAndMovesToRecentOnShutdown() {
        WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
        TestOperation operation = new TestOperation("restore", false);
        lifecycle.start("world", operation);
        WorldOperationShutdownHandler handler = new WorldOperationShutdownHandler(
                lifecycle,
                new WorldApplyBudgetPlanner(),
                (server, active) -> lifecycle.complete("world", active)
        );

        handler.finishServerOperationBeforeShutdown("world", null);

        assertFalse(lifecycle.hasActive("world"));
        assertEquals(OperationStage.FAILED, lifecycle.snapshot("world").orElseThrow().stage());
    }

    @Test
    void drainableOperationCanCompleteBeforeShutdownFailure() {
        WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
        TestOperation operation = new TestOperation("light-refresh", true);
        lifecycle.start("world", operation);
        WorldOperationShutdownHandler handler = new WorldOperationShutdownHandler(
                lifecycle,
                new WorldApplyBudgetPlanner(),
                (server, active) -> lifecycle.complete("world", active)
        );

        handler.finishServerOperationBeforeShutdown("world", null);

        assertFalse(lifecycle.hasActive("world"));
        assertEquals(OperationStage.COMPLETED, lifecycle.snapshot("world").orElseThrow().stage());
    }

    private static final class TestOperation extends WorldOperationManager.ActiveOperation {

        private final boolean drainBeforeShutdown;

        private TestOperation(String label, boolean drainBeforeShutdown) {
            super(null, handle(label), "blocks");
            this.drainBeforeShutdown = drainBeforeShutdown;
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) {
            this.complete("drained");
            return true;
        }

        @Override
        protected boolean drainBeforeShutdown() {
            return this.drainBeforeShutdown;
        }

        private static OperationHandle handle(String label) {
            return new OperationHandle(UUID.randomUUID().toString(), "project", label, Instant.EPOCH, false);
        }
    }
}
