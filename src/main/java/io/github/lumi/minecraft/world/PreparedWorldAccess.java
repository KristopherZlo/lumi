package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.IOException;
import java.util.List;
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

    default String mutationPhase() {
        return "loaded apply";
    }

    List<Integer> blockEntityIndexes(SectionKey key) throws IOException;

    void removeBlockEntity(SectionKey key, int localIndex) throws IOException;

    void loadBlockEntity(SectionKey key, int localIndex, CompoundTag nbt) throws IOException;

    SectionBlob captureSection(SectionKey key) throws IOException;

    List<UUID> durableEntityIds(EntityChunkKey key) throws IOException;

    void removeEntity(EntityChunkKey key, UUID id) throws IOException;

    void addEntity(EntityChunkKey key, DecodedEntity entity) throws IOException;

    EntityChunkBlob captureEntities(EntityChunkKey key) throws IOException;

    void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) throws IOException;

    boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) throws IOException;
}
