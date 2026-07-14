package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.service.HistoryProtectionService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates low-priority persistence work for live capture sessions.
 *
 * <p>Baseline chunk writes and recovery draft flushes use separate maintenance
 * executors so draft durability is not delayed behind large baseline batches.
 */
public final class CapturePersistenceCoordinator implements AutoCloseable {

    private static final String BASELINE_THREADS_PROPERTY = "lumi.capture.baselineThreads";
    private static final String BASELINE_LOG_CATEGORY = "capture-baseline";
    private static final int DEFAULT_BASELINE_THREAD_LIMIT = 1;
    private static final int MAX_BASELINE_THREAD_LIMIT = 8;

    private final RecoveryRepository recoveryRepository;
    private final BaselineChunkRepository baselineChunkRepository;
    private final ProjectDirtyScopeRepository dirtyScopeRepository;
    private final HistoryProtectionService historyProtectionService = new HistoryProtectionService();
    private final ExecutorService draftFlushExecutor;
    private final ExecutorService baselineExecutor;
    private final ExecutorService priorityBaselineExecutor;
    private final Map<String, PendingBaselineWrite> pendingBaselineWrites = new HashMap<>();
    private final Map<String, PendingDraftFlush> pendingDraftFlushes = new HashMap<>();
    private final Map<String, PendingDirtyScopeFlush> pendingDirtyScopeFlushes = new HashMap<>();
    private final Map<String, Throwable> persistenceFailures = new HashMap<>();

    public CapturePersistenceCoordinator() {
        this(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                new ProjectDirtyScopeRepository(),
                Executors.newSingleThreadExecutor(maintenanceThreadFactory("draft")),
                Executors.newFixedThreadPool(
                        defaultBaselineWriterThreads(),
                        maintenanceThreadFactory("baseline")
                ),
                Executors.newSingleThreadExecutor(maintenanceThreadFactory("baseline-priority"))
        );
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ExecutorService maintenanceExecutor
    ) {
        this(
                recoveryRepository,
                baselineChunkRepository,
                new ProjectDirtyScopeRepository(),
                maintenanceExecutor,
                maintenanceExecutor,
                maintenanceExecutor
        );
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ExecutorService draftFlushExecutor,
            ExecutorService baselineExecutor
    ) {
        this(
                recoveryRepository,
                baselineChunkRepository,
                new ProjectDirtyScopeRepository(),
                draftFlushExecutor,
                baselineExecutor,
                baselineExecutor
        );
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ProjectDirtyScopeRepository dirtyScopeRepository,
            ExecutorService draftFlushExecutor,
            ExecutorService baselineExecutor
    ) {
        this(
                recoveryRepository,
                baselineChunkRepository,
                dirtyScopeRepository,
                draftFlushExecutor,
                baselineExecutor,
                baselineExecutor
        );
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ProjectDirtyScopeRepository dirtyScopeRepository,
            ExecutorService draftFlushExecutor,
            ExecutorService baselineExecutor,
            ExecutorService priorityBaselineExecutor
    ) {
        this.recoveryRepository = Objects.requireNonNull(recoveryRepository, "recoveryRepository");
        this.baselineChunkRepository = Objects.requireNonNull(baselineChunkRepository, "baselineChunkRepository");
        this.dirtyScopeRepository = Objects.requireNonNull(dirtyScopeRepository, "dirtyScopeRepository");
        this.draftFlushExecutor = Objects.requireNonNull(draftFlushExecutor, "draftFlushExecutor");
        this.baselineExecutor = Objects.requireNonNull(baselineExecutor, "baselineExecutor");
        this.priorityBaselineExecutor = Objects.requireNonNull(priorityBaselineExecutor, "priorityBaselineExecutor");
    }

    public boolean enqueueBaselineWrite(
            ProjectLayout layout,
            String projectId,
            String projectName,
            ChunkSnapshotPayload chunkSnapshot,
            java.time.Instant now
    ) {
        String key = baselineKey(projectId, chunkSnapshot.chunk());
        synchronized (this) {
            if (this.pendingBaselineWrites.containsKey(key)) {
                return false;
            }
            PendingBaselineWrite pending = new PendingBaselineWrite(
                    layout,
                    projectId,
                    projectName,
                    chunkSnapshot,
                    now,
                    key
            );
            this.pendingBaselineWrites.put(key, pending);
            LumaDebugLog.log(
                    BASELINE_LOG_CATEGORY,
                    "Queued async baseline write for project {} chunk {}:{}",
                    projectName,
                    chunkSnapshot.chunkX(),
                    chunkSnapshot.chunkZ()
            );
            this.baselineExecutor.execute(() -> this.writeBaseline(pending));
            return true;
        }
    }

    public void enqueueDraftFlush(
            ProjectLayout layout,
            String projectId,
            String projectName,
            RecoveryDraft draft
    ) {
        synchronized (this) {
            PendingDraftFlush pending = this.pendingDraftFlushes.get(projectId);
            if (pending == null) {
                pending = new PendingDraftFlush(projectId, projectName, layout, draft);
                this.pendingDraftFlushes.put(projectId, pending);
                this.scheduleDraftFlush(pending);
            } else {
                pending.update(draft);
            }
            LumaMod.LOGGER.info(
                    "Queued async draft flush for project {} with {} pending changes",
                    projectName,
                    draft.changes().size()
            );
        }
    }

    public void enqueueDirtyScopeFlush(
            ProjectLayout layout,
            String projectId,
            String projectName,
            ProjectDirtyScope scope
    ) {
        synchronized (this) {
            PendingDirtyScopeFlush pending = this.pendingDirtyScopeFlushes.get(projectId);
            if (pending == null) {
                pending = new PendingDirtyScopeFlush(projectId, projectName, layout, scope.copy());
                this.pendingDirtyScopeFlushes.put(projectId, pending);
                this.scheduleDirtyScopeFlush(pending);
            } else {
                pending.update(scope.copy());
            }
        }
    }

    public boolean hasPendingBaselineWrite(String projectId, ChunkPoint chunk) {
        if (projectId == null || projectId.isBlank() || chunk == null) {
            return false;
        }
        synchronized (this) {
            return this.pendingBaselineWrites.containsKey(baselineKey(projectId, chunk));
        }
    }

    public boolean hasPendingDraftFlush(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        synchronized (this) {
            return this.pendingDraftFlushes.containsKey(projectId);
        }
    }

    public boolean hasPendingDirtyScopeFlush(String projectId) {
        synchronized (this) {
            return projectId != null && this.pendingDirtyScopeFlushes.containsKey(projectId);
        }
    }

    public void drainProject(String projectId, String projectName) throws IOException {
        long startedAt = System.nanoTime();
        boolean waited = false;
        int peakPendingTasks = 0;
        this.promoteProjectBaselines(projectId);
        while (true) {
            this.throwRecordedFailure(projectId, projectName);
            List<CompletableFuture<Void>> futures = this.projectFutures(projectId);
            if (futures.isEmpty()) {
                if (waited) {
                    LumaMod.LOGGER.info(
                            "Drained capture maintenance for project {} in {} ms (peak pending tasks: {})",
                            projectName,
                            elapsedMillis(startedAt),
                            peakPendingTasks
                    );
                }
                return;
            }
            peakPendingTasks = Math.max(peakPendingTasks, futures.size());
            if (!waited) {
                waited = true;
                LumaMod.LOGGER.info(
                        "Waiting for {} pending capture maintenance tasks for project {}",
                        futures.size(),
                        projectName
                );
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                LumaMod.LOGGER.warn("Failed to drain capture maintenance for project {}", projectName, cause);
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to drain capture maintenance for " + projectName, cause);
            }
        }
    }

    public void drainDraftFlushes(String projectId, String projectName) throws IOException {
        while (true) {
            this.throwRecordedFailure(projectId, projectName);
            CompletableFuture<Void> future;
            synchronized (this) {
                PendingDraftFlush pendingDraftFlush = this.pendingDraftFlushes.get(projectId);
                if (pendingDraftFlush == null) {
                    return;
                }
                future = pendingDraftFlush.future;
            }
            try {
                future.join();
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                LumaMod.LOGGER.warn("Failed to drain capture draft flush for project {}", projectName, cause);
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to drain capture draft flush for " + projectName, cause);
            }
        }
    }

    public void drainDirtyScopeFlushes(String projectId, String projectName) throws IOException {
        while (true) {
            this.throwRecordedFailure(projectId, projectName);
            CompletableFuture<Void> future;
            synchronized (this) {
                PendingDirtyScopeFlush pending = this.pendingDirtyScopeFlushes.get(projectId);
                if (pending == null) {
                    return;
                }
                future = pending.future;
            }
            try {
                future.join();
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to drain capture dirty scope for " + projectName, cause);
            }
        }
    }

    public void deleteDraft(ProjectLayout layout, String projectId, String projectName) throws IOException {
        this.drainDraftFlushes(projectId, projectName);
        this.recoveryRepository.deleteDraft(layout);
    }

    public void deleteDirtyScope(ProjectLayout layout, String projectId, String projectName) throws IOException {
        this.drainDirtyScopeFlushes(projectId, projectName);
        this.dirtyScopeRepository.delete(layout);
    }

    @Override
    public void close() {
        this.draftFlushExecutor.shutdown();
        if (this.baselineExecutor != this.draftFlushExecutor) {
            this.baselineExecutor.shutdown();
        }
        if (this.priorityBaselineExecutor != this.baselineExecutor
                && this.priorityBaselineExecutor != this.draftFlushExecutor) {
            this.priorityBaselineExecutor.shutdown();
        }
    }

    private void writeBaseline(PendingBaselineWrite pending) {
        if (!pending.claim()) {
            return;
        }
        try {
            boolean written = this.baselineChunkRepository.writeIfMissing(
                    pending.layout,
                    pending.projectId,
                    pending.chunkSnapshot,
                    pending.now
            );
            if (written) {
                LumaDebugLog.log(
                        BASELINE_LOG_CATEGORY,
                        "Completed async baseline write for project {} chunk {}:{}",
                        pending.projectName,
                        pending.chunkSnapshot.chunkX(),
                        pending.chunkSnapshot.chunkZ()
                );
            }
            pending.future.complete(null);
        } catch (Throwable throwable) {
            this.markDegraded(pending.layout, "Baseline persistence failed: " + failureDetail(throwable));
            this.recordFailure(pending.projectId, throwable);
            pending.future.completeExceptionally(throwable);
        } finally {
            synchronized (this) {
                this.pendingBaselineWrites.remove(pending.key, pending);
            }
        }
    }

    private void promoteProjectBaselines(String projectId) {
        List<PendingBaselineWrite> projectWrites;
        synchronized (this) {
            projectWrites = this.pendingBaselineWrites.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(projectId + "::"))
                    .map(Map.Entry::getValue)
                    .toList();
        }
        for (PendingBaselineWrite pending : projectWrites) {
            this.priorityBaselineExecutor.execute(() -> this.writeBaseline(pending));
        }
    }

    private void scheduleDraftFlush(PendingDraftFlush pending) {
        pending.scheduled = true;
        this.draftFlushExecutor.execute(() -> this.flushDraftLoop(pending));
    }

    private void scheduleDirtyScopeFlush(PendingDirtyScopeFlush pending) {
        this.draftFlushExecutor.execute(() -> this.flushDirtyScopeLoop(pending));
    }

    private void flushDirtyScopeLoop(PendingDirtyScopeFlush pending) {
        while (true) {
            ProjectDirtyScope scope;
            synchronized (this) {
                scope = pending.latestScope;
                pending.dirty = false;
            }
            try {
                this.drainBaselineWrites(pending.projectId, pending.projectName);
                ProjectDirtyScope stored = this.dirtyScopeRepository.load(pending.layout).orElse(null);
                if (stored != null) {
                    if (!sameDirtyScopeBase(stored, scope)) {
                        throw new IOException("Dirty scope base does not match active project head");
                    }
                    stored.markBlockSections(scope.blockSections());
                    for (ChunkPoint chunk : scope.entityChunks()) {
                        stored.markEntityChunk(chunk);
                    }
                    scope = stored;
                }
                this.dirtyScopeRepository.save(pending.layout, scope);
            } catch (Throwable throwable) {
                this.markDegraded(
                        pending.layout,
                        "Dirty scope persistence failed: " + failureDetail(throwable)
                );
                this.recordFailure(pending.projectId, throwable);
                synchronized (this) {
                    this.pendingDirtyScopeFlushes.remove(pending.projectId, pending);
                }
                LumaMod.LOGGER.warn("Failed to persist dirty scope for project {}", pending.projectName, throwable);
                pending.future.completeExceptionally(throwable);
                return;
            }
            synchronized (this) {
                if (!pending.dirty) {
                    this.pendingDirtyScopeFlushes.remove(pending.projectId, pending);
                    pending.future.complete(null);
                    return;
                }
            }
        }
    }

    private void drainBaselineWrites(String projectId, String projectName) throws IOException {
        this.promoteProjectBaselines(projectId);
        while (true) {
            this.throwRecordedFailure(projectId, projectName);
            List<CompletableFuture<Void>> futures;
            synchronized (this) {
                futures = this.pendingBaselineWrites.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(projectId + "::"))
                        .map(entry -> entry.getValue().future)
                        .toList();
            }
            if (futures.isEmpty()) {
                return;
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to persist baseline chunks for " + projectName, cause);
            }
        }
    }

    private void flushDraftLoop(PendingDraftFlush pending) {
        while (true) {
            RecoveryDraft draft;
            synchronized (this) {
                draft = pending.latestDraft;
                pending.dirty = false;
            }

            try {
                this.recoveryRepository.saveDraft(pending.layout, draft);
                LumaMod.LOGGER.info(
                        "Completed async draft flush for project {} with {} pending changes",
                        pending.projectName,
                        draft.changes().size()
                );
            } catch (Throwable throwable) {
                this.markDegraded(
                        pending.layout,
                        "Recovery draft persistence failed: " + failureDetail(throwable)
                );
                this.recordFailure(pending.projectId, throwable);
                synchronized (this) {
                    this.pendingDraftFlushes.remove(pending.projectId, pending);
                }
                pending.future.completeExceptionally(throwable);
                return;
            }

            synchronized (this) {
                if (!pending.dirty) {
                    this.pendingDraftFlushes.remove(pending.projectId, pending);
                    pending.scheduled = false;
                    pending.future.complete(null);
                    return;
                }
            }
        }
    }

    private List<CompletableFuture<Void>> projectFutures(String projectId) {
        synchronized (this) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map.Entry<String, PendingBaselineWrite> entry : this.pendingBaselineWrites.entrySet()) {
                if (entry.getKey().startsWith(projectId + "::")) {
                    futures.add(entry.getValue().future);
                }
            }
            PendingDraftFlush pendingDraftFlush = this.pendingDraftFlushes.get(projectId);
            if (pendingDraftFlush != null) {
                futures.add(pendingDraftFlush.future);
            }
            PendingDirtyScopeFlush pendingDirtyScopeFlush = this.pendingDirtyScopeFlushes.get(projectId);
            if (pendingDirtyScopeFlush != null) {
                futures.add(pendingDirtyScopeFlush.future);
            }
            return futures;
        }
    }

    private static String baselineKey(String projectId, ChunkPoint chunk) {
        return projectId + "::" + chunk.x() + ":" + chunk.z();
    }

    private static boolean sameDirtyScopeBase(ProjectDirtyScope left, ProjectDirtyScope right) {
        return left.projectId().equals(right.projectId())
                && left.variantId().equals(right.variantId())
                && left.baseVersionId().equals(right.baseVersionId());
    }

    private void markDegraded(ProjectLayout layout, String detail) {
        try {
            this.historyProtectionService.markDegraded(layout, detail);
        } catch (IOException markerFailure) {
            LumaMod.LOGGER.error("Failed to persist degraded history state for {}", layout.root(), markerFailure);
        }
    }

    private synchronized void recordFailure(String projectId, Throwable failure) {
        this.persistenceFailures.putIfAbsent(projectId, failure);
    }

    private synchronized void throwRecordedFailure(String projectId, String projectName) throws IOException {
        Throwable failure = this.persistenceFailures.get(projectId);
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        throw new IOException("Capture persistence failed for " + projectName, failure);
    }

    private static String failureDetail(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static int defaultBaselineWriterThreads() {
        return baselineWriterThreads(System.getProperty(BASELINE_THREADS_PROPERTY));
    }

    static int baselineWriterThreads(String configuredValue) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            try {
                int configured = Integer.parseInt(configuredValue.trim());
                if (configured > 0) {
                    return Math.min(configured, MAX_BASELINE_THREAD_LIMIT);
                }
            } catch (NumberFormatException ignored) {
                // Invalid values fall back to the bounded default.
            }
        }

        return DEFAULT_BASELINE_THREAD_LIMIT;
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static ThreadFactory maintenanceThreadFactory(String queueName) {
        AtomicInteger nextIndex = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "lumi-capture-" + queueName + "-" + nextIndex.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    private static final class PendingBaselineWrite {

        private final ProjectLayout layout;
        private final String projectId;
        private final String projectName;
        private final ChunkSnapshotPayload chunkSnapshot;
        private final java.time.Instant now;
        private final String key;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();

        private PendingBaselineWrite(
                ProjectLayout layout,
                String projectId,
                String projectName,
                ChunkSnapshotPayload chunkSnapshot,
                java.time.Instant now,
                String key
        ) {
            this.layout = layout;
            this.projectId = projectId;
            this.projectName = projectName;
            this.chunkSnapshot = chunkSnapshot;
            this.now = now;
            this.key = key;
        }

        private boolean claim() {
            return this.claimed.compareAndSet(false, true);
        }
    }

    private static final class PendingDraftFlush {

        private final String projectId;
        private final String projectName;
        private final ProjectLayout layout;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private RecoveryDraft latestDraft;
        private boolean dirty = true;
        private boolean scheduled;

        private PendingDraftFlush(String projectId, String projectName, ProjectLayout layout, RecoveryDraft latestDraft) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.layout = layout;
            this.latestDraft = latestDraft;
        }

        private void update(RecoveryDraft draft) {
            this.latestDraft = draft;
            this.dirty = true;
        }
    }

    private static final class PendingDirtyScopeFlush {

        private final String projectId;
        private final String projectName;
        private final ProjectLayout layout;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private ProjectDirtyScope latestScope;
        private boolean dirty = true;

        private PendingDirtyScopeFlush(
                String projectId,
                String projectName,
                ProjectLayout layout,
                ProjectDirtyScope latestScope
        ) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.layout = layout;
            this.latestScope = latestScope;
        }

        private void update(ProjectDirtyScope scope) {
            this.latestScope = scope;
            this.dirty = true;
        }
    }

}
