package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.WorkspaceService;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSwitchRestorePublicationTest {
    @TempDir Path repositoryRoot;

    @Test
    void finishesBothPointersWhenBranchPointerWasAlreadyPublished() throws Exception {
        var objects = new WorldObjectRepository(repositoryRoot);
        var commits = new CommitRepository(repositoryRoot);
        var refs = new BranchRefRepository(repositoryRoot);
        var activeBranch = new ActiveBranchRepository(repositoryRoot);
        var working = new WorkingIndexRepository(repositoryRoot);
        UUID defaultId = new UUID(0, 1);
        var main = new DimensionHistoryInitializer(objects, commits, refs, activeBranch)
                .initialize(defaultId);
        working.write(WorkingIndexSnapshot.empty());
        var branches = new BranchService(commits, refs, activeBranch, working);
        var activeWorkspace = new ActiveWorkspaceRepository(repositoryRoot);
        var workspaces = new WorkspaceService(
                new WorkspaceRepository(repositoryRoot), activeWorkspace, commits, refs);
        workspaces.initializeDefault(defaultId);
        UUID targetId = new UUID(0, 2);
        var created = workspaces.create(
                targetId, "Castle", Optional.empty(),
                io.github.lumi.domain.model.WorkspaceSettings.defaults(), main,
                new CommitAuthor(new UUID(0, 3), "Builder"), Instant.EPOCH);
        var branchPlan = branches.prepareSwitch(created.main().name());
        var plan = workspaces.prepareSwitch(targetId, branchPlan);
        var restore = new PreparedRestore(
                main, created.main().commit(), Map.of(), Map.of(), Map.of(), Map.of());
        branches.completeSwitch(branchPlan);

        new WorkspaceSwitchRestorePublication(branches, workspaces, plan).publish(restore);

        assertEquals(created.main(), branches.active());
        assertEquals(targetId, workspaces.active().id());
        new WorkspaceSwitchRestorePublication(branches, workspaces, plan).publish(restore);
    }
}
