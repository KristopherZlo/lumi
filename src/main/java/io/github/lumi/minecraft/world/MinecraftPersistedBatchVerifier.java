package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
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
    private static final int MAX_PENDING_READS =
            StreamingPreparedWorldMutationSession.MAX_CHUNKS;
    private final ServerLevel level;
    private final Executor background;
    private final MinecraftStoredChunkAccess storedChunks;
    private final MinecraftEntityChunkCapture entityCapture;
    private final Map<ChunkCoordinate, Map<SectionKey, SectionBlob>> chunkTargets;
    private final Map<EntityChunkKey, EntityChunkBlob> entityTargets;
    private final List<ChunkCoordinate> chunks;
    private final List<EntityChunkKey> entityChunks;
    private final SimpleRegionStorage entityStorage;
    private CompletableFuture<Void> verification;
    private int nextChunk;
    private int nextEntityChunk;
    private int pendingChunkEnd;
    private int pendingEntityEnd;
    private volatile String phase = "persisted chunk verification";

    MinecraftPersistedBatchVerifier(
            ServerLevel level,
            Executor background,
            MinecraftStoredChunkAccess storedChunks,
            MinecraftEntityChunkCapture entityCapture,
            Map<ChunkCoordinate, Map<SectionKey, SectionBlob>> chunkTargets,
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
        while (System.nanoTime() < deadlineNanos) {
            if (verification == null) {
                if (nextChunk == chunks.size()
                        && nextEntityChunk == entityChunks.size()) {
                    return true;
                }
                verification = beginVerification();
            }
            if (!verification.isDone()) {
                return false;
            }
            MinecraftPersistenceFuture.join(
                    verification, "Restore persisted verification");
            nextChunk = pendingChunkEnd;
            nextEntityChunk = pendingEntityEnd;
            verification = null;
        }
        return nextChunk == chunks.size() && nextEntityChunk == entityChunks.size();
    }

    private CompletableFuture<Void> beginVerification() {
        pendingChunkEnd = readBatchEnd(
                chunks.size(), nextChunk, MAX_PENDING_READS);
        int remaining = MAX_PENDING_READS - (pendingChunkEnd - nextChunk);
        pendingEntityEnd = readBatchEnd(
                entityChunks.size(), nextEntityChunk, remaining);
        List<CompletableFuture<Void>> pending = new ArrayList<>(MAX_PENDING_READS);
        for (int index = nextChunk; index < pendingChunkEnd; index++) {
            ChunkCoordinate chunk = chunks.get(index);
            ChunkPos position = new ChunkPos(chunk.x(), chunk.z());
            pending.add(level.getChunkSource().chunkMap.read(position)
                    .thenApplyAsync(stored -> {
                        try {
                            String mismatch = stored.isEmpty()
                                    ? "chunk is absent"
                                    : storedChunks.mismatchRaw(
                                            position, stored.orElseThrow(),
                                            chunkTargets.get(chunk));
                            if (mismatch != null) {
                                throw new IOException(
                                        "Persisted Restore chunk mismatch: "
                                                + chunk + ": " + mismatch);
                            }
                            return (Void) null;
                        } catch (IOException failed) {
                            throw new CompletionException(failed);
                        }
                    }, background));
        }
        for (int index = nextEntityChunk; index < pendingEntityEnd; index++) {
            EntityChunkKey key = entityChunks.get(index);
            EntityChunkBlob expected = entityTargets.get(key);
            ChunkPos position = new ChunkPos(key.chunkX(), key.chunkZ());
            pending.add(entityStorage.read(position)
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
                    }, background));
        }
        phase = nextEntityChunk == pendingEntityEnd
                ? "persisted chunk verification"
                : nextChunk == pendingChunkEnd
                        ? "persisted entity verification"
                        : "persisted chunk/entity verification";
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    static int readBatchEnd(int total, int start, int capacity) {
        if (total < 0 || start < 0 || start > total || capacity < 0) {
            throw new IllegalArgumentException("Invalid persisted read window");
        }
        return (int) Math.min(total, (long) start + capacity);
    }

    String phase() {
        return phase;
    }
}
