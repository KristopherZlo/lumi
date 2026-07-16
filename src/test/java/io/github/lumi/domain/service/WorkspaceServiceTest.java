package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void initializesDefaultThenCreatesNamedWorkspaceOnSharedTree() throws Exception {
        WorkspaceRepository workspaces = new WorkspaceRepository(repositoryRoot);
        ActiveWorkspaceRepository active = new ActiveWorkspaceRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        UUID defaultId = new UUID(0, 2);
        var tree = objects.write(new DimensionTree(Map.of()));
        var initial = commits.write(commit(tree, defaultId));
        var main = refs.create(new BranchName("main"), initial);
        WorkspaceService service = new WorkspaceService(workspaces, active, commits, refs);

        service.initializeDefault(defaultId);
        UUID projectId = new UUID(0, 3);
        WorkspaceService.Creation created = service.create(
                projectId, "Castle", Optional.empty(), WorkspaceSettings.defaults(), main,
                new CommitAuthor(new UUID(0, 1), "Builder"), Instant.EPOCH);

        assertEquals(defaultId, active.read().orElseThrow().id());
        assertEquals(projectId, created.workspace().id());
        assertEquals(tree, commits.read(created.main().commit()).tree());
        assertEquals(projectId, commits.read(created.main().commit()).workspaceId());
        assertEquals(List.of(initial), commits.read(created.main().commit()).parents());
        assertEquals(new BranchName("workspace/00000000-0000-0000-0000-000000000003/main"),
                created.main().name());
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree, UUID workspace) {
        return new Commit(tree, List.of(), new CommitAuthor(new UUID(0, 0), "Lumi"),
                "Initial", Instant.EPOCH, workspace, Optional.empty(),
                CommitKind.HIDDEN_SAFETY, new CommitStatistics(0, 0, 0, 0));
    }
}
