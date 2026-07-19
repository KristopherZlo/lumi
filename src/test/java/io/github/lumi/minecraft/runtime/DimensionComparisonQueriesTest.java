package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.MaterialDelta;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.ZoneRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionComparisonQueriesTest {
    @TempDir Path repository;

    @Test
    void rejectsACommitOutsideTheSelectedWorkspaceBeforeDecoding()
            throws Exception {
        UUID selectedWorkspace = new UUID(0, 1);
        CommitRepository commits = new CommitRepository(repository);
        var main = new DimensionHistoryInitializer(
                new WorldObjectRepository(repository), commits,
                new BranchRefRepository(repository),
                new ActiveBranchRepository(repository))
                .initialize(selectedWorkspace);
        Commit root = commits.read(main.commit());
        var foreign = commits.write(new Commit(
                root.tree(), List.of(), new CommitAuthor(new UUID(0, 2), "Other"),
                "Foreign", Instant.EPOCH, new UUID(0, 3), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0)));
        var queries = new DimensionComparisonQueries(
                repository, Runnable::run,
                new ZoneService(new ZoneRepository(repository)),
                () -> selectedWorkspace);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> queries.compare(main.commit(), foreign, () -> false).join());

        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void streamsExactBlocksAndReturnsTheirSummary() throws Exception {
        UUID workspace = new UUID(0, 5);
        WorldObjectRepository objects = new WorldObjectRepository(repository);
        CommitRepository commits = new CommitRepository(repository);
        var before = commits.write(commit(
                objects, workspace, "minecraft:air"));
        var after = commits.write(commit(
                objects, workspace, "minecraft:stone"));
        var queries = new DimensionComparisonQueries(
                repository, Runnable::run,
                new ZoneService(new ZoneRepository(repository)), () -> workspace);
        List<List<BlockChange>> batches = new ArrayList<>();

        var summary = queries.compare(
                before, after, () -> false, batches::add).join();

        assertEquals(List.of(List.of(new BlockChange(
                0, 0, 0, BlockChange.Kind.ADDED))), batches);
        assertEquals(1, summary.changedBlocks());
        assertEquals(Map.of(
                "minecraft:stone", new MaterialDelta(0, 1)),
                summary.materials());
    }

    private static Commit commit(
            WorldObjectRepository objects, UUID workspace, String firstState)
            throws Exception {
        var blocks = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        blocks.set(0, firstState);
        var section = objects.write(new SectionBlob(blocks, Map.of()));
        var chunk = objects.write(new ChunkTree(
                Map.of(0, section), Optional.empty()));
        var region = objects.write(new RegionTree(
                Map.of(new ChunkInRegion(0, 0), chunk)));
        var tree = objects.write(new DimensionTree(
                Map.of(new RegionCoordinate(0, 0), region)));
        return new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 6), "Builder"),
                "Save", Instant.EPOCH, workspace, Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 1, 0));
    }
}
