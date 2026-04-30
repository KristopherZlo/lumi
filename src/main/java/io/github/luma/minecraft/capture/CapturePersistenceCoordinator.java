package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
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

/**
 * Coordinates low-priority persistence work for live capture sessions.
 *
 * <p>Baseline chunk writes and recovery draft flushes use separate maintenance
 * executors so draft durability is not delayed behind large baseline batches.
 */
public final class CapturePersistenceCoordinator implements AutoCloseable {

    private final RecoveryRepository recoveryRepository;
    private final BaselineChunkRepository baselineChunkRepository;
    private final ExecutorService draftFlushExecutor;
    private final ExecutorService baselineExecutor;
    private final Map<String, CompletableFuture<Void>> pendingBaselineWrites = new HashMap<>();
    private final Map<String, PendingDraftFlush> pendingDraftFlushes = new HashMap<>();

    public CapturePersistenceCoordinator() {
        this(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                Executors.newSingleThreadExecutor(new MaintenanceThreadFactory("draft")),
                Executors.newSingleThreadExecutor(new MaintenanceThreadFactory("baseline"))
        );
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ExecutorService maintenanceExecutor
    ) {
        this(recoveryRepository, baselineChunkRepository, maintenanceExecutor, maintenanceExecutor);
    }

    CapturePersistenceCoordinator(
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository,
            ExecutorService draftFlushExecutor,
            ExecutorService baselineExecutor
    ) {
        this.recoveryRepository = Objects.requireNonNull(recoveryRepository, "recoveryRepository");
        this.baselineChunkRepository = Objects.requireNonNull(baselineChunkRepository, "baselineChunkRepository");
        this.draftFlushExecutor = Objects.requireNonNull(draftFlushExecutor, "draftFlushExecutor");
        this.baselineExecutor = Objects.requireNonNull(baselineExecutor, "baselineExecutor");
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
            CompletableFuture<Void> future = new CompletableFuture<>();
            this.pendingBaselineWrites.put(key, future);
            LumaMod.LOGGER.info(
                    "Queued async baseline write for project {} chunk {}:{}",
                    projectName,
                    chunkSnapshot.chunkX(),
                    chunkSnapshot.chunkZ()
            );
            this.baselineExecutor.execute(() -> this.writeBaseline(layout, projectId, projectName, chunkSnapshot, now, key, future));
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

    public boolean hasPendingBaselineWrite(String projectId, ChunkPoint chunk) {
        if (projectId == null || projectId.isBlank() || chunk == null) {
            return false;
        }
        synchronized (this) {
            return this.pendingBaselineWrites.containsKey(baselineKey(projectId, chunk));
        }
    }

    public void drainProject(String projectId, String projectName) throws IOException {
        while (true) {
            List<CompletableFuture<Void>> futures = this.projectFutures(projectId);
            if (futures.isEmpty()) {
                return;
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

    public void deleteDraft(ProjectLayout layout, String projectId, String projectName) throws IOException {
        this.drainProject(projectId, projectName);
        this.recoveryRepository.deleteDraft(layout);
    }

    @Override
    public void close() {
        this.draftFlushExecutor.shutdown();
        if (this.baselineExecutor != this.draftFlushExecutor) {
            this.baselineExecutor.shutdown();
        }
    }

    private void writeBaseline(
            ProjectLayout layout,
            String projectId,
            String projectName,
            ChunkSnapshotPayload chunkSnapshot,
            java.time.Instant now,
            String key,
            CompletableFuture<Void> future
    ) {
        try {
            boolean written = this.baselineChunkRepository.writeIfMissing(layout, projectId, chunkSnapshot, now);
            if (written) {
                LumaMod.LOGGER.info(
                        "Completed async baseline write for project {} chunk {}:{}",
                        projectName,
                        chunkSnapshot.chunkX(),
                        chunkSnapshot.chunkZ()
                );
            }
            future.complete(null);
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        } finally {
            synchronized (this) {
                this.pendingBaselineWrites.remove(key);
            }
        }
    }

    private void scheduleDraftFlush(PendingDraftFlush pending) {
        pending.scheduled = true;
        this.draftFlushExecutor.execute(() -> this.flushDraftLoop(pending));
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
            for (Map.Entry<String, CompletableFuture<Void>> entry : this.pendingBaselineWrites.entrySet()) {
                if (entry.getKey().startsWith(projectId + "::")) {
                    futures.add(entry.getValue());
                }
            }
            PendingDraftFlush pendingDraftFlush = this.pendingDraftFlushes.get(projectId);
            if (pendingDraftFlush != null) {
                futures.add(pendingDraftFlush.future);
            }
            return futures;
        }
    }

    private static String baselineKey(String projectId, ChunkPoint chunk) {
        return projectId + "::" + chunk.x() + ":" + chunk.z();
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

    private static final class MaintenanceThreadFactory implements ThreadFactory {

        private final String queueName;
        private int index;

        private MaintenanceThreadFactory(String queueName) {
            this.queueName = queueName;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lumi-capture-" + this.queueName + "-" + (++this.index));
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
