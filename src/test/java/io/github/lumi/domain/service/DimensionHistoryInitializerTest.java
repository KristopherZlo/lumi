package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionHistoryInitializerTest {
    @TempDir Path repositoryRoot;

    @Test
    void createsOneDeterministicEmptyMainRootAndReusesIt() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        DimensionHistoryInitializer initializer =
                new DimensionHistoryInitializer(objects, commits, refs);
        UUID workspace = UUID.fromString("20000000-0000-0000-0000-000000000002");

        var created = initializer.initialize(workspace);
        var reopened = initializer.initialize(workspace);

        assertEquals(new BranchName("main"), created.name());
        assertEquals(created, reopened);
        assertEquals(1, refs.list().size());
        var commit = commits.read(created.commit());
        assertTrue(commit.parents().isEmpty());
        assertEquals(workspace, commit.workspaceId());
        assertTrue(objects.readDimension(commit.tree()).regions().isEmpty());
    }
}
