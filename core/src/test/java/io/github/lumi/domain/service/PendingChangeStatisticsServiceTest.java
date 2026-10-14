package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.PendingChangeStatistics;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingChangeStatisticsServiceTest {
    @TempDir Path repository;

    @Test
    void countsDirectionalBlocksForWorkspaceAndOverlappingZones()
            throws Exception {
        UUID workspace = new UUID(0, 1);
        SectionKey key = new SectionKey(-1, 4, 0);
        var objects = new WorldObjectRepository(repository);
        var commits = new CommitRepository(repository);
        var origins = new OriginStore(repository);
        var head = new DimensionHistoryInitializer(
                objects, commits, new BranchRefRepository(repository),
                new ActiveBranchRepository(repository))
                .initialize(workspace).commit();
        origins.register(key, objects.write(section(
                "minecraft:air", "minecraft:stone", "minecraft:dirt")));
        Zone included = new Zone(
                new UUID(0, 2), workspace, "Clock", 0x44AAFF,
                Set.of(key), Set.of());
        Zone excluded = new Zone(
                new UUID(0, 3), workspace, "Garden", 0x55AA44,
                Set.of(new SectionKey(2, 4, 2)), Set.of());

        var result = new PendingChangeStatisticsService(
                objects, commits, origins).calculate(
                        head,
                        Map.of(key, section(
                                "minecraft:stone", "minecraft:air",
                                "minecraft:grass_block")),
                        List.of(included, excluded));

        var expected = new PendingChangeStatistics(1, 1, 1);
        assertEquals(expected, result.workspace());
        assertEquals(expected, result.zones().get(included.id()));
        assertEquals(PendingChangeStatistics.NONE,
                result.zones().get(excluded.id()));
    }

    @Test
    void resolvesTheSavedSectionFromTheHeadTree() throws Exception {
        UUID workspace = new UUID(0, 4);
        SectionKey key = new SectionKey(33, 5, -33);
        var objects = new WorldObjectRepository(repository);
        var commits = new CommitRepository(repository);
        var refs = new BranchRefRepository(repository);
        var initial = new DimensionHistoryInitializer(
                objects, commits, refs,
                new ActiveBranchRepository(repository))
                .initialize(workspace);
        var parent = commits.read(initial.commit());
        var savedTree = new MerkleTreeEditor(objects).update(
                java.util.Optional.of(parent.tree()),
                Map.of(key, objects.write(section("minecraft:stone"))));
        var saved = commits.write(new Commit(
                savedTree, List.of(initial.commit()),
                new io.github.lumi.domain.model.CommitAuthor(
                        new UUID(0, 5), "Builder"),
                "Saved", Instant.EPOCH, workspace, java.util.Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 1, 0)));

        var result = new PendingChangeStatisticsService(
                objects, commits, new OriginStore(repository)).calculate(
                        saved,
                        Map.of(key, section("minecraft:air")),
                        List.of());

        assertEquals(new PendingChangeStatistics(0, 1, 0),
                result.workspace());
    }

    @Test
    void stopsComparingWhenCancelled() throws Exception {
        UUID workspace = new UUID(0, 6);
        SectionKey key = new SectionKey(0, 4, 0);
        var objects = new WorldObjectRepository(repository);
        var commits = new CommitRepository(repository);
        var origins = new OriginStore(repository);
        var head = new DimensionHistoryInitializer(
                objects, commits, new BranchRefRepository(repository),
                new ActiveBranchRepository(repository))
                .initialize(workspace).commit();
        origins.register(key, objects.write(section("minecraft:stone")));
        AtomicInteger checks = new AtomicInteger();

        assertThrows(CancellationException.class, () ->
                new PendingChangeStatisticsService(
                        objects, commits, origins).calculate(
                                head, Map.of(key, section("minecraft:air")),
                                List.of(), () -> checks.incrementAndGet() > 2));
    }

    private static SectionBlob section(String... leadingStates) {
        var states = new ArrayList<String>(
                java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index = 0; index < leadingStates.length; index++) {
            states.set(index, leadingStates[index]);
        }
        return new SectionBlob(states, Map.of());
    }
}
