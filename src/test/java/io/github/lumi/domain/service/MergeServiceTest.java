package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
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
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
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

class MergeServiceTest {
    @TempDir java.nio.file.Path repositoryRoot;

    @Test
    void writesTwoParentCommitWithCombinedSectionAndSourceWins() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        OriginStore origins = new OriginStore(repositoryRoot);
        CommitId base = commits.write(commit(objects, List.of(),
                section("minecraft:air", "minecraft:air", "minecraft:air"), "base"));
        CommitId current = commits.write(commit(objects, List.of(base),
                section("minecraft:stone", "minecraft:air", "minecraft:dirt"), "current"));
        CommitId source = commits.write(commit(objects, List.of(base),
                section("minecraft:air", "minecraft:gold_block", "minecraft:diamond_block"), "source"));
        var service = new MergeService(objects, commits, origins, new MerkleTreeEditor(objects));
        var request = new MergeService.Request(
                new BranchRef(new BranchName("main"), current, 2),
                new BranchRef(new BranchName("idea"), source, 1),
                new CommitAuthor(new UUID(0, 1), "Builder"), "Merge idea",
                Instant.EPOCH, new UUID(0, 2), Optional.empty());

        MergeService.Result result = service.prepare(request);

        Commit merge = commits.read(result.commit());
        SectionBlob merged = readSection(objects, merge);
        assertEquals(List.of(current, source), merge.parents());
        assertEquals(CommitKind.MERGE, merge.kind());
        assertEquals("minecraft:stone", merged.blockStates().get(0));
        assertEquals("minecraft:gold_block", merged.blockStates().get(1));
        assertEquals("minecraft:diamond_block", merged.blockStates().get(2));
        assertEquals(base, result.base());
        assertEquals(1, result.conflicts());
        assertEquals(new CommitStatistics(1, 0, 2, 0), result.statistics());
    }

    private static Commit commit(
            WorldObjectRepository objects,
            List<CommitId> parents,
            SectionBlob section,
            String message) throws Exception {
        var sectionId = objects.write(section);
        var chunk = objects.write(new ChunkTree(Map.of(0, sectionId), Optional.empty()));
        var region = objects.write(new RegionTree(Map.of(new ChunkInRegion(0, 0), chunk)));
        var tree = objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
        return new Commit(tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                message, Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 0, 0));
    }

    private static SectionBlob readSection(WorldObjectRepository objects, Commit commit)
            throws Exception {
        var dimension = objects.readDimension(commit.tree());
        var region = objects.readRegion(dimension.regions().get(new RegionCoordinate(0, 0)));
        var chunk = objects.readChunk(region.chunks().get(new ChunkInRegion(0, 0)));
        return objects.readSection(chunk.sections().get(0));
    }

    private static SectionBlob section(String... firstStates) {
        List<String> states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index = 0; index < firstStates.length; index++) {
            states.set(index, firstStates[index]);
        }
        return new SectionBlob(states, Map.of());
    }
}
