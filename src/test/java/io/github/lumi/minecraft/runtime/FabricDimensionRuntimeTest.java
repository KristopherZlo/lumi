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
    void technobladeTributeRequiresARevivedNamedPig() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains(
                "plan.direction() != LiveActionJournal.Direction.UNDO"));
        assertTrue(source.contains("plan.expectedEntities()"));
        assertTrue(source.contains("entity instanceof Pig"));
        assertTrue(source.contains("\"Technoblade\".equals("));
        assertTrue(source.contains("\"TECHNOBLADE NEVER DIES!\""));
        assertTrue(source.contains("ChatFormatting.BOLD"));
    }
}
