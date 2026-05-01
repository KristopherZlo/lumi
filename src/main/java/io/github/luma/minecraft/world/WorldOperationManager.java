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
import java.time.Duration;
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
    private final WorldApplyTickWorkGate tickWorkGate = new WorldApplyTickWorkGate();
    private ExecutorService backgroundExecutor = createExecutor();
    private final Map<String, ActiveOperation> activeOperations = new HashMap<>();
    private final Map<String, OperationSnapshot> lastSnapshots = new HashMap<>();
    private final Map<String, String> lastApplyMetrics = new HashMap<>();

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

    public synchronized Optional<String> applyMetrics(OperationHandle handle) {
        if (handle == null || handle.id() == null || handle.id().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.lastApplyMetrics.get(handle.id()));
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
            operation.applyMetricsSummary()
                    .ifPresent(metrics -> this.lastApplyMetrics.put(operation.handle().id(), metrics));
        }
    }

    private WorldApplyBudget currentTickBudget(ActiveOperation operation) {
        double fraction = operation.snapshot().progress().fraction();
        WorldApplyProfile profile = this.applyProfile(operation);
        return this.budgetPlanner.plan(fraction, operation.adaptiveScale(), profile);
    }

    private WorldApplyProfile applyProfile(ActiveOperation operation) {
        if (operation == null
                || operation.handle() == null) {
            return WorldApplyProfile.NORMAL;
        }
        return this.applyOperationProfile.profileFor(operation.handle().label());
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

        protected Optional<String> applyMetricsSummary() {
            return Optional.empty();
        }

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
        private final long preparationStartedAtNanos;
        private final WorldApplyProfile profile;
        private PreparedApplyOperation prepared;
        private WorldApplyChunkPreloader chunkPreloader;
        private GlobalDispatcher dispatcher;
        private ChunkBatch currentBatch;
        private List<PreparedSectionApplyBatch> currentNativeSections = List.of();
        private List<SectionBatch> currentSections = List.of();
        private List<Map.Entry<BlockPos, CompoundTag>> currentBlockEntities = List.of();
        private CompletableFuture<Void> completionFuture;
        private int nativeSectionIndex = 0;
        private NativeSectionApplyCursor nativeSectionCursor;
        private int sectionIndex = 0;
        private int placementIndex = 0;
        private int blockEntityIndex = 0;
        private int entityIndex = 0;
        private boolean blockEntitiesApplied = false;
        private boolean entitiesApplied = false;
        private int appliedWorkUnits = 0;
        private String preparationMarkerDetail = "";
        private final WorldApplyMetrics applyMetrics = new WorldApplyMetrics();
        private final WorldLightUpdateQueue lightUpdateQueue = new WorldLightUpdateQueue();

        private PreparedApplyActiveOperation(
                ServerLevel level,
                OperationHandle handle,
                String unitLabel,
                PreparedApplyWork work
        ) {
            super(level, handle, unitLabel);
            this.profile = WorldOperationManager.this.applyOperationProfile.profileFor(handle.label());
            this.preparationStartedAtNanos = System.nanoTime();
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

                this.applyMetrics.recordPreparationDuration(System.nanoTime() - this.preparationStartedAtNanos);
                this.preparationMarkerDetail = this.preservedPreparationMarker(this.snapshot().detail());
                this.chunkPreloader = WorldApplyChunkPreloader.create(this.prepared.localQueue(), this.profile);
                LumaDebugLog.log(
                        this.handle(),
                        "world-op",
                        "Prepared operation {} loaded {} work units across {} ready chunk batches and {} preload chunks",
                        this.handle().label(),
                        this.prepared.totalWorkUnits(),
                        this.prepared.localQueue().completedCount(),
                        this.chunkPreloader.totalChunks()
                );
                if (this.prepared.totalWorkUnits() == 0) {
                    return this.advanceCompletion();
                }
            }

            if (this.chunkPreloader != null && this.chunkPreloader.required() && !this.chunkPreloader.complete()) {
                return this.advancePreload(budget, deadlineNanos);
            }

            if (this.dispatcher == null) {
                this.startApply();
            }

            int processedWorkThisTick = 0;
            int processedNativeSectionsThisTick = 0;
            int processedNativeCellsThisTick = 0;
            int processedRewriteSectionsThisTick = 0;
            int processedDirectSectionsThisTick = 0;
            int startedChunksThisTick = 0;
            int finishedChunksThisTick = 0;
            int tickStartProcessedBlocks = this.applyMetrics.processedBlocks();
            int tickStartRewriteSections = this.applyMetrics.rewriteSections();
            int tickStartNativeSections = this.applyMetrics.nativeSections();
            int tickStartFallbackSections = this.applyMetrics.fallbackSections();
            int tickStartLightChecks = this.applyMetrics.lightChecks();
            long applyTickStartedAt = System.nanoTime();
            String stopReason = "deadline";
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Apply tick start label={} progress={}/{} adaptiveScale={} budget=[{}] currentBatch={} dispatcherPending={} lightPending={}",
                        this.handle().label(),
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.adaptiveScale(),
                        budget.summary(),
                        this.currentBatch == null ? "none" : this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z(),
                        this.dispatcher != null && this.dispatcher.hasPending(),
                        this.lightUpdateQueue.pendingCount()
                );
            }
            while (System.nanoTime() < deadlineNanos) {
                if (this.currentBatch == null) {
                    this.currentBatch = this.dispatcher.pollNext();
                    if (this.currentBatch == null) {
                        stopReason = "dispatcher-empty";
                        break;
                    }
                    this.currentBatch = this.prepared.batchProcessor().processSet(this.currentBatch);
                    startedChunksThisTick += 1;
                    this.currentNativeSections = this.currentBatch.orderedNativeSections();
                    this.currentSections = this.currentBatch.orderedSections();
                    this.currentBlockEntities = List.copyOf(this.currentBatch.blockEntities().entrySet());
                    this.nativeSectionIndex = 0;
                    this.nativeSectionCursor = null;
                    this.sectionIndex = 0;
                    this.placementIndex = 0;
                    this.blockEntityIndex = 0;
                    this.entityIndex = 0;
                    this.blockEntitiesApplied = false;
                    this.entitiesApplied = false;
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Chunk batch start {}:{} placements={} nativeSections={} rewriteSections={} nativeCells={} rewriteCells={} sparseSections={} sparsePlacements={} blockEntities={} entityOps={}",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                this.currentBatch.totalPlacements(),
                                this.currentNativeSections.size(),
                                this.rewriteSectionCount(this.currentBatch),
                                this.nativeCellCount(this.currentBatch),
                                this.rewriteCellCount(this.currentBatch),
                                this.currentSections.size(),
                                this.sparsePlacementCount(this.currentBatch),
                                this.currentBlockEntities.size(),
                                BlockChangeApplier.entityOperationCount(this.currentBatch.entityBatch())
                        );
                    }
                }

                WorldApplyTickGateDecision decision = WorldOperationManager.this.tickWorkGate.decide(
                        this.hasPendingNativeSection(),
                        this.hasPendingNativeSection() ? this.pendingNativeSection().safetyProfile().path() : null,
                        processedWorkThisTick,
                        processedNativeSectionsThisTick,
                        processedNativeCellsThisTick,
                        processedRewriteSectionsThisTick,
                        processedDirectSectionsThisTick,
                        budget
                );
                if (!decision.canStart()) {
                    stopReason = decision.reason();
                    break;
                }

                WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.pushCaptureSuppression();
                WorldLightUpdateContext.push(this.lightUpdateQueue);
                AppliedWork processed;
                try {
                    int maxBlocks = this.maxWorkForCurrentStep(budget, processedWorkThisTick, processedNativeCellsThisTick);
                    int maxDirectSections = Math.max(0, budget.maxDirectSections() - processedDirectSectionsThisTick);
                    processed = this.applyCurrentChunk(maxBlocks, maxDirectSections);
                } finally {
                    WorldLightUpdateContext.pop();
                    WorldMutationContext.popCaptureSuppression();
                    WorldMutationContext.popSource();
                }

                if (processed.workUnits() <= 0 && this.currentBatch != null && !this.currentBatchFinished()) {
                    stopReason = "no-progress";
                    break;
                }

                this.appliedWorkUnits += processed.workUnits();
                processedWorkThisTick += processed.workUnits();
                processedNativeSectionsThisTick += processed.nativeSections();
                processedNativeCellsThisTick += processed.nativeCells();
                processedRewriteSectionsThisTick += processed.rewriteSections();
                processedDirectSectionsThisTick += processed.directSections();

                this.progressSink().update(
                        OperationStage.APPLYING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.applyDetail(this.currentBatch == null
                                ? "Applying queued chunks"
                                : "Applying chunk " + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z())
                );
                if (this.currentBatch != null && this.currentBatchFinished()) {
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Chunk batch finish {}:{} totalApplied={} metrics=[{}]",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                this.appliedWorkUnits,
                                this.applyMetrics.summary()
                        );
                    }
                    finishedChunksThisTick += 1;
                    this.prepared.historyStore().record(this.currentBatch);
                    this.prepared.batchProcessor().postProcessSet(this.currentBatch);
                    this.currentBatch = null;
                    this.currentNativeSections = List.of();
                    this.currentSections = List.of();
                    this.currentBlockEntities = List.of();
                    this.nativeSectionCursor = null;
                }
            }
            if (System.nanoTime() >= deadlineNanos && !"dispatcher-empty".equals(stopReason)) {
                stopReason = "time-budget";
            }
            this.logApplyTickSummary(
                    stopReason,
                    processedWorkThisTick,
                    processedNativeSectionsThisTick,
                    processedNativeCellsThisTick,
                    processedRewriteSectionsThisTick,
                    processedDirectSectionsThisTick,
                    startedChunksThisTick,
                    finishedChunksThisTick,
                    tickStartProcessedBlocks,
                    tickStartRewriteSections,
                    tickStartNativeSections,
                    tickStartFallbackSections,
                    tickStartLightChecks
            );
            this.applyMetrics.recordApplyTick(processedWorkThisTick, System.nanoTime() - applyTickStartedAt);

            if (this.currentBatch == null && (this.dispatcher == null || !this.dispatcher.hasPending())) {
                if (!this.drainDeferredLightUpdates(budget, deadlineNanos)) {
                    return false;
                }
                this.progressSink().update(
                        OperationStage.FINALIZING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.applyDetail("Finalizing")
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

        private boolean advancePreload(WorldApplyBudget budget, long deadlineNanos) {
            long startedAt = System.nanoTime();
            WorldApplyChunkPreloader.PreloadTickResult result = this.chunkPreloader.advance(
                    new ServerLevelChunkPreloadAccess(this.level()),
                    budget,
                    deadlineNanos
            );
            long elapsedNanos = System.nanoTime() - startedAt;
            this.applyMetrics.recordPreloadTick(
                    result.newlyLoadedChunks(),
                    result.alreadyLoadedChunks(),
                    elapsedNanos
            );
            this.progressSink().update(
                    OperationStage.PRELOADING,
                    result.completedChunks(),
                    result.totalChunks(),
                    this.applyDetail("Preloading chunks " + result.completedChunks() + "/" + result.totalChunks())
            );
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Preload tick chunks={}/{} newlyLoaded={} alreadyLoaded={} elapsedMicros={} complete={}",
                        result.completedChunks(),
                        result.totalChunks(),
                        result.newlyLoadedChunks(),
                        result.alreadyLoadedChunks(),
                        elapsedNanos / 1_000L,
                        result.complete()
                );
            }
            return false;
        }

        private void startApply() {
            this.dispatcher = new GlobalDispatcher();
            this.dispatcher.enqueue(this.prepared.localQueue());
            this.progressSink().update(
                    OperationStage.APPLYING,
                    0,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Applying prepared batches")
            );
        }

        private boolean drainDeferredLightUpdates(WorldApplyBudget budget, long deadlineNanos) {
            if (!this.lightUpdateQueue.hasPending()) {
                return true;
            }

            int maxChecks = Math.max(128, budget.maxLightChecks());
            int pendingBefore = this.lightUpdateQueue.pendingCount();
            long startedAt = System.nanoTime();
            int appliedChecks = this.lightUpdateQueue.drain(this.level(), maxChecks, deadlineNanos);
            long elapsedNanos = System.nanoTime() - startedAt;
            this.applyMetrics.recordLightChecks(appliedChecks);
            this.applyMetrics.recordLightDrainTick(elapsedNanos);
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Light drain maxChecks={} applied={} pendingBefore={} pendingAfter={} elapsedMicros={}",
                        maxChecks,
                        appliedChecks,
                        pendingBefore,
                        this.lightUpdateQueue.pendingCount(),
                        elapsedNanos / 1_000L
                );
            }
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.appliedWorkUnits,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Updating light, " + this.lightUpdateQueue.pendingCount() + " checks queued")
            );
            return !this.lightUpdateQueue.hasPending();
        }

        private String preservedPreparationMarker(String detail) {
            if (detail == null || !detail.startsWith("Decoded initial snapshot")) {
                return "";
            }
            return detail;
        }

        private String applyDetail(String detail) {
            if (this.preparationMarkerDetail.isBlank()) {
                return detail;
            }
            return this.preparationMarkerDetail + "; " + detail;
        }

        private void logApplyTickSummary(
                String stopReason,
                int processedWorkThisTick,
                int processedNativeSectionsThisTick,
                int processedNativeCellsThisTick,
                int processedRewriteSectionsThisTick,
                int processedDirectSectionsThisTick,
                int startedChunksThisTick,
                int finishedChunksThisTick,
                int tickStartProcessedBlocks,
                int tickStartRewriteSections,
                int tickStartNativeSections,
                int tickStartFallbackSections,
                int tickStartLightChecks
        ) {
            if (!this.debugApplyEnabled()) {
                return;
            }
            LumaDebugLog.log(
                    this.handle(),
                    "world-op-apply",
                    "Apply tick stop={} workThisTick={} nativeSectionsThisTick={} nativeCellsThisTick={} rewriteSectionsThisTick={} directSectionsThisTick={} chunksStarted={} chunksFinished={} totalsDelta=[processedBlocks={}, rewriteSections={}, nativeSections={}, fallbackSections={}, lightChecks={}] currentBatch={} dispatcherPending={} lightPending={}",
                    stopReason,
                    processedWorkThisTick,
                    processedNativeSectionsThisTick,
                    processedNativeCellsThisTick,
                    processedRewriteSectionsThisTick,
                    processedDirectSectionsThisTick,
                    startedChunksThisTick,
                    finishedChunksThisTick,
                    this.applyMetrics.processedBlocks() - tickStartProcessedBlocks,
                    this.applyMetrics.rewriteSections() - tickStartRewriteSections,
                    this.applyMetrics.nativeSections() - tickStartNativeSections,
                    this.applyMetrics.fallbackSections() - tickStartFallbackSections,
                    this.applyMetrics.lightChecks() - tickStartLightChecks,
                    this.currentBatch == null ? "none" : this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z(),
                    this.dispatcher != null && this.dispatcher.hasPending(),
                    this.lightUpdateQueue.pendingCount()
            );
        }

        private int maxWorkForCurrentStep(
                WorldApplyBudget budget,
                int processedWorkThisTick,
                int processedNativeCellsThisTick
        ) {
            if (!this.hasPendingNativeSection()) {
                int remainingBlocks = budget.maxBlocks() - processedWorkThisTick;
                return Math.min(remainingBlocks, budget.sparseStepCap());
            }
            PreparedSectionApplyBatch nativeSection = this.pendingNativeSection();
            if (nativeSection.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                return Integer.MAX_VALUE;
            }
            return Math.max(0, budget.maxNativeCells() - processedNativeCellsThisTick);
        }

        private boolean advanceCompletion() throws Exception {
            if (this.completionFuture == null) {
                this.releasePreloadTickets();
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

        private AppliedWork applyCurrentChunk(int maxBlocks, int maxDirectSections) {
            if (this.currentBatch == null) {
                return AppliedWork.none();
            }

            if (this.hasPendingNativeSection()) {
                PreparedSectionApplyBatch nativeSection = this.currentNativeSections.get(this.nativeSectionIndex);
                if (this.nativeSectionCursor == null || !this.nativeSectionCursor.isFor(nativeSection)) {
                    this.nativeSectionCursor = new NativeSectionApplyCursor(nativeSection);
                }
                long startedAt = System.nanoTime();
                NativeSectionApplyResult result = BlockChangeApplier.applyNativeSectionBatch(
                        this.level(),
                        this.nativeSectionCursor,
                        maxBlocks,
                        this.applyMetrics
                );
                int nativeCells = nativeSection.safetyProfile().path() == SectionApplyPath.SECTION_NATIVE
                        ? result.processedCells()
                        : 0;
                int completedNativeSections = result.completedSection() ? 1 : 0;
                int completedRewriteSections = nativeSection.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE
                        && result.completedSection()
                        ? 1
                        : 0;
                if (result.completedSection()) {
                    this.nativeSectionIndex += 1;
                    this.nativeSectionCursor = null;
                }
                if (this.debugApplyEnabled()) {
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op-apply",
                            "Native section step chunk={}:{} sectionY={} path={} cells={} maxBlocks={} processed={} completed={} elapsedMicros={} commit=[{}]",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            nativeSection.sectionY(),
                            nativeSection.safetyProfile().path(),
                            nativeSection.changedCellCount(),
                            maxBlocks,
                            result.processedCells(),
                            result.completedSection(),
                            microsSince(startedAt),
                            this.commitSummary(result.commitResult())
                    );
                }
                return new AppliedWork(
                        result.processedCells(),
                        completedNativeSections,
                        nativeCells,
                        completedRewriteSections,
                        0
                );
            }

            if (this.sectionIndex < this.currentSections.size()) {
                long startedAt = System.nanoTime();
                DirectChunkApplyResult result = BlockChangeApplier.applyDirectChunkSections(
                        this.level(),
                        this.currentBatch,
                        this.sectionIndex,
                        this.placementIndex,
                        maxBlocks,
                        maxDirectSections,
                        this.applyMetrics
                );
                int previousSectionIndex = this.sectionIndex;
                this.sectionIndex = result.nextSectionIndex();
                this.placementIndex = result.nextPlacementIndex();
                if (this.debugApplyEnabled()) {
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op-apply",
                            "Sparse chunk step chunk={}:{} sectionIndex={}->{} maxBlocks={} maxDirectSections={} processed={} nextPlacement={} directSections={} completed={} elapsedMicros={} commit=[{}]",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            previousSectionIndex,
                            this.sectionIndex,
                            maxBlocks,
                            maxDirectSections,
                            result.processedBlocks(),
                            this.placementIndex,
                            result.commitResult().directSections(),
                            this.sectionIndex >= this.currentSections.size(),
                            microsSince(startedAt),
                            this.commitSummary(result.commitResult())
                    );
                }
                return new AppliedWork(
                        result.processedBlocks(),
                        0,
                        0,
                        0,
                        result.commitResult().directSections()
                );
            }

            if (!this.blockEntitiesApplied) {
                if (this.currentBlockEntities.isEmpty()) {
                    this.blockEntitiesApplied = true;
                } else {
                    long startedAt = System.nanoTime();
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
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Block-entity step chunk={}:{} max={} processed={} nextIndex={} total={} completed={} elapsedMicros={}",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                Math.min(maxBlocks, MAX_BLOCK_ENTITIES_PER_TICK),
                                processed,
                                this.blockEntityIndex,
                                this.currentBlockEntities.size(),
                                this.blockEntitiesApplied,
                                microsSince(startedAt)
                        );
                    }
                    return new AppliedWork(processed, 0, 0, 0, 0);
                }
            }

            if (!this.entitiesApplied) {
                int entityOperationCount = BlockChangeApplier.entityOperationCount(this.currentBatch.entityBatch());
                if (entityOperationCount <= 0) {
                    this.entitiesApplied = true;
                    return AppliedWork.none();
                }
                long startedAt = System.nanoTime();
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
                if (this.debugApplyEnabled()) {
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op-apply",
                            "Entity step chunk={}:{} max={} processed={} nextIndex={} total={} completed={} elapsedMicros={}",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            Math.min(maxBlocks, MAX_ENTITY_OPERATIONS_PER_TICK),
                            processed,
                            this.entityIndex,
                            entityOperationCount,
                            this.entitiesApplied,
                            microsSince(startedAt)
                    );
                }
                return new AppliedWork(processed, 0, 0, 0, 0);
            }

            return AppliedWork.none();
        }

        private boolean hasPendingNativeSection() {
            return this.currentBatch != null && this.nativeSectionIndex < this.currentNativeSections.size();
        }

        private PreparedSectionApplyBatch pendingNativeSection() {
            return this.currentNativeSections.get(this.nativeSectionIndex);
        }

        private boolean currentBatchFinished() {
            return this.currentBatch != null
                    && this.nativeSectionIndex >= this.currentNativeSections.size()
                    && this.sectionIndex >= this.currentSections.size()
                    && this.blockEntitiesApplied
                    && this.entitiesApplied;
        }

        private boolean debugApplyEnabled() {
            return LumaDebugLog.enabled(this.handle());
        }

        private int rewriteSectionCount(ChunkBatch batch) {
            if (batch == null) {
                return 0;
            }
            int count = 0;
            for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
                if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                    count += 1;
                }
            }
            return count;
        }

        private int nativeCellCount(ChunkBatch batch) {
            if (batch == null) {
                return 0;
            }
            int count = 0;
            for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
                if (section.safetyProfile().path() == SectionApplyPath.SECTION_NATIVE) {
                    count += section.changedCellCount();
                }
            }
            return count;
        }

        private int rewriteCellCount(ChunkBatch batch) {
            if (batch == null) {
                return 0;
            }
            int count = 0;
            for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
                if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                    count += section.changedCellCount();
                }
            }
            return count;
        }

        private int sparsePlacementCount(ChunkBatch batch) {
            if (batch == null) {
                return 0;
            }
            int count = 0;
            for (SectionBatch section : batch.sections().values()) {
                count += section.placementCount();
            }
            return count;
        }

        private String commitSummary(BlockCommitResult result) {
            if (result == null) {
                return "partial";
            }
            return "processed=" + result.processedBlocks()
                    + ", changed=" + result.changedBlocks()
                    + ", skipped=" + result.skippedBlocks()
                    + ", rewriteSections=" + result.rewriteSections()
                    + ", nativeSections=" + result.nativeSections()
                    + ", directSections=" + result.directSections()
                    + ", fallbackSections=" + (result.fallbackSections()
                            + result.nativeFallbackSections()
                            + result.rewriteFallbackSections())
                    + ", packets=" + result.sectionPackets()
                    + ", blockEntityPackets=" + result.blockEntityPackets()
                    + ", lightChecks=" + result.lightChecks()
                    + ", reason=" + result.fallbackReason();
        }

        @Override
        protected Optional<String> applyMetricsSummary() {
            this.applyMetrics.recordTotalDuration(Duration.between(this.handle().startedAt(), Instant.now()).toNanos());
            return Optional.of(this.applyMetrics.summary());
        }

        @Override
        protected void complete(String detail) {
            this.releasePreloadTickets();
            super.complete(detail);
        }

        @Override
        protected void fail(Exception exception) {
            this.releasePreloadTickets();
            super.fail(exception);
        }

        private void releasePreloadTickets() {
            if (this.chunkPreloader == null) {
                return;
            }
            this.chunkPreloader.release(new ServerLevelChunkPreloadAccess(this.level()));
        }

        private long microsSince(long startedAt) {
            return Math.max(0L, (System.nanoTime() - startedAt) / 1_000L);
        }

        private record AppliedWork(
                int workUnits,
                int nativeSections,
                int nativeCells,
                int rewriteSections,
                int directSections
        ) {

            private static AppliedWork none() {
                return new AppliedWork(0, 0, 0, 0, 0);
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
