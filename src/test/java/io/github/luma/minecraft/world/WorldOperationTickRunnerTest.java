package io.github.luma.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

class WorldOperationTickRunnerTest {

    private final WorldOperationTickRunner runner = new WorldOperationTickRunner(new WorldApplyBudgetPlanner());

    @Test
    void completesOperationWhenAdvanceReportsDone() {
        TestOperation operation = new TestOperation(false);
        AtomicInteger completions = new AtomicInteger();

        this.runner.advance(null, operation, (server, completed) -> completions.incrementAndGet());

        assertEquals(1, operation.advanceCalls);
        assertEquals(1, completions.get());
    }

    @Test
    void failsAndCompletesOperationWhenAdvanceThrows() {
        TestOperation operation = new TestOperation(true);
        AtomicInteger completions = new AtomicInteger();

        this.runner.advance(null, operation, (server, completed) -> completions.incrementAndGet());

        assertEquals(OperationStage.FAILED, operation.snapshot().stage());
        assertEquals(1, completions.get());
    }

    private static final class TestOperation extends WorldOperationManager.ActiveOperation {

        private final boolean fail;
        private int advanceCalls;

        private TestOperation(boolean fail) {
            super(null, new OperationHandle("op", "project", "restore-version", Instant.EPOCH, false), "blocks");
            this.fail = fail;
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) throws Exception {
            this.advanceCalls += 1;
            if (this.fail) {
                throw new IllegalStateException("boom");
            }
            return true;
        }
    }
}
