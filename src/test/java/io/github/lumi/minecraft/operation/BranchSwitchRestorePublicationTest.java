package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

    @Test
    void clearsCapturedAmbientChangesAfterSwitchingThePointer() throws Exception {
        var objects = new WorldObjectRepository(repositoryRoot);
        var commits = new CommitRepository(repositoryRoot);
        var refs = new BranchRefRepository(repositoryRoot);
        var active = new ActiveBranchRepository(repositoryRoot);
        var working = new WorkingIndexRepository(repositoryRoot);
        var main = new DimensionHistoryInitializer(objects, commits, refs, active)
                .initialize(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        var branches = new BranchService(commits, refs, active, working);
        var target = branches.create(new BranchName("redstone-test"), main.commit());
        var mutations = MutationDurabilityTracker.open(
                objects, new OriginStore(repositoryRoot), working, Runnable::run);
        SectionKey section = new SectionKey(1, 0, 1);
        long generation = mutations.registerSectionMutation(
                section, BranchSwitchRestorePublicationTest::airSection);
        mutations.recordBlockMutation(new BlockPosition(17, 2, 17), generation);
        WorkingIndexSnapshot captured = mutations.snapshot();
        var plan = branches.prepareSwitch(target.name());
        var restore = new PreparedRestore(
                main, target.commit(), Map.of(), Map.of(), Map.of(), Map.of());

        var publication = new BranchSwitchRestorePublication(
                branches, plan, mutations, captured);
        publication.publish(restore);

        assertEquals(target, branches.active());
        assertEquals(WorkingIndexSnapshot.empty(), mutations.snapshot());
        assertTrue(publication.isDurable());
    }

    private static SectionBlob airSection() {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }
}
