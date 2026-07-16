package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.WorkingIndexPreview;
import io.github.lumi.minecraft.operation.CapturedGenerationCompletion;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Coalesces dirty persistence and gates vanilla chunk publication until it is durable. */
public final class MutationDurabilityTracker implements CapturedGenerationCompletion {
    private static final Logger LOGGER = Logger.getLogger(MutationDurabilityTracker.class.getName());

    private final WorldObjectRepository objects;
    private final OriginStore origins;
    private final WorkingIndexRepository indexRepository;
    private final Executor background;
    private final ChunkDurabilityRetention chunkRetention;
    private final WorkingIndex working;
    private final Set<HistoryKey> durableOrigins;
    private final Map<HistoryKey, Long> durableGenerations;
    private final Map<HistoryKey, Long> committedGenerations = new HashMap<>();
    private final Map<HistoryKey, Long> publicationRequirements = new HashMap<>();
    private final Map<ChunkCoordinate, Integer> blockedChunks = new HashMap<>();
    private final Set<HistoryKey> pendingOrigins = new HashSet<>();
    private final ArrayDeque<PendingOriginWrite> originWrites = new ArrayDeque<>();
    private long indexRevision;
    private long durableIndexRevision;
    private boolean originWriterScheduled;
    private boolean indexWriterScheduled;

    private MutationDurabilityTracker(
            WorldObjectRepository objects,
            OriginStore origins,
            WorkingIndexRepository indexRepository,
            Executor background,
            ChunkDurabilityRetention chunkRetention,
            WorkingIndexSnapshot persisted,
            Set<HistoryKey> durableOrigins) {
        this.objects = objects;
        this.origins = origins;
        this.indexRepository = indexRepository;
        this.background = background;
        this.chunkRetention = chunkRetention;
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
        return open(objects, origins, indexRepository, background,
                ChunkDurabilityRetention.NONE);
    }

    /** Must be called off the server thread because it reads repository indexes. */
    public static MutationDurabilityTracker open(
            WorldObjectRepository objects,
            OriginStore origins,
            WorkingIndexRepository indexRepository,
            Executor background,
            ChunkDurabilityRetention chunkRetention) throws IOException {
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(origins, "origins");
        Objects.requireNonNull(indexRepository, "indexRepository");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(chunkRetention, "chunkRetention");
        return new MutationDurabilityTracker(
                objects, origins, indexRepository, background, chunkRetention,
                indexRepository.read(), origins.entries().keySet());
    }

    public long registerSectionMutation(SectionKey key, Supplier<SectionBlob> preMutationCapture) {
        return register(key, preMutationCapture, objects::write);
    }

    public long registerEntityMutation(
            EntityChunkKey key, Supplier<EntityChunkBlob> preMutationCapture) {
        return register(key, preMutationCapture, objects::write);
    }

    public long markTrackedSection(SectionKey key) {
        requireTracked(key);
        return registerSectionMutation(key, () -> {
            throw new IllegalStateException("Tracked section unexpectedly needs an origin");
        });
    }

    private synchronized void requireTracked(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        if (!durableOrigins.contains(key) && !pendingOrigins.contains(key)) {
            throw new IllegalStateException("Lumi origin is missing for live mutation " + key);
        }
    }

    private <T> long register(HistoryKey key, Supplier<T> capture, OriginWriter<T> writer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(capture, "preMutationCapture");
        boolean scheduleOrigins = false;
        boolean scheduleIndex;
        long generation;
        synchronized (this) {
            if (!durableOrigins.contains(key) && pendingOrigins.add(key)) {
                T origin = Objects.requireNonNull(capture.get(), "captured origin");
                originWrites.add(new PendingOriginWrite(
                        key, () -> persistOrigin(key, origin, writer)));
                scheduleOrigins = !originWriterScheduled;
                originWriterScheduled = true;
            }
            generation = working.markDirty(key);
            if (generation == 1) {
                committedGenerations.remove(key);
            }
            if (publicationRequirements.put(key, generation) == null) {
                ChunkCoordinate coordinate = chunk(key);
                if (!blockedChunks.containsKey(coordinate)) {
                    chunkRetention.retain(coordinate.x(), coordinate.z());
                }
                blockedChunks.merge(coordinate, 1, Integer::sum);
            }
            indexRevision++;
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (scheduleOrigins) {
            scheduleOriginWriter();
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
        return generation;
    }

    public synchronized boolean canPublishChunk(int chunkX, int chunkZ) {
        return !blockedChunks.containsKey(new ChunkCoordinate(chunkX, chunkZ));
    }

    public synchronized boolean canPublish(HistoryKey key) {
        return !publicationRequirements.containsKey(Objects.requireNonNull(key, "key"));
    }

    public synchronized boolean needsOrigin(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        return !durableOrigins.contains(key) && !pendingOrigins.contains(key);
    }

    public synchronized WorkingIndexSnapshot snapshot() {
        return working.snapshot();
    }

    public synchronized WorkingIndexPreview preview(
            Predicate<HistoryKey> scope, int maximumSections) {
        return working.preview(scope, maximumSections);
    }

    public synchronized boolean isDurable(WorkingIndexSnapshot captured) {
        Objects.requireNonNull(captured, "captured");
        return captured.generations().entrySet().stream().allMatch(entry ->
                durableOrigins.contains(entry.getKey())
                        && durableGenerations.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
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
            scheduleIndexWriter();
        }
    }

    /** Requeues failed durability work; the runtime calls this with a one-second backoff. */
    public void retryFailedWrites() {
        boolean scheduleOrigins;
        boolean scheduleIndex;
        synchronized (this) {
            scheduleOrigins = !originWrites.isEmpty() && !originWriterScheduled;
            originWriterScheduled |= scheduleOrigins;
            scheduleIndex = durableIndexRevision < indexRevision && !indexWriterScheduled;
            indexWriterScheduled |= scheduleIndex;
        }
        if (scheduleOrigins) {
            scheduleOriginWriter();
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
    }

    private <T> void persistOrigin(
            HistoryKey key, T origin, OriginWriter<T> writer) throws IOException {
        ObjectId id = writer.write(origin);
        origins.register(key, id);
        synchronized (this) {
            durableOrigins.add(key);
            pendingOrigins.remove(key);
            releaseSatisfied(key);
        }
    }

    private void drainOrigins() {
        while (true) {
            PendingOriginWrite write;
            synchronized (this) {
                write = originWrites.peek();
                if (write == null) {
                    originWriterScheduled = false;
                    return;
                }
            }
            try {
                write.persistence().persist();
            } catch (IOException | RuntimeException failed) {
                synchronized (this) {
                    originWriterScheduled = false;
                }
                LOGGER.log(Level.SEVERE,
                        "Failed to persist Lumi origin for " + write.key(), failed);
                return;
            }
            synchronized (this) {
                originWrites.removeFirst();
            }
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
                durableIndexRevision = revision;
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

    private void scheduleOriginWriter() {
        try {
            background.execute(this::drainOrigins);
        } catch (RuntimeException rejected) {
            synchronized (this) {
                originWriterScheduled = false;
            }
            LOGGER.log(Level.SEVERE, "Failed to schedule Lumi origin persistence", rejected);
        }
    }

    private void scheduleIndexWriter() {
        try {
            background.execute(this::writeIndexUntilCurrent);
        } catch (RuntimeException rejected) {
            synchronized (this) {
                indexWriterScheduled = false;
            }
            LOGGER.log(Level.SEVERE, "Failed to schedule Lumi working index persistence", rejected);
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
        ChunkCoordinate coordinate = chunk(key);
        int count = Objects.requireNonNull(
                blockedChunks.get(coordinate), "blocked chunk count");
        if (count == 1) {
            blockedChunks.remove(coordinate);
            chunkRetention.release(coordinate.x(), coordinate.z());
        } else {
            blockedChunks.put(coordinate, count - 1);
        }
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

    @FunctionalInterface
    private interface OriginPersistence {
        void persist() throws IOException;
    }

    private record PendingOriginWrite(HistoryKey key, OriginPersistence persistence) { }

    private record ChunkCoordinate(int x, int z) { }
}
