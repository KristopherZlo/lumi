package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockOnlyRestoreServiceTest {
    @TempDir java.nio.file.Path repositoryRoot;

    @Test
    void composesTargetBlocksWithCheckpointEntitiesAndTargetSpawn() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        UUID workspace = new UUID(0, 1);
        PlayerSpawn targetSpawn = new PlayerSpawn(1, 70, 2, 90, 0, true);
        CommitId target = commits.write(commit(
                objects, "minecraft:stone", 1, targetSpawn, workspace));
        CommitId checkpoint = commits.write(commit(
                objects, "minecraft:dirt", 2,
                new PlayerSpawn(9, 80, 9, 0, 0, false), workspace));
        BlockOnlyRestoreService service = new BlockOnlyRestoreService(
                objects, commits, new OriginStore(repositoryRoot));

        CommitId result = service.compose(
                checkpoint, target, author(), Instant.EPOCH, ignored -> { });

        Commit composite = commits.read(result);
        ChunkTree chunk = chunk(objects, composite);
        assertEquals(List.of(target, checkpoint), composite.parents());
        assertEquals(CommitKind.RESTORE, composite.kind());
        assertEquals(new CommitStatistics(0, 1, 0, 1), composite.statistics());
        assertEquals(Map.of(new UUID(0, 7), targetSpawn), composite.playerSpawns());
        assertEquals("minecraft:stone",
                objects.readSection(chunk.sections().get(0)).blockStates().get(0));
        assertArrayEquals(new byte[] {2}, objects.readEntities(
                chunk.entities().orElseThrow()).entities().getFirst().nbt().bytes());
        PreparedRestore prepared = new RestoreService(
                objects, commits, new OriginStore(repositoryRoot)).prepare(
                        new BranchRef(new BranchName("main"), checkpoint, 1),
                        checkpoint, result);
        assertEquals(1, prepared.sections().size());
        assertTrue(prepared.entities().isEmpty());
        CommitId matchingEntities = commits.write(commit(
                objects, "minecraft:dirt", 1, targetSpawn, workspace));
        assertEquals(target, service.compose(
                matchingEntities, target, author(), Instant.EPOCH, ignored -> { }));
    }

    private static Commit commit(
            WorldObjectRepository objects,
            String state,
            int entityState,
            PlayerSpawn spawn,
            UUID workspace) throws Exception {
        var section = objects.write(new SectionBlob(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, state), Map.of()));
        UUID entityId = new UUID(0, 8);
        var entities = objects.write(new EntityChunkBlob(List.of(new EntityState(
                entityId, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) entityState})))));
        var chunk = objects.write(new ChunkTree(
                Map.of(0, section), Optional.of(entities)));
        var region = objects.write(new RegionTree(
                Map.of(new ChunkInRegion(0, 0), chunk)));
        var tree = objects.write(new DimensionTree(
                Map.of(new RegionCoordinate(0, 0), region)));
        return new Commit(
                tree, List.of(), author(), "Tower", Instant.EPOCH, workspace,
                Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(1, 1, SectionBlob.BLOCK_COUNT, 1),
                Map.of(new UUID(0, 7), spawn));
    }

    private static ChunkTree chunk(
            WorldObjectRepository objects, Commit commit) throws Exception {
        DimensionTree dimension = objects.readDimension(commit.tree());
        RegionTree region = objects.readRegion(
                dimension.regions().get(new RegionCoordinate(0, 0)));
        return objects.readChunk(region.chunks().get(new ChunkInRegion(0, 0)));
    }

    private static CommitAuthor author() {
        return new CommitAuthor(new UUID(0, 9), "Builder");
    }
}
