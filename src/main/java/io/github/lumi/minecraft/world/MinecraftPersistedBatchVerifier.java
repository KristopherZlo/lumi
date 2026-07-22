package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** Rereads one synchronized Restore batch without materializing whole-world state. */
final class MinecraftPersistedBatchVerifier {
    private final ServerLevel level;
    private final Executor background;
    private final MinecraftStoredChunkAccess storedChunks;
    private final MinecraftEntityChunkCapture entityCapture;
    private final Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> chunkTargets;
    private final Map<EntityChunkKey, EntityChunkBlob> entityTargets;
    private final List<ChunkCoordinate> chunks;
    private final List<EntityChunkKey> entityChunks;
    private final SimpleRegionStorage entityStorage;
    private CompletableFuture<Void> verification;
    private volatile String phase = "persisted chunk verification";

    MinecraftPersistedBatchVerifier(
            ServerLevel level,
            Executor background,
            MinecraftStoredChunkAccess storedChunks,
            MinecraftEntityChunkCapture entityCapture,
            Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> chunkTargets,
            Map<EntityChunkKey, EntityChunkBlob> entityTargets,
            List<ChunkCoordinate> chunks,
            List<EntityChunkKey> entityChunks,
            SimpleRegionStorage entityStorage) {
        this.level = Objects.requireNonNull(level, "level");
        this.background = Objects.requireNonNull(background, "background");
        this.storedChunks = Objects.requireNonNull(storedChunks, "storedChunks");
        this.entityCapture = Objects.requireNonNull(entityCapture, "entityCapture");
        this.chunkTargets = Objects.requireNonNull(chunkTargets, "chunkTargets");
        this.entityTargets = Objects.requireNonNull(entityTargets, "entityTargets");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.entityChunks = Objects.requireNonNull(entityChunks, "entityChunks");
        this.entityStorage = entityStorage;
        if (!entityChunks.isEmpty() && entityStorage == null) {
            throw new IllegalArgumentException("Entity storage is required for entity readback");
        }
    }

    boolean advanceUntil(long deadlineNanos) throws IOException {
        if (verification == null) {
            verification = beginVerification();
        }
        if (!verification.isDone()) {
            return false;
        }
        MinecraftPersistenceFuture.join(verification, "Restore persisted verification");
        return true;
    }

    private CompletableFuture<Void> beginVerification() {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (ChunkCoordinate chunk : chunks) {
            ChunkPos position = new ChunkPos(chunk.x(), chunk.z());
            result = result.thenCompose(ignored -> {
                phase = "persisted chunk verification";
                return level.getChunkSource().chunkMap.read(position);
            })
                    .thenApplyAsync(stored -> {
                        try {
                            if (stored.isEmpty() || !storedChunks.matches(
                                    position, stored.orElseThrow(), chunkTargets.get(chunk))) {
                                throw new IOException(
                                        "Persisted Restore chunk mismatch: " + chunk);
                            }
                            return (Void) null;
                        } catch (IOException failed) {
                            throw new CompletionException(failed);
                        }
                    }, background);
        }
        for (EntityChunkKey key : entityChunks) {
            EntityChunkBlob expected = entityTargets.get(key);
            ChunkPos position = new ChunkPos(key.chunkX(), key.chunkZ());
            result = result.thenCompose(ignored -> {
                phase = "persisted entity verification";
                return entityStorage.read(position);
            })
                    .thenApplyAsync(stored -> {
                        try {
                            if (!expected.equals(entityCapture.captureStored(key, stored))) {
                                throw new IOException(
                                        "Persisted Restore entity mismatch: " + key);
                            }
                            return (Void) null;
                        } catch (IOException failed) {
                            throw new CompletionException(failed);
                        }
                    }, background);
        }
        return result;
    }

    String phase() {
        return phase;
    }
}
