package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;

/** Minimal Minecraft mutation/readback port used by the deadline-bounded apply cursor. */
public interface PreparedWorldAccess {
    SectionApplyResult applySection(SectionKey key, DecodedSection section) throws IOException;

    ChunkSyncResult finishChunk(
            ChunkCoordinate chunk,
            List<SectionApplyResult> sections,
            boolean blockEntitiesChanged) throws IOException;

    default CompletableFuture<StoredChunkApplyResult> applyStoredChunk(
            ChunkCoordinate chunk,
            Map<SectionKey, DecodedSection> sections,
            boolean entitiesChanged) {
        return CompletableFuture.completedFuture(StoredChunkApplyResult.FALLBACK);
    }

    default CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>>
            applyStoredChunks(
                    Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> chunks,
                    Set<ChunkCoordinate> entityChunks) {
        Map<ChunkCoordinate, CompletableFuture<StoredChunkApplyResult>> pending =
                new LinkedHashMap<>();
        chunks.forEach((chunk, sections) -> pending.put(chunk,
                applyStoredChunk(chunk, sections, entityChunks.contains(chunk))));
        return CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<ChunkCoordinate, StoredChunkApplyResult> results =
                            new LinkedHashMap<>();
                    pending.forEach((chunk, result) -> results.put(chunk, result.join()));
                    return Map.copyOf(results);
                });
    }

    /**
     * Canonically rewrites eligible unloaded entity chunks to an intermediate
     * removal state, returning the keys made durable without loading them.
     */
    default CompletableFuture<Set<EntityChunkKey>> cleanStoredEntities(
            PreparedMinecraftState target) {
        return CompletableFuture.completedFuture(Set.of());
    }

    /** Prevents queued legacy payloads for these chunks from entering the live index. */
    DimensionFreeze.Lease suppressEntityLoads(Set<EntityChunkKey> keys);

    default String mutationPhase() {
        return "loaded apply";
    }

    default boolean finishLighting() throws IOException {
        return true;
    }

    WorldPersistenceSession beginPersistence(
            PreparedMinecraftState target,
            Set<ChunkCoordinate> alreadyStored,
            boolean playerSpawnsIncluded);

    /** Stages one verified write window without forcing its backing stores. */
    default WorldPersistenceSession beginPersistenceStage(
            PreparedMinecraftState writeTarget,
            Set<ChunkCoordinate> alreadyStored) {
        return beginPersistence(writeTarget, alreadyStored, false);
    }

    /** Writes the last window, then forces and rereads the complete Restore target. */
    default WorldPersistenceSession beginPersistenceCommit(
            PreparedMinecraftState writeTarget,
            WorldStateApply.State verificationTarget,
            List<SectionKey> verificationSections,
            List<EntityChunkKey> verificationEntities,
            Set<ChunkCoordinate> alreadyStored) {
        return beginPersistence(writeTarget, alreadyStored, false);
    }

    List<Integer> blockEntityIndexes(SectionKey key) throws IOException;

    void removeBlockEntity(SectionKey key, int localIndex) throws IOException;

    void loadBlockEntity(SectionKey key, int localIndex, CompoundTag nbt) throws IOException;

    SectionBlob captureSection(SectionKey key) throws IOException;

    default boolean matchesSection(
            SectionKey key, SectionBlob source, DecodedSection target) throws IOException {
        return source.equals(captureSection(key));
    }

    List<UUID> durableEntityIds(EntityChunkKey key) throws IOException;

    void removeEntity(UUID id) throws IOException;

    void removeEntity(EntityChunkKey key, UUID id) throws IOException;

    void addEntity(EntityChunkKey key, DecodedEntity entity) throws IOException;

    EntityChunkBlob captureEntities(EntityChunkKey key) throws IOException;

    void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) throws IOException;

    boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) throws IOException;
}
