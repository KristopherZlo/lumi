package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.lumi.domain.model.ObjectChange;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void resolvesSparseOriginAndReturnsOnlyChangedObjectIds() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        OriginStore origins = new OriginStore(repositoryRoot);
        SectionKey key = new SectionKey(0, 0, 0);
        var origin = objects.write(section("minecraft:air"));
        var stone = objects.write(section("minecraft:stone"));
        origins.register(key, origin);
        CommitId empty = commits.write(commit(objects.write(new DimensionTree(Map.of()))));
        CommitId explicitOrigin = commits.write(commit(tree(objects, origin)));
        CommitId changed = commits.write(commit(tree(objects, stone)));
        CompareService compare = new CompareService(objects, commits, origins);

        assertTrue(compare.compare(empty, explicitOrigin).isEmpty());
        WorldDifference difference = compare.compare(empty, changed);

        assertEquals(Map.of(key, new ObjectChange(origin, stone)), difference.sections());
        assertTrue(difference.entities().isEmpty());
        assertEquals(1, difference.changeCount());
        Zone included = new Zone(new UUID(0, 3), new UUID(0, 2), "Included", 0,
                java.util.Set.of(key), java.util.Set.of());
        Zone excluded = new Zone(new UUID(0, 4), new UUID(0, 2), "Excluded", 0,
                java.util.Set.of(new SectionKey(1, 0, 0)), java.util.Set.of());
        assertEquals(difference, compare.compare(empty, changed, new ZoneScope(included)));
        assertTrue(compare.compare(empty, changed, new ZoneScope(excluded)).isEmpty());
        assertThrows(CancellationException.class,
                () -> compare.compare(empty, changed, () -> true));
    }

    private static io.github.lumi.domain.model.ObjectId tree(
            WorldObjectRepository objects,
            io.github.lumi.domain.model.ObjectId section) throws Exception {
        var chunk = objects.write(new ChunkTree(Map.of(0, section), Optional.empty()));
        var region = objects.write(new RegionTree(Map.of(new ChunkInRegion(0, 0), chunk)));
        return objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
    }

    private static SectionBlob section(String state) {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, state)), Map.of());
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree) {
        return new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"),
                "Save", Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0));
    }
}
