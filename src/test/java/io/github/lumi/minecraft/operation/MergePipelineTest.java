package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.MergeService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergePipelineTest {
    private static final SectionKey KEY = new SectionKey(0, 0, 0);
    @TempDir java.nio.file.Path repositoryRoot;

    @Test
    void publishesMergeRefOnlyAfterVerifiedApply() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        OriginStore origins = new OriginStore(repositoryRoot);
        CommitId base = commits.write(commit(objects, List.of(), section("minecraft:air")));
        CommitId current = commits.write(commit(objects, List.of(base), section("minecraft:stone")));
        CommitId source = commits.write(commit(objects, List.of(base),
                section("minecraft:air", "minecraft:gold_block")));
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var currentRef = refs.create(new BranchName("main"), current);
        var sourceRef = refs.create(new BranchName("idea"), source);
        var mergeService = new MergeService(
                objects, commits, origins, new MerkleTreeEditor(objects));
        var result = mergeService.prepare(new MergeService.Request(
                currentRef, sourceRef, new CommitAuthor(new UUID(0, 1), "Builder"),
                "Merge idea", Instant.EPOCH, new UUID(0, 2), Optional.empty()));
        var restore = new RestoreService(objects, commits, origins)
                .prepare(currentRef, result.commit());
        RecordingApply world = new RecordingApply();
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        RestoreOperation operation = RestoreOperation.startMerge(
                restore, world, new BranchRefRestorePublication(refs), journals,
                UUID.randomUUID(), RestoreStateListener.NONE);

        assertEquals(current, refs.read(currentRef.name()).orElseThrow().commit());
        operation.tick(Long.MAX_VALUE);
        assertEquals(current, refs.read(currentRef.name()).orElseThrow().commit());
        operation.tick(Long.MAX_VALUE);

        assertEquals(result.commit(), refs.read(currentRef.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
        assertEquals("minecraft:stone", world.target.sections().get(KEY).blockStates().get(0));
        assertEquals("minecraft:gold_block", world.target.sections().get(KEY).blockStates().get(1));
    }

    private static Commit commit(
            WorldObjectRepository objects, List<CommitId> parents, SectionBlob section)
            throws Exception {
        var sectionId = objects.write(section);
        var chunk = objects.write(new ChunkTree(Map.of(0, sectionId), Optional.empty()));
        var region = objects.write(new RegionTree(Map.of(new ChunkInRegion(0, 0), chunk)));
        var tree = objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
        return new Commit(tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                "Save", Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 0, 0));
    }

    private static SectionBlob section(String... statesAtStart) {
        List<String> states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index = 0; index < statesAtStart.length; index++) states.set(index, statesAtStart[index]);
        return new SectionBlob(states, Map.of());
    }

    private static final class RecordingApply implements WorldStateApply {
        private State target;
        @Override public PreparedState prepare(State state) {
            if (target == null) target = state;
            return new Prepared(state);
        }
        @Override public ApplySession begin(PreparedState ignored) { return new Session(); }
        private record Prepared(State source) implements PreparedState { }
        private static final class Session implements ApplySession {
            @Override public boolean applyUntil(long deadlineNanos) { return true; }
            @Override public Verification verifyUntil(long deadlineNanos) { return Verification.VERIFIED; }
            @Override public boolean repairUntil(long deadlineNanos) { return true; }
            @Override public void restartVerification() { }
        }
    }
}
