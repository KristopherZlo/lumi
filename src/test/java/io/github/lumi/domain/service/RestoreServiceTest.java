package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
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
        assertEquals(Map.of(key, section("minecraft:stone")), prepared.returnSections());
        assertEquals(target, prepared.targetCommit());
    }

    @Test
    void partialRestoreChangesOnlyBlocksInsideTheBox() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var air = objects.write(section("minecraft:air"));
        var stone = objects.write(section("minecraft:stone"));
        var targetChunk = objects.write(new ChunkTree(
                Map.of(0, air, 1, air), Optional.empty()));
        var currentChunk = objects.write(new ChunkTree(
                Map.of(0, stone, 1, stone), Optional.empty()));
        var target = commits.write(commit(tree(objects, targetChunk), List.of()));
        var current = commits.write(commit(tree(objects, currentChunk), List.of(target)));
        var currentRef = new BranchRef(new BranchName("main"), current, 1);

        PreparedRestore prepared = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot)).preparePartial(
                        currentRef, target, new BlockBox(0, 0, 0, 0, 0, 0), false);

        SectionBlob expected = section("minecraft:stone");
        var blocks = new ArrayList<>(expected.blockStates());
        blocks.set(0, "minecraft:air");
        expected = new SectionBlob(blocks, Map.of());
        assertEquals(Map.of(new SectionKey(0, 0, 0), expected), prepared.sections());
        assertEquals(Map.of(new SectionKey(0, 0, 0), section("minecraft:stone")),
                prepared.returnSections());
        assertEquals(Map.of(), prepared.entities());

        PreparedRestore outside = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot)).preparePartial(
                        currentRef, target, new BlockBox(0, 0, 0, 15, 15, 15), true);
        assertEquals(Map.of(new SectionKey(0, 1, 0), section("minecraft:air")),
                outside.sections());

        var gold = objects.write(section("minecraft:gold_block"));
        var checkpointChunk = objects.write(new ChunkTree(
                Map.of(0, gold, 1, stone), Optional.empty()));
        var checkpoint = commits.write(commit(
                tree(objects, checkpointChunk), List.of(current)));
        PreparedRestore fromCheckpoint = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot)).preparePartial(
                        currentRef, checkpoint, target,
                        new BlockBox(0, 0, 0, 0, 0, 0), false);
        assertEquals("minecraft:gold_block", fromCheckpoint.returnSections()
                .get(new SectionKey(0, 0, 0)).blockStates().get(0));
        assertEquals(currentRef, fromCheckpoint.expectedRef());

        PreparedRestore fullFromCheckpoint = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot)).prepare(
                        currentRef, checkpoint, target);
        assertEquals("minecraft:gold_block", fullFromCheckpoint.returnSections()
                .get(new SectionKey(0, 0, 0)).blockStates().get(0));
    }

    @Test
    void fullRestoreCarriesPlayerSpawnsButPartialRestoreDoesNot() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID player = UUID.fromString("30000000-0000-0000-0000-000000000003");
        PlayerSpawn before = new PlayerSpawn(1, 64, 1, 0.0F, 0.0F, false);
        PlayerSpawn after = new PlayerSpawn(9, 70, -4, 90.0F, 12.0F, true);
        var target = commits.write(commit(tree, List.of(), Map.of(player, after)));
        var current = commits.write(commit(tree, List.of(target), Map.of(player, before)));
        var currentRef = new BranchRef(new BranchName("main"), current, 1);
        RestoreService service = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot));

        PreparedRestore full = service.prepare(currentRef, target);
        PreparedRestore partial = service.preparePartial(
                currentRef, target, new BlockBox(0, 0, 0, 15, 15, 15), false);

        assertEquals(Map.of(player, after), full.playerSpawns());
        assertEquals(Map.of(player, before), full.returnPlayerSpawns());
        assertEquals(true, full.restorePlayerSpawns());
        assertEquals(false, partial.restorePlayerSpawns());
    }

    @Test
    void fullRestoreCanExplicitlyExcludeDurableEntities() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        EntityChunkBlob before = new EntityChunkBlob(List.of());
        EntityChunkBlob after = new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 8), "minecraft:armor_stand", new CanonicalNbt(new byte[] {1}))));
        var beforeChunk = objects.write(new ChunkTree(
                Map.of(), Optional.of(objects.write(before))));
        var afterChunk = objects.write(new ChunkTree(
                Map.of(), Optional.of(objects.write(after))));
        var target = commits.write(commit(tree(objects, afterChunk), List.of()));
        var current = commits.write(commit(tree(objects, beforeChunk), List.of(target)));
        var currentRef = new BranchRef(new BranchName("main"), current, 1);
        RestoreService service = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot));

        PreparedRestore included = service.prepare(currentRef, target);
        PreparedRestore excluded = service.prepareWithoutEntities(currentRef, target);

        assertEquals(Map.of(new EntityChunkKey(0, 0), after), included.entities());
        assertEquals(Map.of(), excluded.entities());
        assertEquals(Map.of(), excluded.returnEntities());
    }

    @Test
    void rejectsTargetFromAnotherWorkspace() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID activeWorkspace = new UUID(0, 2);
        UUID foreignWorkspace = new UUID(0, 3);
        var target = commits.write(new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"), "Foreign",
                Instant.EPOCH, foreignWorkspace, Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(0, 0, 0, 0)));
        RestoreService service = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot));

        assertThrows(IOException.class,
                () -> service.requireTargetInWorkspace(target, activeWorkspace));
    }

    @Test
    void zoneRestoreSelectsExactCellsAndTheirEntityColumns() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        EntityChunkBlob beforeEntities = new EntityChunkBlob(List.of());
        EntityChunkBlob afterEntities = new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 9), "minecraft:armor_stand", new CanonicalNbt(new byte[] {1}))));
        var targetChunk = objects.write(new ChunkTree(
                Map.of(0, objects.write(section("minecraft:air")),
                        1, objects.write(section("minecraft:air"))),
                Optional.of(objects.write(afterEntities))));
        var currentChunk = objects.write(new ChunkTree(
                Map.of(0, objects.write(section("minecraft:stone")),
                        1, objects.write(section("minecraft:stone"))),
                Optional.of(objects.write(beforeEntities))));
        var target = commits.write(commit(tree(objects, targetChunk), List.of()));
        var current = commits.write(commit(tree(objects, currentChunk), List.of(target)));
        var currentRef = new BranchRef(new BranchName("main"), current, 1);
        Zone zone = new Zone(new UUID(0, 4), new UUID(0, 2), "Cell", 0,
                java.util.Set.of(new SectionKey(0, 1, 0)), java.util.Set.of());

        PreparedRestore prepared = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot))
                .prepareZone(currentRef, target, new ZoneScope(zone));

        assertEquals(Map.of(new SectionKey(0, 1, 0), section("minecraft:air")),
                prepared.sections());
        assertEquals(Map.of(new EntityChunkKey(0, 0), afterEntities), prepared.entities());
        assertEquals(false, prepared.restorePlayerSpawns());
    }

    private static io.github.lumi.domain.model.ObjectId tree(
            WorldObjectRepository objects,
            io.github.lumi.domain.model.ObjectId chunk) throws IOException {
        var region = objects.write(new RegionTree(Map.of(new ChunkInRegion(0, 0), chunk)));
        return objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
    }

    private static SectionBlob section(String state) {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, state)), Map.of());
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents) {
        return commit(tree, parents, Map.of());
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents,
            Map<UUID, PlayerSpawn> spawns) {
        return new Commit(tree, parents,
                new CommitAuthor(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Builder"),
                "Save", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(1, 0, 1, 0), spawns);
    }
}
