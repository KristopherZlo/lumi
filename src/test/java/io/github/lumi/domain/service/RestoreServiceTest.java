package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreServiceTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void missingTargetPathResolvesThroughOrigin() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        SectionKey key = new SectionKey(0, 0, 0);
        var originId = objects.write(section("minecraft:air"));
        new OriginStore(repositoryRoot).register(key, originId);
        var emptyTree = objects.write(new DimensionTree(Map.of()));
        var target = commits.write(commit(emptyTree, List.of()));
        var changedSection = objects.write(section("minecraft:stone"));
        var chunk = objects.write(new ChunkTree(Map.of(0, changedSection), Optional.empty()));
        var region = objects.write(new RegionTree(Map.of(new ChunkInRegion(0, 0), chunk)));
        var changedTree = objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
        var current = commits.write(commit(changedTree, List.of(target)));
        BranchRef currentRef = new BranchRef(new BranchName("main"), current, 1);

        PreparedRestore prepared = new RestoreService(objects, commits, new OriginStore(repositoryRoot))
                .prepare(currentRef, target);

        assertEquals(Map.of(key, section("minecraft:air")), prepared.sections());
        assertEquals(target, prepared.targetCommit());
    }

    private static SectionBlob section(String state) {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, state)), Map.of());
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents) {
        return new Commit(tree, parents,
                new CommitAuthor(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Builder"),
                "Save", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 1, 0));
    }
}
