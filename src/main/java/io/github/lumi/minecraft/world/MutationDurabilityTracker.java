package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.operation.CapturedGenerationCompletion;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Coalesces dirty persistence and gates vanilla chunk publication until it is durable. */
public final class MutationDurabilityTracker implements CapturedGenerationCompletion {
    private static final Logger LOGGER = Logger.getLogger(MutationDurabilityTracker.class.getName());

    private final WorldObjectRepository objects;
    private final OriginStore origins;
    private final WorkingIndexRepository indexRepository;
    private final Executor background;
    private final WorkingIndex working;
    private final Set<HistoryKey> durableOrigins;
    private final Map<HistoryKey, Long> durableGenerations;
    private final Map<HistoryKey, Long> committedGenerations = new HashMap<>();
    private final Map<HistoryKey, Long> publicationRequirements = new HashMap<>();
    private final Map<ChunkCoordinate, Integer> blockedChunks = new HashMap<>();
    private final Set<HistoryKey> pendingOrigins = new HashSet<>();
    private long indexRevision;
    private boolean indexWriterScheduled;

    private MutationDurabilityTracker(
            WorldObjectRepository objects,
            OriginStore origins,
            WorkingIndexRepository indexRepository,
            Executor background,
            WorkingIndexSnapshot persisted,
            Set<HistoryKey> durableOrigins) {
        this.objects = objects;
        this.origins = origins;
        this.indexRepository = indexRepository;
        this.background = background;
        working = new WorkingIndex(persisted);
        this.durableOrigins = new HashSet<>(durableOrigins);
        durableGenerations = new HashMap<>(persisted.generations());
    }

    /** Must be called off the server thread because it reads repository indexes. */
    public static MutationDurabilityTracker open(
            WorldObjectRepository objects,
            OriginStore origins,
            WorkingIndexRepository indexRepository,
            Executor background) throws IOException {
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(origins, "origins");
        Objects.requireNonNull(indexRepository, "indexRepository");
        Objects.requireNonNull(background, "background");
        return new MutationDurabilityTracker(objects, origins, indexRepository, background,
                indexRepository.read(), origins.entries().keySet());
    }

    public long registerSectionMutation(SectionKey key, Supplier<SectionBlob> preMutationCapture) {
        return register(key, preMutationCapture, objects::write);
    }

    public long registerEntityMutation(
            EntityChunkKey key, Supplier<EntityChunkBlob> preMutationCapture) {
        return register(key, preMutationCapture, objects::write);
    }

    private <T> long register(HistoryKey key, Supplier<T> capture, OriginWriter<T> writer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(capture, "preMutationCapture");
        Runnable originWrite = null;
        boolean scheduleIndex;
        long generation;
        synchronized (this) {
            if (!durableOrigins.contains(key) && pendingOrigins.add(key)) {
                T origin = Objects.requireNonNull(capture.get(), "captured origin");
                originWrite = () -> persistOrigin(key, origin, writer);
            }
            generation = working.markDirty(key);
            if (generation == 1) {
                committedGenerations.remove(key);
            }
            if (publicationRequirements.put(key, generation) == null) {
                blockedChunks.merge(chunk(key), 1, Integer::sum);
            }
            indexRevision++;
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (originWrite != null) {
            background.execute(originWrite);
        }
        if (scheduleIndex) {
            background.execute(this::writeIndexUntilCurrent);
        }
        return generation;
    }

    public synchronized boolean canPublishChunk(int chunkX, int chunkZ) {
        return !blockedChunks.containsKey(new ChunkCoordinate(chunkX, chunkZ));
    }

    public synchronized WorkingIndexSnapshot snapshot() {
        return working.snapshot();
    }

    @Override
    public void clear(WorkingIndexSnapshot captured) {
        boolean scheduleIndex;
        synchronized (this) {
            WorkingIndexSnapshot before = working.snapshot();
            working.clearCaptured(captured);
            WorkingIndexSnapshot after = working.snapshot();
            captured.generations().forEach((key, generation) -> {
                if (Objects.equals(before.generations().get(key), generation)
                        && !after.generations().containsKey(key)) {
                    committedGenerations.put(key, generation);
                    releaseSatisfied(key);
                }
            });
            indexRevision++;
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (scheduleIndex) {
            background.execute(this::writeIndexUntilCurrent);
        }
    }

    private <T> void persistOrigin(HistoryKey key, T origin, OriginWriter<T> writer) {
        try {
            ObjectId id = writer.write(origin);
            origins.register(key, id);
            synchronized (this) {
                durableOrigins.add(key);
                pendingOrigins.remove(key);
                releaseSatisfied(key);
            }
        } catch (IOException failed) {
            LOGGER.log(Level.SEVERE, "Failed to persist Lumi origin for " + key, failed);
        }
    }

    private void writeIndexUntilCurrent() {
        while (true) {
            WorkingIndexSnapshot snapshot;
            long revision;
            synchronized (this) {
                snapshot = working.snapshot();
                revision = indexRevision;
            }
            try {
                indexRepository.write(snapshot);
            } catch (IOException failed) {
                synchronized (this) {
                    indexWriterScheduled = false;
                }
                LOGGER.log(Level.SEVERE, "Failed to persist Lumi working index", failed);
                return;
            }
            synchronized (this) {
                durableGenerations.clear();
                durableGenerations.putAll(snapshot.generations());
                Set.copyOf(publicationRequirements.keySet()).forEach(this::releaseSatisfied);
                if (revision == indexRevision) {
                    indexWriterScheduled = false;
                    return;
                }
            }
        }
    }

    private void releaseSatisfied(HistoryKey key) {
        Long required = publicationRequirements.get(key);
        long durable = Math.max(
                durableGenerations.getOrDefault(key, 0L),
                committedGenerations.getOrDefault(key, 0L));
        if (required == null || !durableOrigins.contains(key)
                || durable < required) {
            return;
        }
        publicationRequirements.remove(key);
        blockedChunks.computeIfPresent(chunk(key), (ignored, count) -> count == 1 ? null : count - 1);
    }

    private static ChunkCoordinate chunk(HistoryKey key) {
        return key instanceof SectionKey section
                ? new ChunkCoordinate(section.chunkX(), section.chunkZ())
                : new ChunkCoordinate(((EntityChunkKey) key).chunkX(), ((EntityChunkKey) key).chunkZ());
    }

    @FunctionalInterface
    private interface OriginWriter<T> {
        ObjectId write(T origin) throws IOException;
    }

    private record ChunkCoordinate(int x, int z) { }
}
