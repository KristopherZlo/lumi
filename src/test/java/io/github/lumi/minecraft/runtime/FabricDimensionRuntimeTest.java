package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FabricDimensionRuntimeTest {
    private static final CommitId HEAD = new CommitId(new ObjectId("a".repeat(64)));
    private static final CommitId OTHER = new CommitId(new ObjectId("b".repeat(64)));

    @Test
    void restoreIsNoOpOnlyForCleanCurrentHead() {
        assertTrue(FabricDimensionRuntime.isRestoreNoOp(HEAD, HEAD, false));
        assertFalse(FabricDimensionRuntime.isRestoreNoOp(HEAD, HEAD, true));
        assertFalse(FabricDimensionRuntime.isRestoreNoOp(HEAD, OTHER, false));
    }

    @Test
    void rebasesLiveBlockEntitiesFromTheVerifiedRestoreState() throws Exception {
        String source = runtimeSource();

        assertTrue(source.contains("var restoredSections = state.sections().keySet()"));
        assertTrue(source.contains("liveBlocks.rebaseSections(restoredSections)"));
        assertTrue(source.contains("causalTicks.cancelSections(restoredSections)"));
        assertFalse(source.contains("liveBlocks.rebaseSections(state.sections())"));
    }

    @Test
    void technobladeTributeRequiresARevivedNamedPig() throws Exception {
        String source = runtimeSource();

        assertTrue(source.contains(
                "plan.direction() != LiveActionJournal.Direction.UNDO"));
        assertTrue(source.contains("plan.expectedEntities()"));
        assertTrue(source.contains("entity instanceof Pig"));
        assertTrue(source.contains("\"Technoblade\".equals("));
        assertTrue(source.contains("\"TECHNOBLADE NEVER DIES!\""));
        assertTrue(source.contains("ChatFormatting.BOLD"));
    }

    @Test
    void refusesLiveActionsWhileAnotherWorldOperationIsRunning() throws Exception {
        String source = runtimeSource();
        int start = source.indexOf("public synchronized DimensionMutation startLiveAction(");
        int end = source.indexOf("private DimensionMutation createSessionAction(", start);
        String liveAction = source.substring(start, end);

        assertTrue(liveAction.contains("operations.hasActiveOperation()"));
        assertTrue(liveAction.contains("operations.queuedCount() > 0"));
        assertTrue(liveAction.contains("\"luma.status.world_operation_busy\""));
        assertTrue(liveAction.indexOf("world_operation_busy")
                < liveAction.indexOf("operations.enqueue("));
    }

    private static String runtimeSource() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));
    }
}
