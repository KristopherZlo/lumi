package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void createsVisibleBranchAtAnExistingCommitAndHidesInternalRefs() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        ActiveBranchRepository active = new ActiveBranchRepository(repositoryRoot);
        WorkingIndexRepository working = new WorkingIndexRepository(repositoryRoot);
        var main = new DimensionHistoryInitializer(
                new WorldObjectRepository(repositoryRoot), commits, refs,
                active)
                .initialize(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        BranchService branches = new BranchService(commits, refs, active, working);

        var created = branches.create(new BranchName("redstone-test"), main.commit());
        refs.create(new BranchName("hidden/return/test"), main.commit());

        assertEquals(main.commit(), created.commit());
        assertEquals(0, created.revision());
        assertEquals(List.of(new BranchName("main"), new BranchName("redstone-test")),
                branches.visible().stream().map(ref -> ref.name()).sorted(
                        java.util.Comparator.comparing(BranchName::value)).toList());

        working.write(new WorkingIndexSnapshot(Map.of(new SectionKey(0, 0, 0), 1L)));
        assertThrows(IllegalStateException.class,
                () -> branches.prepareSwitch(created.name()));
        working.write(WorkingIndexSnapshot.empty());
        var switchPlan = branches.prepareSwitch(created.name());

        assertEquals(main, branches.active());
        branches.completeSwitch(switchPlan);
        assertEquals(created, branches.active());
    }

    @Test
    void refusesBranchAtUnknownCommit() {
        BranchService branches = new BranchService(
                new CommitRepository(repositoryRoot), new BranchRefRepository(repositoryRoot),
                new ActiveBranchRepository(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot));

        assertThrows(IOException.class, () -> branches.create(
                new BranchName("broken"),
                new CommitId(new ObjectId("1".repeat(64)))));
    }
}
