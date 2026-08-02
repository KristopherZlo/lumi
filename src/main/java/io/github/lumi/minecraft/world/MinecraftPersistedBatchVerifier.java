package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.DeadlineFuture;
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

/** Pipelines bounded reread windows over one synchronized Restore target. */
final class MinecraftPersistedBatchVerifier {
    private static final int MAX_PENDING_READS =
            StreamingPreparedWorldMutationSession.MAX_CHUNKS;
    private final ServerLevel level;
    private final Executor background;
    private final MinecraftStoredChunkAccess storedChunks;
    private final MinecraftEntityChunkCapture entityCapture;
    private final WorldStateApply.State target;
    private final List<SectionKey> sections;
    private final List<EntityChunkKey> entityChunks;
    private final SimpleRegionStorage entityStorage;
    private CompletableFuture<Void> verification;
    private int nextSection;
    private int nextEntityChunk;
    private volatile String phase = "persisted chunk verification";
    private volatile boolean closed;

    MinecraftPersistedBatchVerifier(
            ServerLevel level,
            Executor background,
            MinecraftStoredChunkAccess storedChunks,
            MinecraftEntityChunkCapture entityCapture,
            WorldStateApply.State target,
            List<SectionKey> sections,
            List<EntityChunkKey> entityChunks,
            SimpleRegionStorage entityStorage) {
        this.level = Objects.requireNonNull(level, "level");
        this.background = Objects.requireNonNull(background, "background");
        this.storedChunks = Objects.requireNonNull(storedChunks, "storedChunks");
        this.entityCapture = Objects.requireNonNull(entityCapture, "entityCapture");
        this.target = Objects.requireNonNull(target, "target");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.entityChunks = Objects.requireNonNull(entityChunks, "entityChunks");
        this.entityStorage = entityStorage;
        if (!entityChunks.isEmpty() && entityStorage == null) {
            throw new IllegalArgumentException("Entity storage is required for entity readback");
        }
    }

    boolean advanceUntil(long deadlineNanos) throws IOException {
        if (nextSection == sections.size()
                && nextEntityChunk == entityChunks.size()) {
            return true;
        }
        if (verification == null) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            start();
        }
        if (!DeadlineFuture.await(verification, deadlineNanos)) {
            return false;
        }
        MinecraftPersistenceFuture.join(
                verification, "Restore persisted verification");
        return nextSection == sections.size()
                && nextEntityChunk == entityChunks.size();
    }

    void start() {
        if (verification == null && (nextSection < sections.size()
                || nextEntityChunk < entityChunks.size())) {
            verification = verifyRemaining(nextSection, nextEntityChunk);
        }
    }

    private CompletableFuture<Void> verifyRemaining(
            int sectionStart, int entityStart) {
        PendingVerification pending = beginVerification(sectionStart, entityStart);
        return pending.future().thenComposeAsync(ignored -> {
            nextSection = pending.sectionEnd();
            nextEntityChunk = pending.entityEnd();
            if (closed || (nextSection == sections.size()
                    && nextEntityChunk == entityChunks.size())) {
                return CompletableFuture.completedFuture(null);
            }
            return verifyRemaining(nextSection, nextEntityChunk);
        }, background);
    }

    private PendingVerification beginVerification(
            int sectionStart, int entityStart) {
        int sectionEnd = StreamingPreparedWorldMutationSession.windowEnd(
                sections, sectionStart, sections.size());
        Map<ChunkCoordinate, Map<SectionKey, SectionBlob>> chunkTargets =
                sectionTargets(sectionStart, sectionEnd);
        int remaining = MAX_PENDING_READS - chunkTargets.size();
        int entityEnd = readBatchEnd(
                entityChunks.size(), entityStart, remaining);
        List<CompletableFuture<Void>> pending = new ArrayList<>(MAX_PENDING_READS);
        for (var entry : chunkTargets.entrySet()) {
            ChunkCoordinate chunk = entry.getKey();
            Map<SectionKey, SectionBlob> expected = entry.getValue();
            ChunkPos position = new ChunkPos(chunk.x(), chunk.z());
            pending.add(level.getChunkSource().chunkMap.read(position)
                    .thenApplyAsync(stored -> {
                        try {
                            String mismatch = stored.isEmpty()
                                    ? "chunk is absent"
                                    : storedChunks.mismatchRaw(
                                            position, stored.orElseThrow(),
                                            expected);
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
        for (int index = entityStart; index < entityEnd; index++) {
            EntityChunkKey key = entityChunks.get(index);
            EntityChunkBlob expected = target.entities().get(key);
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
        phase = entityStart == entityEnd
                ? "persisted chunk verification"
                : sectionStart == sectionEnd
                        ? "persisted entity verification"
                        : "persisted chunk/entity verification";
        return new PendingVerification(
                sectionEnd, entityEnd,
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
    }

    private Map<ChunkCoordinate, Map<SectionKey, SectionBlob>> sectionTargets(
            int start, int end) {
        Map<ChunkCoordinate, Map<SectionKey, SectionBlob>> grouped =
                new java.util.LinkedHashMap<>();
        for (int index = start; index < end; index++) {
            SectionKey key = sections.get(index);
            grouped.computeIfAbsent(
                    ChunkCoordinate.from(key), ignored -> new java.util.LinkedHashMap<>())
                    .put(key, target.sections().get(key));
        }
        grouped.replaceAll((ignored, value) -> Map.copyOf(value));
        return Map.copyOf(grouped);
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

    void close() {
        closed = true;
        if (verification != null) {
            verification.cancel(false);
        }
    }

    private record PendingVerification(
            int sectionEnd,
            int entityEnd,
            CompletableFuture<Void> future) { }
}
