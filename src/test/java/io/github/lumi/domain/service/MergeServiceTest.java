package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
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
    void rejectsSourceFromAnotherWorkspace() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        OriginStore origins = new OriginStore(repositoryRoot);
        UUID currentWorkspace = new UUID(0, 2);
        UUID foreignWorkspace = new UUID(0, 9);
        CommitId base = commits.write(commit(
                objects.write(new DimensionTree(Map.of())), List.of(), currentWorkspace));
        CommitId current = commits.write(commit(
                objects.write(new DimensionTree(Map.of())), List.of(base), currentWorkspace));
        CommitId source = commits.write(commit(
                objects.write(new DimensionTree(Map.of())), List.of(base), foreignWorkspace));
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var currentRef = refs.create(new BranchName("main"), current);
        var sourceRef = refs.create(new BranchName("foreign"), source);
        MergeService service = new MergeService(
                objects, commits, origins, new MerkleTreeEditor(objects));

        assertThrows(java.io.IOException.class, () -> service.prepare(
                new MergeService.Request(
                        currentRef, sourceRef, author(), "Merge foreign", Instant.EPOCH,
                        currentWorkspace, Optional.empty())));
    }

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

    @Test
    void sourceWinsConflictingEntityMoveWithoutDuplicatingUuid() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        OriginStore origins = new OriginStore(repositoryRoot);
        UUID entityId = new UUID(0, 9);
        CommitId base = commits.write(entityCommit(objects, List.of(), entityId, 0, 0));
        CommitId current = commits.write(entityCommit(objects, List.of(base), entityId, 1, 1));
        CommitId source = commits.write(entityCommit(objects, List.of(base), entityId, 2, 2));
        var service = new MergeService(objects, commits, origins, new MerkleTreeEditor(objects));

        var result = service.prepare(new MergeService.Request(
                new BranchRef(new BranchName("main"), current, 1),
                new BranchRef(new BranchName("idea"), source, 1),
                new CommitAuthor(new UUID(0, 1), "Builder"), "Merge move",
                Instant.EPOCH, new UUID(0, 2), Optional.empty()));

        var dimension = objects.readDimension(commits.read(result.commit()).tree());
        var region = objects.readRegion(dimension.regions().get(new RegionCoordinate(0, 0)));
        List<EntityState> merged = new ArrayList<>();
        for (int chunkX = 0; chunkX < 3; chunkX++) {
            var chunk = objects.readChunk(region.chunks().get(new ChunkInRegion(chunkX, 0)));
            merged.addAll(objects.readEntities(chunk.entities().orElseThrow()).entities());
        }
        assertEquals(List.of(entity(entityId, 2)), merged);
        assertEquals(1, result.conflicts());
    }

    @Test
    void sourceWinsConflictingPlayerSpawn() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        UUID player = new UUID(0, 7);
        CommitId base = commits.write(spawnCommit(
                objects, List.of(), player, new PlayerSpawn(0, 64, 0, 0, 0, false)));
        CommitId current = commits.write(spawnCommit(
                objects, List.of(base), player, new PlayerSpawn(5, 70, 5, 0, 0, false)));
        PlayerSpawn sourceSpawn = new PlayerSpawn(-4, 80, 9, 90, 8, true);
        CommitId source = commits.write(spawnCommit(
                objects, List.of(base), player, sourceSpawn));
        var service = new MergeService(
                objects, commits, new OriginStore(repositoryRoot),
                new MerkleTreeEditor(objects));

        var result = service.prepare(new MergeService.Request(
                new BranchRef(new BranchName("main"), current, 1),
                new BranchRef(new BranchName("idea"), source, 1),
                new CommitAuthor(new UUID(0, 1), "Builder"), "Merge spawn",
                Instant.EPOCH, new UUID(0, 2), Optional.empty()));

        assertEquals(Map.of(player, sourceSpawn),
                commits.read(result.commit()).playerSpawns());
        assertEquals(1, result.conflicts());
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<CommitId> parents,
            UUID workspace) {
        return new Commit(tree, parents, author(), "Save", Instant.EPOCH,
                workspace, Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(0, 0, 0, 0));
    }

    private static CommitAuthor author() {
        return new CommitAuthor(new UUID(0, 1), "Builder");
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

    private static Commit entityCommit(
            WorldObjectRepository objects, List<CommitId> parents,
            UUID id, int entityChunk, int state) throws Exception {
        Map<ChunkInRegion, io.github.lumi.domain.model.ObjectId> chunks = new java.util.HashMap<>();
        for (int chunkX = 0; chunkX < 3; chunkX++) {
            EntityChunkBlob entities = new EntityChunkBlob(chunkX == entityChunk
                    ? List.of(entity(id, state)) : List.of());
            chunks.put(new ChunkInRegion(chunkX, 0), objects.write(
                    new ChunkTree(Map.of(), Optional.of(objects.write(entities)))));
        }
        var region = objects.write(new RegionTree(chunks));
        var tree = objects.write(new DimensionTree(Map.of(new RegionCoordinate(0, 0), region)));
        return new Commit(tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                "Move", Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 3, 0, 1));
    }

    private static EntityState entity(UUID id, int state) {
        return new EntityState(id, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) state}));
    }

    private static Commit spawnCommit(
            WorldObjectRepository objects, List<CommitId> parents,
            UUID player, PlayerSpawn spawn) throws Exception {
        var tree = objects.write(new DimensionTree(Map.of()));
        return new Commit(tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                "Spawn", Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0),
                Map.of(player, spawn));
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
