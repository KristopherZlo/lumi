package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOperationLifecycleTest {

    @Test
    void cannotStartSecondOperationForSameWorld() {
        WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
        lifecycle.start("world", new TestOperation("first"));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> lifecycle.start("world", new TestOperation("second"))
        );

        assertEquals("Another world operation is already running", thrown.getMessage());
    }

    @Test
    void completedOperationIsRetainedByHandle() {
        WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
        TestOperation operation = new TestOperation("first");
        lifecycle.start("world", operation);
        operation.markComplete();

        WorldOperationLifecycle.Completion completion = lifecycle.complete("world", operation);

        assertTrue(completion.completed());
        assertEquals(Optional.of(operation.snapshot()), lifecycle.snapshot("world", operation.handle()));
        assertEquals(OperationStage.COMPLETED, lifecycle.snapshot("world").orElseThrow().stage());
    }

    @Test
    void followUpOperationBecomesActiveAfterCompletion() {
        WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
        TestOperation followUp = new TestOperation("light-refresh");
        TestOperation operation = new TestOperation("restore", followUp);
        lifecycle.start("world", operation);
        operation.markComplete();

        WorldOperationLifecycle.Completion completion = lifecycle.complete("world", operation);

        assertTrue(completion.completed());
        assertSame(followUp, completion.followUp());
        assertSame(followUp, lifecycle.active("world"));
    }

    private static final class TestOperation extends WorldOperationManager.ActiveOperation {

        private final WorldOperationManager.ActiveOperation followUp;

        private TestOperation(String label) {
            this(label, null);
        }

        private TestOperation(String label, WorldOperationManager.ActiveOperation followUp) {
            super(null, handle(label), "blocks");
            this.followUp = followUp;
        }

        private void markComplete() {
            this.complete("done");
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) {
            return true;
        }

        @Override
        protected WorldOperationManager.ActiveOperation followUpOperation() {
            return this.followUp;
        }

        private static OperationHandle handle(String label) {
            return new OperationHandle(UUID.randomUUID().toString(), "project", label, Instant.EPOCH, false);
        }
    }
}
