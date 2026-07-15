package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchSwitchRestorePublicationTest {
    @TempDir Path repositoryRoot;

    @Test
    void switchesOnlyTheActivePointerAfterVerifiedRestore() throws Exception {
        var objects = new WorldObjectRepository(repositoryRoot);
        var commits = new CommitRepository(repositoryRoot);
        var refs = new BranchRefRepository(repositoryRoot);
        var active = new ActiveBranchRepository(repositoryRoot);
        var working = new WorkingIndexRepository(repositoryRoot);
        var main = new DimensionHistoryInitializer(objects, commits, refs, active)
                .initialize(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        working.write(WorkingIndexSnapshot.empty());
        var branches = new BranchService(commits, refs, active, working);
        var target = branches.create(new BranchName("redstone-test"), main.commit());
        var plan = branches.prepareSwitch(target.name());
        var restore = new PreparedRestore(
                main, target.commit(), Map.of(), Map.of(), Map.of(), Map.of());

        new BranchSwitchRestorePublication(branches, plan).publish(restore);

        assertEquals(target, branches.active());
        assertEquals(main, refs.read(main.name()).orElseThrow());
        assertEquals(target, refs.read(target.name()).orElseThrow());
    }
}
