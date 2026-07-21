package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.BlockPosition;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final int MAX_PENDING_BLOCKS = 16_384;
    private static final Logger LOGGER = Logger.getLogger(MutationDurabilityTracker.class.getName());

    private final WorldObjectRepository objects;
    private final OriginStore origins;
    private final WorkingIndexRepository indexRepository;
    private final Executor background;
    private final ChunkDurabilityRetention chunkRetention;
    private final WorkingIndex working;
    private final Set<HistoryKey> durableOrigins;
    private final Map<HistoryKey, Long> durableGenerations;
    private final Map<HistoryKey, Long> durableBuilderGenerations;
    private final Map<HistoryKey, Long> committedGenerations = new HashMap<>();
    private final Map<HistoryKey, Long> builderGenerations;
    private final Map<HistoryKey, Long> publicationRequirements = new HashMap<>();
    private final LinkedHashMap<BlockPosition, PendingBlock> pendingBlocks =
            new LinkedHashMap<>();
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
            WorkingIndexRepository.State persisted,
            Set<HistoryKey> durableOrigins) {
        this.objects = objects;
        this.origins = origins;
        this.indexRepository = indexRepository;
        this.background = background;
        this.chunkRetention = chunkRetention;
        working = new WorkingIndex(persisted.working());
        this.durableOrigins = new HashSet<>(durableOrigins);
        durableGenerations = new HashMap<>(persisted.working().generations());
        durableBuilderGenerations = new HashMap<>(persisted.builder().generations());
        builderGenerations = new HashMap<>(persisted.builder().generations());
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
                indexRepository.readState(), origins.entries().keySet());
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
            generation = working.markDirty(
                    key, committedGenerations.getOrDefault(key, 0L));
            committedGenerations.remove(key);
            requirePublicationLocked(key, generation);
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

    public boolean hasPendingChanges() {
        return !working.isEmpty();
    }

    public synchronized boolean hasPendingBuilderChanges() {
        return !builderGenerations.isEmpty();
    }

    public synchronized WorkingIndexSnapshot builderSnapshot() {
        return new WorkingIndexSnapshot(builderGenerations);
    }

    public synchronized WorkingIndexSnapshot builderSnapshot(Predicate<HistoryKey> includes) {
        Objects.requireNonNull(includes, "includes");
        Map<HistoryKey, Long> selected = new LinkedHashMap<>();
        builderGenerations.forEach((key, generation) -> {
            if (includes.test(key)) {
                selected.put(key, generation);
            }
        });
        return new WorkingIndexSnapshot(selected);
    }

    /** Captures one atomic working/builder/index revision for preparation durability. */
    public synchronized DurabilityBoundary durabilityBoundary() {
        return new DurabilityBoundary(
                working.snapshot(), new WorkingIndexSnapshot(builderGenerations),
                new IndexRevision(indexRevision));
    }

    /** Attaches the current index revision and matching builder markers to a focused boundary. */
    public synchronized DurabilityBoundary durabilityBoundary(
            WorkingIndexSnapshot captured) {
        Objects.requireNonNull(captured, "captured");
        Map<HistoryKey, Long> builders = new LinkedHashMap<>();
        builderGenerations.forEach((key, generation) -> {
            Long capturedGeneration = captured.generations().get(key);
            if (capturedGeneration != null && generation <= capturedGeneration) {
                builders.put(key, generation);
            }
        });
        return new DurabilityBoundary(
                captured, new WorkingIndexSnapshot(builders),
                new IndexRevision(indexRevision));
    }

    public synchronized WorkingIndexPreview preview(
            Predicate<HistoryKey> scope, int maximumBlocks) {
        if (maximumBlocks < 0) {
            throw new IllegalArgumentException("maximumBlocks must be non-negative");
        }
        Map<HistoryKey, Long> visible = new LinkedHashMap<>();
        builderGenerations.forEach((key, generation) -> {
            if (scope.test(key)) {
                visible.put(key, generation);
            }
        });
        WorkingIndexSnapshot builders = new WorkingIndexSnapshot(visible);
        var blocks = new java.util.ArrayList<BlockPosition>(
                Math.min(maximumBlocks, pendingBlocks.size()));
        var entries = List.copyOf(pendingBlocks.values());
        for (int index = entries.size() - 1;
                index >= 0 && blocks.size() < maximumBlocks; index--) {
            PendingBlock pending = entries.get(index);
            Long generation = visible.get(pending.section());
            if (generation != null && generation >= pending.generation()
                    && pending.builder()) {
                blocks.add(pending.position());
            }
        }
        return new WorkingIndexPreview(
                visible.size(), List.of(), blocks, builders.sectionBounds());
    }

    public synchronized void recordBlockMutation(
            BlockPosition position, long generation) {
        recordBlockMutationLocked(position, generation, false);
    }

    public void recordBuilderBlockMutation(
            BlockPosition position, long generation) {
        boolean scheduleIndex;
        synchronized (this) {
            recordBlockMutationLocked(position, generation, true);
            scheduleIndex = markBuilderMutationLocked(section(position), generation);
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
    }

    public boolean markBuilderMutation(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        boolean scheduleIndex;
        synchronized (this) {
            Long generation = working.generation(key);
            if (generation == null) {
                return false;
            }
            scheduleIndex = markBuilderMutationLocked(key, generation);
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
        return true;
    }

    private boolean markBuilderMutationLocked(HistoryKey key, long generation) {
        Long previous = builderGenerations.get(key);
        if (previous != null && previous >= generation) {
            return false;
        }
        builderGenerations.put(key, generation);
        requirePublicationLocked(key, generation);
        indexRevision++;
        boolean scheduleIndex = !indexWriterScheduled;
        indexWriterScheduled = true;
        return scheduleIndex;
    }

    private void recordBlockMutationLocked(
            BlockPosition position, long generation, boolean builder) {
        Objects.requireNonNull(position, "position");
        SectionKey section = section(position);
        Long current = working.generation(section);
        if (generation < 1 || current == null || generation > current) {
            throw new IllegalArgumentException(
                    "Pending block generation is outside its dirty section");
        }
        pendingBlocks.remove(position);
        pendingBlocks.put(position, new PendingBlock(
                position, section, generation, builder));
        while (pendingBlocks.size() > MAX_PENDING_BLOCKS) {
            pendingBlocks.remove(pendingBlocks.keySet().iterator().next());
        }
    }

    public synchronized boolean isDurable(WorkingIndexSnapshot captured) {
        Objects.requireNonNull(captured, "captured");
        return durableKeyCount(captured) == captured.generations().size();
    }

    public synchronized boolean isDurable(DurabilityBoundary captured) {
        Objects.requireNonNull(captured, "captured");
        return durableIndexRevision >= captured.revision().value()
                && durableKeyCount(captured) == captured.working().generations().size();
    }

    public synchronized int durableKeyCount(WorkingIndexSnapshot captured) {
        Objects.requireNonNull(captured, "captured");
        return (int) captured.generations().entrySet().stream().filter(entry ->
                durableOrigins.contains(entry.getKey())
                        && durableGenerations.getOrDefault(entry.getKey(), 0L)
                        >= entry.getValue()
                        && isBuilderMarkerDurable(entry.getKey(), entry.getValue())).count();
    }

    public synchronized int durableKeyCount(DurabilityBoundary captured) {
        Objects.requireNonNull(captured, "captured");
        return (int) captured.working().generations().entrySet().stream()
                .filter(entry -> isBoundaryEntryDurable(
                        entry.getKey(), entry.getValue(), captured.builder()))
                .count();
    }

    @Override
    public void clear(WorkingIndexSnapshot captured) {
        clearAndRevision(captured);
    }

    @Override
    public void complete(WorkingIndexSnapshot captured) throws IOException {
        awaitDurable(clearAndRevision(captured));
    }

    public IndexRevision clearAndRevision(WorkingIndexSnapshot captured) {
        boolean scheduleIndex;
        IndexRevision revision;
        synchronized (this) {
            WorkingIndexSnapshot before = working.snapshot();
            working.clearCaptured(captured);
            WorkingIndexSnapshot after = working.snapshot();
            builderGenerations.entrySet().removeIf(entry -> {
                Long generation = captured.generations().get(entry.getKey());
                return generation != null && generation >= entry.getValue();
            });
            captured.generations().forEach((key, generation) -> {
                if (Objects.equals(before.generations().get(key), generation)
                        && !after.generations().containsKey(key)) {
                    committedGenerations.put(key, generation);
                    releaseSatisfied(key);
                }
            });
            pendingBlocks.entrySet().removeIf(entry -> {
                Long generation = captured.generations().get(entry.getValue().section());
                return generation != null && entry.getValue().generation() <= generation;
            });
            indexRevision++;
            revision = new IndexRevision(indexRevision);
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
        return revision;
    }

    public IndexRevision restoreAndRevision(WorkingIndexSnapshot captured) {
        Objects.requireNonNull(captured, "captured");
        boolean scheduleIndex;
        IndexRevision revision;
        synchronized (this) {
            working.restoreCaptured(captured);
            captured.generations().forEach((key, generation) -> {
                builderGenerations.merge(key, generation, Math::max);
                requirePublicationLocked(
                        key, Objects.requireNonNull(working.generation(key)));
            });
            indexRevision++;
            revision = new IndexRevision(indexRevision);
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
        return revision;
    }

    /** Reconstructs builder tracking for a legacy journal that predates generation sidecars. */
    public IndexRevision trackRestoredBuilderAndRevision(
            Collection<? extends HistoryKey> restoredKeys) {
        Objects.requireNonNull(restoredKeys, "restoredKeys");
        boolean scheduleIndex;
        IndexRevision revision;
        synchronized (this) {
            for (HistoryKey key : restoredKeys) {
                requireTracked(key);
                long generation = working.markDirty(
                        key, committedGenerations.getOrDefault(key, 0L));
                committedGenerations.remove(key);
                builderGenerations.put(key, generation);
                requirePublicationLocked(key, generation);
            }
            indexRevision++;
            revision = new IndexRevision(indexRevision);
            scheduleIndex = !indexWriterScheduled;
            indexWriterScheduled = true;
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
        }
        return revision;
    }

    public synchronized boolean isDurable(IndexRevision revision) {
        return durableIndexRevision >= Objects.requireNonNull(revision, "revision").value();
    }

    private synchronized void awaitDurable(IndexRevision revision) throws IOException {
        while (!isDurable(revision)) {
            try {
                wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "Interrupted while finalizing the Lumi working index", interrupted);
            }
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
        boolean scheduleIndex;
        synchronized (this) {
            durableOrigins.add(key);
            pendingOrigins.remove(key);
            releaseSatisfied(key);
            scheduleIndex = durableIndexRevision < indexRevision && !indexWriterScheduled;
            indexWriterScheduled |= scheduleIndex;
        }
        if (scheduleIndex) {
            scheduleIndexWriter();
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
            WorkingIndexRepository.State state;
            long revision;
            synchronized (this) {
                state = new WorkingIndexRepository.State(
                        working.snapshot(), new WorkingIndexSnapshot(builderGenerations));
                revision = indexRevision;
                if (!durableOrigins.containsAll(state.working().generations().keySet())) {
                    indexWriterScheduled = false;
                    return;
                }
            }
            try {
                indexRepository.write(state);
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
                durableGenerations.putAll(state.working().generations());
                durableBuilderGenerations.clear();
                durableBuilderGenerations.putAll(state.builder().generations());
                Set.copyOf(publicationRequirements.keySet()).forEach(this::releaseSatisfied);
                notifyAll();
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
        Long requiredBuilder = builderGenerations.get(key);
        long durable = Math.max(
                durableGenerations.getOrDefault(key, 0L),
                committedGenerations.getOrDefault(key, 0L));
        if (required == null || !durableOrigins.contains(key)
                || durable < required
                || requiredBuilder != null
                && durableBuilderGenerations.getOrDefault(key, 0L) < requiredBuilder) {
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

    private void requirePublicationLocked(HistoryKey key, long generation) {
        Long previous = publicationRequirements.put(key, generation);
        if (previous != null) {
            if (previous > generation) {
                publicationRequirements.put(key, previous);
            }
            return;
        }
        ChunkCoordinate coordinate = chunk(key);
        if (!blockedChunks.containsKey(coordinate)) {
            chunkRetention.retain(coordinate.x(), coordinate.z());
        }
        blockedChunks.merge(coordinate, 1, Integer::sum);
    }

    private boolean isBuilderMarkerDurable(HistoryKey key, long capturedGeneration) {
        Long builderGeneration = builderGenerations.get(key);
        return builderGeneration == null || builderGeneration > capturedGeneration
                || durableBuilderGenerations.getOrDefault(key, 0L) >= builderGeneration;
    }

    private boolean isBoundaryEntryDurable(
            HistoryKey key,
            long generation,
            WorkingIndexSnapshot builders) {
        if (!durableOrigins.contains(key)
                || durableGenerations.getOrDefault(key, 0L) < generation) {
            return false;
        }
        Long builderGeneration = builders.generations().get(key);
        return builderGeneration == null
                || durableBuilderGenerations.getOrDefault(key, 0L) >= builderGeneration;
    }

    private static ChunkCoordinate chunk(HistoryKey key) {
        return key instanceof SectionKey section
                ? new ChunkCoordinate(section.chunkX(), section.chunkZ())
                : new ChunkCoordinate(((EntityChunkKey) key).chunkX(), ((EntityChunkKey) key).chunkZ());
    }

    private static SectionKey section(BlockPosition position) {
        return new SectionKey(
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.y(), 16),
                Math.floorDiv(position.z(), 16));
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

    private record PendingBlock(
            BlockPosition position, SectionKey section,
            long generation, boolean builder) { }

    private record ChunkCoordinate(int x, int z) { }

    public record IndexRevision(long value) {
        public IndexRevision {
            if (value < 0) {
                throw new IllegalArgumentException("Index revision cannot be negative");
            }
        }
    }

    public record DurabilityBoundary(
            WorkingIndexSnapshot working,
            WorkingIndexSnapshot builder,
            IndexRevision revision) {
        public DurabilityBoundary {
            Objects.requireNonNull(working, "working");
            Objects.requireNonNull(builder, "builder");
            Objects.requireNonNull(revision, "revision");
            builder.generations().forEach((key, generation) -> {
                Long workingGeneration = working.generations().get(key);
                if (workingGeneration == null || generation > workingGeneration) {
                    throw new IllegalArgumentException(
                            "Builder boundary must be within the working boundary");
                }
            });
        }
    }
}
