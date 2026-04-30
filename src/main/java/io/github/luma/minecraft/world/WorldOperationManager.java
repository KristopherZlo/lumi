package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Coordinates long-running world operations with a two-stage execution model.
 *
 * <p>Heavy preparation work runs on a low-priority background executor while
 * actual block placement is applied in bounded batches on the server thread.
 * Only one operation may run per world at a time.
 */
public final class WorldOperationManager {

    private static final int MAX_BLOCK_ENTITIES_PER_TICK = 64;
    private static final int MAX_ENTITY_OPERATIONS_PER_TICK = 32;
    private static final double MIN_ADAPTIVE_SCALE = 0.25D;
    private static final double MAX_ADAPTIVE_SCALE = 1.25D;
    private static final WorldOperationManager INSTANCE = new WorldOperationManager();

    private final WorldApplyOperationProfile applyOperationProfile = new WorldApplyOperationProfile();
    private final WorldApplyBudgetPlanner budgetPlanner = new WorldApplyBudgetPlanner();
    private ExecutorService backgroundExecutor = createExecutor();
    private final Map<String, ActiveOperation> activeOperations = new HashMap<>();
    private final Map<String, OperationSnapshot> lastSnapshots = new HashMap<>();

    private WorldOperationManager() {
    }

    public static WorldOperationManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean hasActiveOperation(MinecraftServer server) {
        return this.activeOperations.containsKey(this.serverKey(server));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server) {
        String serverKey = this.serverKey(server);
        ActiveOperation active = this.activeOperations.get(serverKey);
        if (active != null) {
            return Optional.of(active.snapshot());
        }
        return Optional.ofNullable(this.lastSnapshots.get(serverKey));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server, String projectId) {
        return this.snapshot(server)
                .filter(snapshot -> snapshot.handle() != null)
                .filter(snapshot -> projectId == null || projectId.equals(snapshot.handle().projectId()));
    }

    /**
     * Starts an operation whose work completes entirely off-thread.
     */
    public OperationHandle startBackgroundOperation(
            ServerLevel level,
            String projectId,
            String label,
            String unitLabel,
            boolean debugEnabled,
            BackgroundWork work
    ) {
        String serverKey = this.serverKey(level.getServer());
        synchronized (this) {
            this.ensureIdle(serverKey);
            BackgroundActiveOperation operation = new BackgroundActiveOperation(
                    level,
                    new OperationHandle(UUID.randomUUID().toString(), projectId, label, Instant.now(), debugEnabled),
                    unitLabel,
                    work
            );
            this.activeOperations.put(serverKey, operation);
            LumaMod.LOGGER.info(
                    "Queued background operation {} for project {}",
                    operation.handle().label(),
                    projectId
            );
            LumaDebugLog.log(
                    operation.handle(),
                    "world-op",
                    "Queued background operation {} for project {}",
                    operation.handle().label(),
                    projectId
            );
            return operation.handle();
        }
    }

    public OperationHandle startPreparedApplyOperation(
            ServerLevel level,
            String projectId,
            String label,
            String unitLabel,
            boolean debugEnabled,
            PreparedApplyWork work
    ) {
        String serverKey = this.serverKey(level.getServer());
        synchronized (this) {
            this.ensureIdle(serverKey);
            PreparedApplyActiveOperation operation = new PreparedApplyActiveOperation(
                    level,
                    new OperationHandle(UUID.randomUUID().toString(), projectId, label, Instant.now(), debugEnabled),
                    unitLabel,
                    work
            );
            this.activeOperations.put(serverKey, operation);
            LumaMod.LOGGER.info(
                    "Queued prepared apply operation {} for project {}",
                    operation.handle().label(),
                    projectId
            );
            LumaDebugLog.log(
                    operation.handle(),
                    "world-op",
                    "Queued prepared apply operation {} for project {}",
                    operation.handle().label(),
                    projectId
            );
            return operation.handle();
        }
    }

    /**
     * Advances the active world operation for the given server.
     *
     * <p>Background-only operations complete once their future is done. Prepared
     * apply operations consume a bounded number of block placements or time
     * budget for the current tick.
     */
    public void tick(MinecraftServer server) {
        ActiveOperation operation;
        synchronized (this) {
            operation = this.activeOperations.get(this.serverKey(server));
        }
        if (operation == null) {
            return;
        }

        try {
            WorldApplyBudget budget = this.currentTickBudget(operation);
            long startedAt = System.nanoTime();
            if (operation.advance(budget, startedAt + budget.maxNanos())) {
                this.complete(server, operation);
            }
            operation.recordAdvanceCost(System.nanoTime() - startedAt, budget.maxNanos());
        } catch (Exception exception) {
            operation.fail(exception);
            this.complete(server, operation);
            LumaMod.LOGGER.warn("World operation {} failed", operation.handle().label(), exception);
        }
    }

    public void shutdown() {
        LumaMod.LOGGER.info("Shutting down world operation executor");
        this.backgroundExecutor.shutdownNow();
    }

    private synchronized void complete(MinecraftServer server, ActiveOperation operation) {
        String serverKey = this.serverKey(server);
        ActiveOperation active = this.activeOperations.get(serverKey);
        if (active == operation) {
            this.activeOperations.remove(serverKey);
            this.lastSnapshots.put(serverKey, operation.snapshot());
        }
    }

    private WorldApplyBudget currentTickBudget(ActiveOperation operation) {
        double fraction = operation.snapshot().progress().fraction();
        boolean highThroughput = this.isHighThroughputApplyOperation(operation);
        return this.budgetPlanner.plan(fraction, operation.adaptiveScale(), highThroughput);
    }

    private boolean isHighThroughputApplyOperation(ActiveOperation operation) {
        return operation != null
                && operation.handle() != null
                && this.applyOperationProfile.isHighThroughput(operation.handle().label());
    }

    private void ensureIdle(String serverKey) {
        if (this.activeOperations.containsKey(serverKey)) {
            throw new IllegalStateException("Another world operation is already running");
        }
    }

    private synchronized ExecutorService executor() {
        if (this.backgroundExecutor == null || this.backgroundExecutor.isShutdown() || this.backgroundExecutor.isTerminated()) {
            this.backgroundExecutor = createExecutor();
            LumaMod.LOGGER.info("Recreated world operation executor");
        }
        return this.backgroundExecutor;
    }

    private String serverKey(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().toString();
    }

    private static ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(1, new NamedThreadFactory());
    }

    @FunctionalInterface
    public interface BackgroundWork {
        void run(ProgressSink progressSink) throws Exception;
    }

    @FunctionalInterface
    public interface PreparedApplyWork {
        PreparedApplyOperation prepare(ProgressSink progressSink) throws Exception;
    }

    @FunctionalInterface
    public interface ProgressSink {
        void update(OperationStage stage, int completedUnits, int totalUnits, String detail);
    }

    @FunctionalInterface
    public interface CompletionAction {
        void run() throws Exception;
    }

    public record PreparedApplyOperation(
            LocalQueue localQueue,
            CompletionAction onComplete,
            BatchProcessor batchProcessor,
            HistoryStore historyStore
    ) {

        public PreparedApplyOperation(List<PreparedChunkBatch> batches, CompletionAction onComplete) {
            this(
                    LocalQueue.completed(batches == null
                            ? List.of()
                            : batches.stream().map(ChunkBatch::fromPrepared).toList()),
                    onComplete,
                    BatchProcessor.NO_OP,
                    HistoryStore.NO_OP
            );
        }

        public int totalWorkUnits() {
            return this.localQueue == null ? 0 : this.localQueue.totalWorkUnits();
        }
    }

    private abstract static class ActiveOperation {

        private final ServerLevel level;
        private final OperationHandle handle;
        private final String unitLabel;
        private volatile OperationSnapshot snapshot;
        private volatile OperationStage lastLoggedStage;
        private volatile int lastLoggedPercent = -1;
        private volatile double adaptiveScale = 1.0D;

        private ActiveOperation(ServerLevel level, OperationHandle handle, String unitLabel) {
            this.level = level;
            this.handle = handle;
            this.unitLabel = unitLabel == null || unitLabel.isBlank() ? "items" : unitLabel;
            this.snapshot = new OperationSnapshot(
                    handle,
                    OperationStage.QUEUED,
                    OperationProgress.empty(this.unitLabel),
                    "",
                    Instant.now()
            );
            this.lastLoggedStage = OperationStage.QUEUED;
            LumaDebugLog.log(
                    this.handle,
                    "world-op",
                    "Created operation {} for project {} with unit={}",
                    this.handle.label(),
                    this.handle.projectId(),
                    this.unitLabel
            );
        }

        protected ServerLevel level() {
            return this.level;
        }

        public OperationHandle handle() {
            return this.handle;
        }

        public OperationSnapshot snapshot() {
            return this.snapshot;
        }

        protected double adaptiveScale() {
            return this.adaptiveScale;
        }

        protected void recordAdvanceCost(long elapsedNanos, long budgetNanos) {
            if (budgetNanos <= 0L || elapsedNanos <= 0L) {
                return;
            }
            if (elapsedNanos > budgetNanos && this.adaptiveScale > MIN_ADAPTIVE_SCALE) {
                this.adaptiveScale = Math.max(MIN_ADAPTIVE_SCALE, this.adaptiveScale * 0.75D);
                return;
            }
            if (elapsedNanos < budgetNanos / 2L && this.adaptiveScale < MAX_ADAPTIVE_SCALE) {
                this.adaptiveScale = Math.min(MAX_ADAPTIVE_SCALE, this.adaptiveScale * 1.08D);
            }
        }

        protected ProgressSink progressSink() {
            return (stage, completedUnits, totalUnits, detail) -> {
                OperationProgress progress = new OperationProgress(Math.max(0, completedUnits), Math.max(0, totalUnits), this.unitLabel);
                String normalizedDetail = detail == null ? "" : detail;
                this.snapshot = new OperationSnapshot(
                        this.handle,
                        stage,
                        progress,
                        normalizedDetail,
                        Instant.now()
                );
                this.logProgressIfNeeded(stage, progress, normalizedDetail);
            };
        }

        protected void complete(String detail) {
            OperationProgress progress = this.snapshot.progress();
            int completed = progress.totalUnits() <= 0 ? progress.completedUnits() : progress.totalUnits();
            this.snapshot = new OperationSnapshot(
                    this.handle,
                    OperationStage.COMPLETED,
                    new OperationProgress(completed, Math.max(completed, progress.totalUnits()), this.unitLabel),
                    detail == null ? "" : detail,
                    Instant.now()
            );
            LumaMod.LOGGER.info(
                    "Completed world operation {} for project {} in {} ms",
                    this.handle.label(),
                    this.handle.projectId(),
                    java.time.Duration.between(this.handle.startedAt(), Instant.now()).toMillis()
            );
            LumaDebugLog.log(
                    this.handle,
                    "world-op",
                    "Completed operation {} with detail='{}' and progress {}",
                    this.handle.label(),
                    detail,
                    this.snapshot.progress()
            );
        }

        protected void fail(Exception exception) {
            this.snapshot = new OperationSnapshot(
                    this.handle,
                    OperationStage.FAILED,
                    this.snapshot.progress(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                    Instant.now()
            );
            LumaMod.LOGGER.warn(
                    "Failed world operation {} for project {} after {} ms",
                    this.handle.label(),
                    this.handle.projectId(),
                    java.time.Duration.between(this.handle.startedAt(), Instant.now()).toMillis(),
                    exception
            );
            LumaDebugLog.log(
                    this.handle,
                    "world-op",
                    "Failed operation {} with detail='{}'",
                    this.handle.label(),
                    this.snapshot.detail()
            );
        }

        abstract boolean advance(WorldApplyBudget budget, long deadlineNanos) throws Exception;

        private void logProgressIfNeeded(OperationStage stage, OperationProgress progress, String detail) {
            int percent = progress.totalUnits() <= 0
                    ? -1
                    : Math.max(0, Math.min(100, (int) Math.floor(progress.fraction() * 100.0D)));
            int quantizedPercent = percent < 0 ? -1 : (percent / 10) * 10;
            boolean stageChanged = stage != this.lastLoggedStage;
            boolean progressChanged = quantizedPercent >= 0 && quantizedPercent > this.lastLoggedPercent;
            if (!stageChanged && !progressChanged) {
                return;
            }

            this.lastLoggedStage = stage;
            if (quantizedPercent >= 0) {
                this.lastLoggedPercent = quantizedPercent;
            }
            LumaMod.LOGGER.info(
                    "World operation {} stage={} progress={}/{} {} detail={}",
                    this.handle.label(),
                    stage,
                    progress.completedUnits(),
                    progress.totalUnits(),
                    progress.unitLabel(),
                    detail
            );
        }
    }

    private final class BackgroundActiveOperation extends ActiveOperation {

        private final CompletableFuture<Void> future;

        private BackgroundActiveOperation(
                ServerLevel level,
                OperationHandle handle,
                String unitLabel,
                BackgroundWork work
        ) {
            super(level, handle, unitLabel);
            this.future = CompletableFuture.runAsync(() -> {
                try {
                    LumaDebugLog.log(this.handle(), "world-op", "Background worker thread started for {}", this.handle().label());
                    this.progressSink().update(OperationStage.PREPARING, 0, 0, "Starting");
                    work.run(this.progressSink());
                    this.complete("Completed");
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, WorldOperationManager.this.executor());
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) throws Exception {
            if (!this.future.isDone()) {
                return false;
            }
            try {
                this.future.join();
                return true;
            } catch (CompletionException exception) {
                Exception cause = exception.getCause() instanceof Exception
                        ? (Exception) exception.getCause()
                        : new RuntimeException(exception.getCause());
                this.fail(cause);
                throw cause;
            }
        }
    }

    private final class PreparedApplyActiveOperation extends ActiveOperation {

        private final CompletableFuture<PreparedApplyOperation> future;
        private PreparedApplyOperation prepared;
        private GlobalDispatcher dispatcher;
        private ChunkBatch currentBatch;
        private List<PreparedSectionApplyBatch> currentNativeSections = List.of();
        private List<SectionBatch> currentSections = List.of();
        private List<Map.Entry<BlockPos, CompoundTag>> currentBlockEntities = List.of();
        private CompletableFuture<Void> completionFuture;
        private int nativeSectionIndex = 0;
        private int sectionIndex = 0;
        private int placementIndex = 0;
        private int blockEntityIndex = 0;
        private int entityIndex = 0;
        private boolean blockEntitiesApplied = false;
        private boolean entitiesApplied = false;
        private int appliedWorkUnits = 0;
        private final WorldApplyMetrics applyMetrics = new WorldApplyMetrics();

        private PreparedApplyActiveOperation(
                ServerLevel level,
                OperationHandle handle,
                String unitLabel,
                PreparedApplyWork work
        ) {
            super(level, handle, unitLabel);
            this.future = CompletableFuture.supplyAsync(() -> {
                try {
                    this.progressSink().update(OperationStage.PREPARING, 0, 0, "Preparing");
                    return work.prepare(this.progressSink());
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, WorldOperationManager.this.executor());
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) throws Exception {
            if (this.prepared == null) {
                if (!this.future.isDone()) {
                    return false;
                }
                try {
                    this.prepared = this.future.join();
                } catch (CompletionException exception) {
                    Exception cause = exception.getCause() instanceof Exception
                            ? (Exception) exception.getCause()
                            : new RuntimeException(exception.getCause());
                    this.fail(cause);
                    throw cause;
                }

                this.dispatcher = new GlobalDispatcher();
                this.dispatcher.enqueue(this.prepared.localQueue());
                LumaDebugLog.log(
                        this.handle(),
                        "world-op",
                        "Prepared operation {} loaded {} work units across {} ready chunk batches",
                        this.handle().label(),
                        this.prepared.totalWorkUnits(),
                        this.prepared.localQueue().completedCount()
                );
                this.progressSink().update(
                        OperationStage.APPLYING,
                        0,
                        this.prepared.totalWorkUnits(),
                        "Applying prepared batches"
                );
                if (this.prepared.totalWorkUnits() == 0) {
                    return this.advanceCompletion();
                }
            }

            int processedWorkThisTick = 0;
            int processedNativeSectionsThisTick = 0;
            while (System.nanoTime() < deadlineNanos) {
                if (this.currentBatch == null) {
                    this.currentBatch = this.dispatcher.pollNext();
                    if (this.currentBatch == null) {
                        break;
                    }
                    this.currentBatch = this.prepared.batchProcessor().processSet(this.currentBatch);
                    this.currentNativeSections = this.currentBatch.orderedNativeSections();
                    this.currentSections = this.currentBatch.orderedSections();
                    this.currentBlockEntities = List.copyOf(this.currentBatch.blockEntities().entrySet());
                    this.nativeSectionIndex = 0;
                    this.sectionIndex = 0;
                    this.placementIndex = 0;
                    this.blockEntityIndex = 0;
                    this.entityIndex = 0;
                    this.blockEntitiesApplied = false;
                    this.entitiesApplied = false;
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op",
                            "Applying chunk batch {}:{} with {} placements across {} sections, {} block entities, and {} entity operations",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            this.currentBatch.totalPlacements(),
                            this.currentSections.size() + this.currentNativeSections.size(),
                            this.currentBlockEntities.size(),
                            BlockChangeApplier.entityOperationCount(this.currentBatch.entityBatch())
                    );
                }

                if (this.hasPendingNativeSection()) {
                    if (processedNativeSectionsThisTick >= budget.maxNativeSections()) {
                        break;
                    }
                } else if (processedWorkThisTick >= budget.maxBlocks()) {
                    break;
                }

                WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.pushCaptureSuppression();
                AppliedWork processed;
                try {
                    int maxBlocks = this.hasPendingNativeSection()
                            ? Integer.MAX_VALUE
                            : Math.min(budget.maxBlocks() - processedWorkThisTick, 128);
                    processed = this.applyCurrentChunk(maxBlocks);
                } finally {
                    WorldMutationContext.popCaptureSuppression();
                    WorldMutationContext.popSource();
                }

                if (processed.workUnits() <= 0 && this.currentBatch != null && !this.currentBatchFinished()) {
                    break;
                }

                this.appliedWorkUnits += processed.workUnits();
                processedWorkThisTick += processed.workUnits();
                processedNativeSectionsThisTick += processed.nativeSections();

                this.progressSink().update(
                        OperationStage.APPLYING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.currentBatch == null
                                ? "Applying queued chunks"
                                : "Applying chunk " + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                );
                if (this.currentBatch != null && this.currentBatchFinished()) {
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op",
                            "Finished chunk batch {}:{} after applying {} total work units so far",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            this.appliedWorkUnits
                    );
                    this.prepared.historyStore().record(this.currentBatch);
                    this.prepared.batchProcessor().postProcessSet(this.currentBatch);
                    this.currentBatch = null;
                    this.currentNativeSections = List.of();
                    this.currentSections = List.of();
                    this.currentBlockEntities = List.of();
                }
            }

            if (this.currentBatch == null && (this.dispatcher == null || !this.dispatcher.hasPending())) {
                this.progressSink().update(
                        OperationStage.FINALIZING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        "Finalizing"
                );
                LumaDebugLog.log(
                        this.handle(),
                        "world-op",
                        "Finalizing prepared operation {} after {} applied work units with fast-apply metrics: {}",
                        this.handle().label(),
                        this.appliedWorkUnits,
                        this.applyMetrics.summary()
                );
                return this.advanceCompletion();
            }

            return false;
        }

        private boolean advanceCompletion() throws Exception {
            if (this.completionFuture == null) {
                this.progressSink().update(
                        OperationStage.FINALIZING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        "Finalizing"
                );
                this.completionFuture = CompletableFuture.runAsync(() -> {
                    try {
                        this.prepared.onComplete().run();
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                }, WorldOperationManager.this.executor());
                return false;
            }
            if (!this.completionFuture.isDone()) {
                return false;
            }
            try {
                this.completionFuture.join();
                this.complete("Completed");
                return true;
            } catch (CompletionException exception) {
                Exception cause = exception.getCause() instanceof Exception
                        ? (Exception) exception.getCause()
                        : new RuntimeException(exception.getCause());
                this.fail(cause);
                throw cause;
            }
        }

        private AppliedWork applyCurrentChunk(int maxBlocks) {
            if (this.currentBatch == null) {
                return AppliedWork.none();
            }

            if (this.hasPendingNativeSection()) {
                PreparedSectionApplyBatch nativeSection = this.currentNativeSections.get(this.nativeSectionIndex);
                int processed = BlockChangeApplier.applyNativeSectionBatch(
                        this.level(),
                        nativeSection,
                        this.applyMetrics
                );
                this.nativeSectionIndex += 1;
                return new AppliedWork(processed, 1);
            }

            if (this.sectionIndex < this.currentSections.size()) {
                SectionBatch section = this.currentSections.get(this.sectionIndex);
                int processed = BlockChangeApplier.applySectionBatch(
                        this.level(),
                        section,
                        this.placementIndex,
                        maxBlocks,
                        this.applyMetrics
                );
                this.placementIndex += processed;
                if (this.placementIndex >= section.placementCount()) {
                    this.sectionIndex += 1;
                    this.placementIndex = 0;
                }
                return new AppliedWork(processed, 0);
            }

            if (!this.blockEntitiesApplied) {
                if (this.currentBlockEntities.isEmpty()) {
                    this.blockEntitiesApplied = true;
                } else {
                    int processed = BlockChangeApplier.applyBlockEntities(
                            this.level(),
                            this.currentBlockEntities,
                            this.blockEntityIndex,
                            Math.min(maxBlocks, MAX_BLOCK_ENTITIES_PER_TICK),
                            this.applyMetrics
                    );
                    this.blockEntityIndex += processed;
                    if (this.blockEntityIndex >= this.currentBlockEntities.size()) {
                        this.blockEntitiesApplied = true;
                    }
                    return new AppliedWork(processed, 0);
                }
            }

            if (!this.entitiesApplied) {
                int entityOperationCount = BlockChangeApplier.entityOperationCount(this.currentBatch.entityBatch());
                if (entityOperationCount <= 0) {
                    this.entitiesApplied = true;
                    return AppliedWork.none();
                }
                int processed = BlockChangeApplier.applyEntityBatch(
                        this.level(),
                        this.currentBatch.entityBatch(),
                        this.entityIndex,
                        Math.min(maxBlocks, MAX_ENTITY_OPERATIONS_PER_TICK)
                );
                this.entityIndex += processed;
                if (this.entityIndex >= entityOperationCount) {
                    this.entitiesApplied = true;
                }
                return new AppliedWork(processed, 0);
            }

            return AppliedWork.none();
        }

        private boolean hasPendingNativeSection() {
            return this.currentBatch != null && this.nativeSectionIndex < this.currentNativeSections.size();
        }

        private boolean currentBatchFinished() {
            return this.currentBatch != null
                    && this.nativeSectionIndex >= this.currentNativeSections.size()
                    && this.sectionIndex >= this.currentSections.size()
                    && this.blockEntitiesApplied
                    && this.entitiesApplied;
        }

        private record AppliedWork(int workUnits, int nativeSections) {

            private static AppliedWork none() {
                return new AppliedWork(0, 0);
            }
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private int nextIndex = 1;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Lumi-WorldOp-" + this.nextIndex++);
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    }
}
