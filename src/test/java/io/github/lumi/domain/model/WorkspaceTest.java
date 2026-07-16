package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceTest {
    @Test
    void boundedWorkspaceIncludesIntersectingSectionsAndEntityChunks() {
        Workspace workspace = new Workspace(
                new UUID(0, 1), "Build", Optional.of(new BlockBox(16, 0, 32, 31, 15, 47)),
                WorkspaceSettings.defaults());

        assertTrue(workspace.includes(new SectionKey(1, 0, 2)));
        assertTrue(workspace.includes(new EntityChunkKey(1, 2)));
        assertFalse(workspace.includes(new SectionKey(1, 1, 2)));
        assertFalse(workspace.includes(new EntityChunkKey(2, 2)));
    }

    @Test
    void wholeDimensionWorkspaceIncludesEveryDurableKey() {
        Workspace workspace = new Workspace(
                new UUID(0, 1), "All", Optional.empty(), WorkspaceSettings.defaults());

        assertTrue(workspace.includes(new SectionKey(Integer.MIN_VALUE, -4, Integer.MAX_VALUE)));
        assertTrue(workspace.includes(new EntityChunkKey(Integer.MAX_VALUE, Integer.MIN_VALUE)));
    }
}
