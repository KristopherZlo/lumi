package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final int EXACT_REPLAY_GUARD_TICKS = 40;
    private static final int LIGHT_PUBLISH_TICKS = 2;
    private static final double MIN_ADAPTIVE_SCALE = 0.25D;
    private static final double MAX_ADAPTIVE_SCALE = 1.25D;
    private static final WorldOperationManager INSTANCE = new WorldOperationManager();
    private static final AtomicInteger NEXT_BACKGROUND_THREAD_INDEX = new AtomicInteger(1);

    private final WorldApplyOperationProfile applyOperationProfile = new WorldApplyOperationProfile();
    private final WorldApplyBudgetPlanner budgetPlanner = new WorldApplyBudgetPlanner();
    private final WorldApplyTickWorkGate tickWorkGate = new WorldApplyTickWorkGate();
    private final WorldApplyTickDiagnostics applyTickDiagnostics = new WorldApplyTickDiagnostics();
    private final WorldOperationTickRunner tickRunner = new WorldOperationTickRunner(
            this.budgetPlanner,
            this.applyOperationProfile
    );
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private ExecutorService backgroundExecutor = createExecutor();
    private final WorldOperationLifecycle lifecycle = new WorldOperationLifecycle();
    private final WorldOperationMetricsReporter metricsReporter = new WorldOperationMetricsReporter();
    private final WorldOperationShutdownHandler shutdownHandler = new WorldOperationShutdownHandler(
            this.lifecycle,
            this.budgetPlanner,
            this::complete
    );

    private WorldOperationManager() {
    }

    public static WorldOperationManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean hasActiveOperation(MinecraftServer server) {
        return this.lifecycle.hasActive(this.serverKey(server));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server) {
        return this.lifecycle.snapshot(this.serverKey(server));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server, String projectId) {
        return this.lifecycle.snapshot(this.serverKey(server), projectId);
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server, OperationHandle handle) {
        return this.lifecycle.snapshot(this.serverKey(server), handle);
    }

    public synchronized Optional<String> applyMetrics(OperationHandle handle) {
        return this.lifecycle.applyMetrics(handle);
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
            this.lifecycle.ensureIdle(serverKey);
            BackgroundActiveOperation operation = new BackgroundActiveOperation(
                    level,
                    new OperationHandle(UUID.randomUUID().toString(), projectId, label, Instant.now(), debugEnabled),
                    unitLabel,
                    work
            );
            this.lifecycle.start(serverKey, operation);
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
            LumaLoadLog.event("world-op", "queued-background", "label=" + label + ", projectId=" + projectId);
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
        return this.startPreparedApplyOperation(level, projectId, label, unitLabel, debugEnabled, work, false);
    }

    public OperationHandle startPreparedApplyOperation(
            ServerLevel level,
            String projectId,
            String label,
            String unitLabel,
            boolean debugEnabled,
            PreparedApplyWork work,
            boolean freezeWorldTicks
    ) {
        String serverKey = this.serverKey(level.getServer());
        synchronized (this) {
            this.lifecycle.ensureIdle(serverKey);
            ExactReplayStateGuard.getInstance().clear(level);
            PreparedApplyActiveOperation operation = new PreparedApplyActiveOperation(
                    level,
                    new OperationHandle(UUID.randomUUID().toString(), projectId, label, Instant.now(), debugEnabled),
                    unitLabel,
                    work,
                    freezeWorldTicks
            );
            this.lifecycle.start(serverKey, operation);
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
            LumaLoadLog.event("world-op", "queued-prepared-apply", "label=" + label + ", projectId=" + projectId);
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
        WorldOperationSafetyBoundary.run(
                "exact-replay-guard",
                "server-tick",
                () -> ExactReplayStateGuard.getInstance().tick(server)
        );

        ActiveOperation operation;
        synchronized (this) {
            operation = this.lifecycle.active(this.serverKey(server));
        }
        if (operation == null) {
            return;
        }

        this.tickRunner.advance(server, operation, this::complete);
    }

    public void shutdown() {
        LumaMod.LOGGER.info("Shutting down world operation executor");
        this.backgroundExecutor.shutdownNow();
    }

    public void shutdown(MinecraftServer server) {
        if (server != null) {
            this.shutdownHandler.finishServerOperationBeforeShutdown(this.serverKey(server), server);
        }
        this.shutdown();
    }

    private synchronized void complete(MinecraftServer server, ActiveOperation operation) {
        String serverKey = this.serverKey(server);
        WorldOperationLifecycle.Completion completion = this.lifecycle.complete(serverKey, operation);
        if (completion.completed()) {
            completion.metrics().ifPresent(metrics -> LumaLoadLog.operationMetrics(operation.handle(), metrics));
            ActiveOperation followUp = completion.followUp();
            if (followUp != null) {
                LumaMod.LOGGER.info(
                        "Queued follow-up world operation {} for project {}",
                        followUp.handle().label(),
                        followUp.handle().projectId()
                );
                LumaDebugLog.log(
                        followUp.handle(),
                        "world-op",
                        "Queued follow-up world operation {} after {}",
                        followUp.handle().label(),
                        operation.handle().label()
                );
                LumaLoadLog.event(
                        "world-op",
                        "queued-follow-up",
                        "label=" + followUp.handle().label()
                                + ", after=" + operation.handle().label()
                                + ", projectId=" + followUp.handle().projectId()
                );
            }
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
        return Executors.newSingleThreadExecutor(WorldOperationManager::backgroundThread);
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

    abstract static class ActiveOperation {

        private final ServerLevel level;
        private final OperationHandle handle;
        private final String unitLabel;
        private volatile OperationSnapshot snapshot;
        private volatile OperationStage lastLoggedStage;
        private volatile int lastLoggedPercent = -1;
        protected final WorldApplyPerformanceGovernor performanceGovernor = new WorldApplyPerformanceGovernor();
        private final boolean freezeWorldTicks;
        private boolean worldTickFreezeReleased;

        ActiveOperation(ServerLevel level, OperationHandle handle, String unitLabel) {
            this(level, handle, unitLabel, false);
        }

        ActiveOperation(ServerLevel level, OperationHandle handle, String unitLabel, boolean freezeWorldTicks) {
            this.level = level;
            this.handle = handle;
            this.unitLabel = unitLabel == null || unitLabel.isBlank() ? "items" : unitLabel;
            this.freezeWorldTicks = freezeWorldTicks;
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
            if (freezeWorldTicks) {
                WorldReplayTickSuppression.getInstance().freezeWorldTick(level);
            }
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
            return this.performanceGovernor.adaptiveScale();
        }

        protected WorldApplyBudget planBudget(
                WorldApplyBudgetPlanner planner,
                double progressFraction,
                WorldApplyProfile profile
        ) {
            return this.performanceGovernor.planBudget(
                    planner,
                    progressFraction,
                    profile,
                    this.minimumAdaptiveScale(),
                    this.maximumAdaptiveScale()
            );
        }

        protected void recordAdvanceCost(long elapsedNanos, long budgetNanos) {
            this.performanceGovernor.recordTick(
                    elapsedNanos,
                    budgetNanos,
                    this.minimumAdaptiveScale(),
                    this.maximumAdaptiveScale()
            );
        }

        protected double minimumAdaptiveScale() {
            return MIN_ADAPTIVE_SCALE;
        }

        protected double maximumAdaptiveScale() {
            return MAX_ADAPTIVE_SCALE;
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
            this.releaseWorldTickFreeze();
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
            this.releaseWorldTickFreeze();
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

        protected ActiveOperation followUpOperation() {
            return null;
        }

        protected boolean drainBeforeShutdown() {
            return false;
        }

        private void releaseWorldTickFreeze() {
            if (!this.freezeWorldTicks || this.worldTickFreezeReleased) {
                return;
            }
            this.worldTickFreezeReleased = true;
            WorldReplayTickSuppression.getInstance().releaseWorldTickFreeze(this.level);
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
                long loadStartedAt = LumaLoadLog.start();
                try {
                    LumaDebugLog.log(this.handle(), "world-op", "Background worker thread started for {}", this.handle().label());
                    this.progressSink().update(OperationStage.PREPARING, 0, 0, "Starting");
                    work.run(this.progressSink());
                    this.complete("Completed");
                } catch (Exception exception) {
                    LumaLoadLog.recordSince("world-op", this.handle().label() + ".background", loadStartedAt, "failed=true");
                    throw new CompletionException(exception);
                }
                LumaLoadLog.recordSince("world-op", this.handle().label() + ".background", loadStartedAt);
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
        private final RedstoneReplayUpdateQueue redstoneUpdateQueue = new RedstoneReplayUpdateQueue();
        private final ExactReplayStateQueue exactReplayStateQueue = new ExactReplayStateQueue();
        private final WorldApplyNoOpPruner noOpPruner = new WorldApplyNoOpPruner();
        private final WorldApplyVerificationService verificationService = new WorldApplyVerificationService();
        private final WorldApplyVerificationRepairer verificationRepairer = new WorldApplyVerificationRepairer();
        private final WorldApplyFinalVerificationGate finalVerificationGate = new WorldApplyFinalVerificationGate();
        private WorldApplyVerificationResult currentVerificationResult;
        private int currentVerificationRepaired;

        private PreparedApplyActiveOperation(
                ServerLevel level,
                OperationHandle handle,
                String unitLabel,
                PreparedApplyWork work,
                boolean freezeWorldTicks
        ) {
            super(level, handle, unitLabel, freezeWorldTicks);
            this.profile = WorldOperationManager.this.applyOperationProfile.profileFor(handle.label());
            this.preparationStartedAtNanos = System.nanoTime();
            this.future = CompletableFuture.supplyAsync(() -> {
                long loadStartedAt = LumaLoadLog.start();
                try {
                    this.progressSink().update(OperationStage.PREPARING, 0, 0, "Preparing");
                    PreparedApplyOperation preparedOperation = work.prepare(this.progressSink());
                    LumaLoadLog.recordSince(
                            "world-op",
                            this.handle().label() + ".prepare",
                            loadStartedAt,
                            "workUnits=" + (preparedOperation == null ? 0 : preparedOperation.totalWorkUnits())
                    );
                    return preparedOperation;
                } catch (Exception exception) {
                    LumaLoadLog.recordSince("world-op", this.handle().label() + ".prepare", loadStartedAt, "failed=true");
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
                String preparationDetail = this.snapshot().detail();
                this.preparationMarkerDetail = preparationDetail != null
                        && (preparationDetail.startsWith("Decoded initial snapshot")
                        || preparationDetail.startsWith("Decoded exact initial snapshot"))
                        ? preparationDetail
                        : "";
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
                if (this.blockApplyDiagnosticsEnabled()) {
                    LumaDiagnosticsLog.blockApplyEvent(
                            "prepared",
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", projectId=" + this.handle().projectId()
                                    + ", profile=" + this.profile
                                    + ", totalWorkUnits=" + this.prepared.totalWorkUnits()
                                    + ", readyChunkBatches=" + this.prepared.localQueue().completedCount()
                                    + ", preloadChunks=" + this.chunkPreloader.totalChunks()
                    );
                }
                if (this.prepared.totalWorkUnits() == 0) {
                    return this.advanceCompletion();
                }
            }

            if (this.chunkPreloader != null
                    && this.chunkPreloader.required()
                    && !this.chunkPreloader.complete()
                    && !this.advancePreload(budget, deadlineNanos)) {
                return false;
            }

            if (this.dispatcher == null) {
                this.startApply();
            }

            WorldApplyTickDiagnostics.TickCounters tickCounters =
                    WorldOperationManager.this.applyTickDiagnostics.startTick(this.applyMetrics);
            long applyTickStartedAt = System.nanoTime();
            String stopReason = "deadline";
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Apply tick start label={} progress={}/{} adaptiveScale={} budget=[{}] currentBatch={} dispatcherPending={} lightPending={} redstonePending={}",
                        this.handle().label(),
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.adaptiveScale(),
                        budget.summary(),
                        WorldOperationManager.this.applyTickDiagnostics.chunkId(this.currentBatch),
                        this.dispatcher != null && this.dispatcher.hasPending(),
                        this.lightUpdateQueue.pendingCount(),
                        this.redstoneUpdateQueue.pendingCount()
                );
            }
            while (System.nanoTime() < deadlineNanos) {
                if (this.currentBatch == null) {
                    this.currentBatch = this.dispatcher.pollNext();
                    if (this.currentBatch == null) {
                        stopReason = "dispatcher-empty";
                        break;
                    }
                    this.currentBatch = this.pruneNoOpBatch(this.currentBatch);
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
                    WorldApplyPerformanceGovernor.ChunkStartDecision chunkStartDecision =
                            this.performanceGovernor.evaluateChunkStart(
                                    this.currentBatch,
                                    budget,
                                    System.nanoTime() - applyTickStartedAt,
                                    tickCounters.workUnits()
                            );
                    if (!chunkStartDecision.allowed()) {
                        stopReason = "chunk-cost-" + chunkStartDecision.reason();
                        this.logChunkCostDefer(chunkStartDecision);
                        break;
                    }
                    tickCounters.recordChunkStarted();
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Chunk batch start {}:{} placements={} nativeSections={} rewriteSections={} nativeCells={} rewriteCells={} sparseSections={} sparsePlacements={} blockEntities={} entityOps={}",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                this.currentBatch.totalPlacements(),
                                this.currentNativeSections.size(),
                                WorldOperationManager.this.applyTickDiagnostics.rewriteSectionCount(this.currentBatch),
                                WorldOperationManager.this.applyTickDiagnostics.nativeCellCount(this.currentBatch),
                                WorldOperationManager.this.applyTickDiagnostics.rewriteCellCount(this.currentBatch),
                                this.currentSections.size(),
                                WorldOperationManager.this.applyTickDiagnostics.sparsePlacementCount(this.currentBatch),
                                this.currentBlockEntities.size(),
                                BlockChangeApplier.entityOperationCount(this.currentBatch.entityBatch())
                        );
                    }
                    this.logBlockApplyChunkStart(this.currentBatch);
                    WorldOperationManager.this.historyDebugLog.logReplayBatch(this.handle(), this.currentBatch);
                }

                WorldApplyTickGateDecision decision = WorldOperationManager.this.tickWorkGate.decide(
                        this.hasPendingNativeSection(),
                        this.hasPendingNativeSection() ? this.pendingNativeSection().safetyProfile().path() : null,
                        tickCounters.workUnits(),
                        tickCounters.nativeSections(),
                        tickCounters.nativeCells(),
                        tickCounters.rewriteSections(),
                        tickCounters.directSections(),
                        budget,
                        this.profile
                );
                if (!decision.canStart()) {
                    stopReason = decision.reason();
                    break;
                }

                AppliedWork processed;
                try (
                        WorldMutationContext.SourceFrame ignoredSource =
                                WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                        WorldMutationContext.SuppressionFrame ignoredSuppression =
                                WorldMutationContext.pushCaptureSuppression()
                ) {
                    WorldRedstoneReplayUpdateContext.push(this.redstoneUpdateQueue);
                    WorldLightUpdateContext.push(this.lightUpdateQueue);
                    boolean allowSynchronousChunkLoad = this.allowsSynchronousChunkLoad();
                    if (allowSynchronousChunkLoad) {
                        WorldApplyChunkLoadContext.pushAllowSynchronousLoad();
                    }
                    int maxBlocks = this.maxWorkForCurrentStep(budget, tickCounters.workUnits(), tickCounters.nativeCells());
                    int maxDirectSections = Math.max(0, budget.maxDirectSections() - tickCounters.directSections());
                    try {
                        LumiTestFailpoints.hit(LumiTestFailpoints.MID_WORLD_OPERATION_APPLY);
                        processed = this.applyCurrentChunk(
                                maxBlocks,
                                maxDirectSections,
                                budget.maxBlockEntities(),
                                budget.maxEntityOperations()
                        );
                    } finally {
                        if (allowSynchronousChunkLoad) {
                            WorldApplyChunkLoadContext.pop();
                        }
                        WorldLightUpdateContext.pop();
                        WorldRedstoneReplayUpdateContext.pop();
                    }
                }

                if (processed.workUnits() <= 0 && this.currentBatch != null && !this.currentBatchFinished()) {
                    stopReason = "no-progress";
                    break;
                }

                this.appliedWorkUnits += processed.workUnits();
                this.performanceGovernor.recordWork(
                        processed.kind(),
                        processed.costUnits(),
                        processed.elapsedNanos()
                );
                tickCounters.recordWork(
                        processed.workUnits(),
                        processed.nativeSections(),
                        processed.nativeCells(),
                        processed.rewriteSections(),
                        processed.directSections()
                );

                this.progressSink().update(
                        OperationStage.APPLYING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.applyDetail(this.currentBatch == null
                                ? "Applying queued chunks"
                                : "Applying chunk " + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z())
                );
                if (this.currentBatch != null && this.currentBatchFinished()) {
                    WorldApplyVerificationResult verificationResult = this.verifyAndRepairBatch(
                            this.currentBatch,
                            budget,
                            deadlineNanos
                    );
                    if (verificationResult == null) {
                        stopReason = "verification-repair-pending";
                        break;
                    }
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Chunk batch finish {}:{} totalApplied={} verification=[{}] metrics=[{}]",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                this.appliedWorkUnits,
                                verificationResult.summary(),
                                this.applyMetrics.summary()
                        );
                    }
                    tickCounters.recordChunkFinished();
                    this.exactReplayStateQueue.record(this.currentBatch);
                    this.finalVerificationGate.record(this.currentBatch);
                    this.logBlockApplyChunkFinish(this.currentBatch);
                    this.currentBatch = null;
                    this.currentNativeSections = List.of();
                    this.currentSections = List.of();
                    this.currentBlockEntities = List.of();
                    this.nativeSectionCursor = null;
                    this.clearCurrentVerification();
                }
            }
            if (System.nanoTime() >= deadlineNanos && !"dispatcher-empty".equals(stopReason)) {
                stopReason = "time-budget";
            }
            long applyTickElapsedNanos = System.nanoTime() - applyTickStartedAt;
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Apply tick {}",
                        tickCounters.tickDetail(
                                stopReason,
                                this.applyMetrics,
                                WorldOperationManager.this.applyTickDiagnostics.chunkId(this.currentBatch),
                                this.dispatcher != null && this.dispatcher.hasPending(),
                                this.lightUpdateQueue.pendingCount(),
                                this.redstoneUpdateQueue.pendingCount()
                        )
                );
            }
            this.applyMetrics.recordApplyTick(tickCounters.workUnits(), applyTickElapsedNanos);
            if (this.blockApplyDiagnosticsEnabled()) {
                LumaDiagnosticsLog.blockApplySpan(
                        "apply-tick",
                        applyTickElapsedNanos,
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", "
                                + tickCounters.tickDetail(
                                        stopReason,
                                        this.applyMetrics,
                                        WorldOperationManager.this.applyTickDiagnostics.chunkId(this.currentBatch),
                                        this.dispatcher != null && this.dispatcher.hasPending(),
                                        this.lightUpdateQueue.pendingCount(),
                                        this.redstoneUpdateQueue.pendingCount()
                                )
                );
            }
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".applyTick",
                    applyTickElapsedNanos,
                    "workUnits=" + tickCounters.workUnits()
                            + ", stop=" + stopReason
                            + ", nativeCells=" + tickCounters.nativeCells()
                            + ", rewriteSections=" + tickCounters.rewriteSections()
                            + ", directSections=" + tickCounters.directSections()
            );

            if (this.currentBatch == null && (this.dispatcher == null || !this.dispatcher.hasPending())) {
                if (!this.drainDeferredRedstoneUpdates(budget, deadlineNanos)) {
                    return false;
                }
                if (!this.drainExactReplayStates(budget, deadlineNanos)) {
                    return false;
                }
                if (this.shouldVerifyPostApply() && !this.finalVerificationGate.advance(
                        this.level(),
                        budget,
                        deadlineNanos,
                        this.applyMetrics,
                        this.redstoneUpdateQueue,
                        this.lightUpdateQueue,
                        this.performanceGovernor
                )) {
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
            this.applyMetrics.recordPreloadPipeline(
                    result.ticketedChunks(),
                    result.outstandingTickets(),
                    result.syncFallbackLoads()
            );
            this.performanceGovernor.recordWork(ApplyWorkKind.PRELOAD_SYNC, result.syncFallbackLoads(), elapsedNanos);
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
                        "Preload tick chunks={}/{} newlyLoaded={} alreadyLoaded={} ticketed={} outstandingTickets={} syncFallbackLoads={} elapsedMicros={} complete={}",
                        result.completedChunks(),
                        result.totalChunks(),
                        result.newlyLoadedChunks(),
                        result.alreadyLoadedChunks(),
                        result.ticketedChunks(),
                        result.outstandingTickets(),
                        result.syncFallbackLoads(),
                        elapsedNanos / 1_000L,
                        result.complete()
                );
            }
            if (this.blockApplyDiagnosticsEnabled()) {
                LumaDiagnosticsLog.blockApplySpan(
                        "preload-tick",
                        elapsedNanos,
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", chunks=" + result.completedChunks() + "/" + result.totalChunks()
                                + ", newlyLoaded=" + result.newlyLoadedChunks()
                                + ", alreadyLoaded=" + result.alreadyLoadedChunks()
                                + ", ticketed=" + result.ticketedChunks()
                                + ", outstandingTickets=" + result.outstandingTickets()
                                + ", syncFallbackLoads=" + result.syncFallbackLoads()
                                + ", complete=" + result.complete()
                );
            }
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".preloadTick",
                    elapsedNanos,
                    "chunks=" + result.completedChunks() + "/" + result.totalChunks()
                            + ", newlyLoaded=" + result.newlyLoadedChunks()
                            + ", alreadyLoaded=" + result.alreadyLoadedChunks()
                            + ", ticketed=" + result.ticketedChunks()
                            + ", outstandingTickets=" + result.outstandingTickets()
                            + ", syncFallbackLoads=" + result.syncFallbackLoads()
            );
            return result.complete();
        }

        private void startApply() {
            this.dispatcher = new GlobalDispatcher();
            this.dispatcher.enqueue(this.prepared.localQueue());
            if (this.blockApplyDiagnosticsEnabled()) {
                LumaDiagnosticsLog.blockApplyEvent(
                        "apply-start",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", profile=" + this.profile
                                + ", totalWorkUnits=" + this.prepared.totalWorkUnits()
                );
            }
            this.progressSink().update(
                    OperationStage.APPLYING,
                    0,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Applying prepared batches")
            );
        }

        private ChunkBatch pruneNoOpBatch(ChunkBatch batch) {
            if (batch == null || this.profile == WorldApplyProfile.NORMAL) {
                return batch;
            }
            int before = batch.totalPlacements();
            ChunkBatch pruned = this.noOpPruner.prune(this.level(), batch);
            int after = pruned == null ? 0 : pruned.totalPlacements();
            if (before > after && this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "No-op pruned chunk {}:{} placements {} -> {}",
                        batch.chunk().x(),
                        batch.chunk().z(),
                        before,
                        after
                );
            }
            return pruned;
        }

        private boolean drainExactReplayStates(WorldApplyBudget budget, long deadlineNanos) {
            if (!this.exactReplayStateQueue.hasPending()) {
                this.guardRecordedExactReplayStates();
                return true;
            }

            int maxBlocks = Math.max(32, budget.maxBlocks());
            int pendingBefore = this.exactReplayStateQueue.pendingCount();
            long startedAt = System.nanoTime();
            int reapplied;
            try (
                    WorldMutationContext.SourceFrame ignoredSource =
                            WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                    WorldMutationContext.SuppressionFrame ignoredSuppression =
                            WorldMutationContext.pushCaptureSuppression()
            ) {
                reapplied = this.exactReplayStateQueue.drain(this.level(), maxBlocks, deadlineNanos, this.handle());
            }
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".exactReplayDrainTick",
                    System.nanoTime() - startedAt,
                    "reapplied=" + reapplied + ", pendingBefore=" + pendingBefore
            );
            if (!this.exactReplayStateQueue.hasPending()) {
                this.guardRecordedExactReplayStates();
            }
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.appliedWorkUnits,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Reasserting exact states, "
                            + this.exactReplayStateQueue.pendingCount()
                            + " blocks queued")
            );
            return !this.exactReplayStateQueue.hasPending();
        }

        private void guardRecordedExactReplayStates() {
            if (!this.exactReplayStateQueue.hasRecordedPlacements()) {
                return;
            }
            ExactReplayStateGuard.getInstance().guard(
                    this.level(),
                    this.exactReplayStateQueue.takeRecordedPlacements(),
                    EXACT_REPLAY_GUARD_TICKS
            );
        }

        private boolean drainDeferredRedstoneUpdates(WorldApplyBudget budget, long deadlineNanos) {
            if (!this.redstoneUpdateQueue.hasPending()) {
                return true;
            }

            int maxUpdates = Math.max(32, budget.maxRedstoneUpdates());
            int pendingBefore = this.redstoneUpdateQueue.pendingCount();
            long startedAt = System.nanoTime();
            int appliedUpdates;
            try (
                    WorldMutationContext.SourceFrame ignoredSource =
                            WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                    WorldMutationContext.SuppressionFrame ignoredSuppression =
                            WorldMutationContext.pushCaptureSuppression()
            ) {
                appliedUpdates = this.redstoneUpdateQueue.drain(this.level(), maxUpdates, deadlineNanos);
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            this.applyMetrics.recordRedstoneUpdates(appliedUpdates);
            this.applyMetrics.recordRedstoneDrainTick(elapsedNanos);
            this.performanceGovernor.recordWork(ApplyWorkKind.REDSTONE_DRAIN, appliedUpdates, elapsedNanos);
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".redstoneDrainTick",
                    elapsedNanos,
                    "appliedUpdates=" + appliedUpdates + ", pendingBefore=" + pendingBefore
            );
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Redstone drain maxUpdates={} applied={} pendingBefore={} pendingAfter={} elapsedMicros={}",
                        maxUpdates,
                        appliedUpdates,
                        pendingBefore,
                        this.redstoneUpdateQueue.pendingCount(),
                        elapsedNanos / 1_000L
                );
            }
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.appliedWorkUnits,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Updating redstone, " + this.redstoneUpdateQueue.pendingCount() + " updates queued")
            );
            return !this.redstoneUpdateQueue.hasPending();
        }

        private WorldApplyVerificationResult verifyAndRepairBatch(
                ChunkBatch batch,
                WorldApplyBudget budget,
                long deadlineNanos
        ) {
            if (!this.shouldVerifyPostApply() || batch == null || batch.totalPlacements() <= 0) {
                return WorldApplyVerificationResult.empty();
            }

            long startedAt = System.nanoTime();
            if (this.currentVerificationResult == null) {
                this.currentVerificationResult = this.verificationService.verify(this.level(), batch);
                this.currentVerificationRepaired = 0;
                if (this.currentVerificationResult.hasRepairs()) {
                    this.verificationRepairer.start(this.currentVerificationResult.repairSections());
                }
            }

            if (this.verificationRepairer.hasPending()) {
                long repairStartedAt = System.nanoTime();
                int repaired = this.verificationRepairer.drain(
                        this.level(),
                        budget,
                        deadlineNanos,
                        this.applyMetrics,
                        this.redstoneUpdateQueue,
                        this.lightUpdateQueue
                );
                long repairElapsedNanos = System.nanoTime() - repairStartedAt;
                this.performanceGovernor.recordWork(ApplyWorkKind.SPARSE_DIRECT, repaired, repairElapsedNanos);
                this.currentVerificationRepaired += repaired;
                if (this.verificationRepairer.hasPending()) {
                    this.logBlockApplyVerificationPending(batch, repaired, repairElapsedNanos);
                    return null;
                }
            }

            WorldApplyVerificationResult result = this.currentVerificationResult.withRepairOutcome(
                    this.currentVerificationRepaired,
                    this.currentVerificationResult.mismatched() - this.currentVerificationRepaired
            );
            this.applyMetrics.recordVerification(result);
            this.logBlockApplyVerification(batch, result, System.nanoTime() - startedAt);
            return result;
        }

        private void clearCurrentVerification() {
            this.currentVerificationResult = null;
            this.currentVerificationRepaired = 0;
            this.verificationRepairer.clear();
        }

        private boolean shouldVerifyPostApply() {
            return WorldOperationManager.this.applyOperationProfile.requiresPostApplyVerification(this.handle().label());
        }

        private String applyDetail(String detail) {
            return WorldOperationManager.this.applyTickDiagnostics.applyDetail(this.preparationMarkerDetail, detail);
        }

        private void logBlockApplyChunkStart(ChunkBatch batch) {
            if (!this.blockApplyDiagnosticsEnabled() || batch == null) {
                return;
            }
            WorldApplyTickDiagnostics.ChunkShape shape =
                    WorldOperationManager.this.applyTickDiagnostics.chunkShape(batch);
            LumaDiagnosticsLog.blockApplyEvent(
                    "chunk-start",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", chunk=" + batch.chunk().x() + ":" + batch.chunk().z()
                            + ", placements=" + batch.totalPlacements()
                            + ", setTargets=" + shape.setTargets()
                            + ", deleteTargets=" + shape.deleteTargets()
                            + ", sparseTargets=" + shape.sparseTargets()
                            + ", nativeTargets=" + shape.nativeTargets()
                            + ", rewriteTargets=" + shape.rewriteTargets()
                            + ", nativeSections=" + this.currentNativeSections.size()
                            + ", rewriteSections="
                            + WorldOperationManager.this.applyTickDiagnostics.rewriteSectionCount(batch)
                            + ", directSections=" + this.currentSections.size()
                            + ", blockEntities=" + this.currentBlockEntities.size()
                            + ", entityOps=" + BlockChangeApplier.entityOperationCount(batch.entityBatch())
            );
        }

        private void logChunkCostDefer(WorldApplyPerformanceGovernor.ChunkStartDecision decision) {
            if (decision == null || this.currentBatch == null) {
                return;
            }
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Deferred chunk {}:{} reason={} predictedMicros={} tickPressure={} adaptiveScale={}",
                        this.currentBatch.chunk().x(),
                        this.currentBatch.chunk().z(),
                        decision.reason(),
                        decision.predictedNanos() / 1_000L,
                        decision.tickPressure(),
                        this.adaptiveScale()
                );
            }
            if (this.blockApplyDiagnosticsEnabled()) {
                LumaDiagnosticsLog.blockApplyEvent(
                        "chunk-defer",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", chunk=" + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                                + ", deferReason=" + decision.reason()
                                + ", predictedCostMs=" + (decision.predictedNanos() / 1_000_000L)
                                + ", tickPressure=" + decision.tickPressure()
                                + ", adaptiveScale=" + this.adaptiveScale()
                );
            }
        }

        private void logBlockApplyChunkFinish(ChunkBatch batch) {
            if (!this.blockApplyDiagnosticsEnabled() || batch == null) {
                return;
            }
            LumaDiagnosticsLog.blockApplyEvent(
                    "chunk-finish",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", chunk=" + batch.chunk().x() + ":" + batch.chunk().z()
                            + ", totalApplied=" + this.appliedWorkUnits
                            + ", metrics=" + this.applyMetrics.summary()
            );
        }

        private void logBlockApplyVerification(
                ChunkBatch batch,
                WorldApplyVerificationResult result,
                long elapsedNanos
        ) {
            if (!this.blockApplyDiagnosticsEnabled() || batch == null || result == null) {
                return;
            }
            LumaDiagnosticsLog.blockApplySpan(
                    "verify",
                    elapsedNanos,
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", chunk=" + batch.chunk().x() + ":" + batch.chunk().z()
                            + ", matched=" + result.matched()
                            + ", mismatched=" + result.mismatched()
                            + ", repaired=" + result.repaired()
                            + ", skipped=" + result.skipped()
            );
        }

        private void logBlockApplyVerificationPending(
                ChunkBatch batch,
                int repairedThisTick,
                long elapsedNanos
        ) {
            if (!this.blockApplyDiagnosticsEnabled() || batch == null) {
                return;
            }
            LumaDiagnosticsLog.blockApplySpan(
                    "verify-repair",
                    elapsedNanos,
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", chunk=" + batch.chunk().x() + ":" + batch.chunk().z()
                            + ", repairedThisTick=" + repairedThisTick
                            + ", repairedTotal=" + this.currentVerificationRepaired
                            + ", pending=" + this.verificationRepairer.pendingCount()
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

        private boolean allowsSynchronousChunkLoad() {
            return this.profile != WorldApplyProfile.NORMAL
                    && this.chunkPreloader != null
                    && this.chunkPreloader.complete();
        }

        private boolean advanceCompletion() throws Exception {
            if (this.prepared.completeOnServerThread()) {
                this.progressSink().update(
                        OperationStage.FINALIZING,
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        "Finalizing"
                );
                this.prepared.onComplete().run();
                this.complete("Completed");
                return true;
            }
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

        private AppliedWork applyCurrentChunk(
                int maxBlocks,
                int maxDirectSections,
                int maxBlockEntities,
                int maxEntityOperations
        ) {
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
                long elapsedNanos = System.nanoTime() - startedAt;
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
                            elapsedNanos / 1_000L,
                            WorldOperationManager.this.applyTickDiagnostics.commitSummary(result.commitResult())
                    );
                }
                if (this.blockApplyDiagnosticsEnabled()) {
                    LumaDiagnosticsLog.blockApplySpan(
                            "native-section-step",
                            elapsedNanos,
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", chunk=" + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                                    + ", sectionY=" + nativeSection.sectionY()
                                    + ", path=" + nativeSection.safetyProfile().path()
                                    + ", cells=" + nativeSection.changedCellCount()
                                    + ", maxBlocks=" + maxBlocks
                                    + ", processed=" + result.processedCells()
                                    + ", completed=" + result.completedSection()
                                    + ", commit=["
                                    + WorldOperationManager.this.applyTickDiagnostics.commitSummary(result.commitResult())
                                    + "]"
                    );
                }
                return new AppliedWork(
                        result.processedCells(),
                        completedNativeSections,
                        nativeCells,
                        completedRewriteSections,
                        0,
                        nativeSection.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE
                                ? ApplyWorkKind.SECTION_REWRITE
                                : ApplyWorkKind.SECTION_NATIVE,
                        nativeSection.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE
                                ? completedRewriteSections
                                : result.processedCells(),
                        elapsedNanos
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
                long elapsedNanos = System.nanoTime() - startedAt;
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
                            elapsedNanos / 1_000L,
                            WorldOperationManager.this.applyTickDiagnostics.commitSummary(result.commitResult())
                    );
                }
                if (this.blockApplyDiagnosticsEnabled()) {
                    LumaDiagnosticsLog.blockApplySpan(
                            "sparse-chunk-step",
                            elapsedNanos,
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", chunk=" + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                                    + ", sectionIndex=" + previousSectionIndex + "->" + this.sectionIndex
                                    + ", maxBlocks=" + maxBlocks
                                    + ", maxDirectSections=" + maxDirectSections
                                    + ", processed=" + result.processedBlocks()
                                    + ", nextPlacement=" + this.placementIndex
                                    + ", completed=" + (this.sectionIndex >= this.currentSections.size())
                                    + ", commit=["
                                    + WorldOperationManager.this.applyTickDiagnostics.commitSummary(result.commitResult())
                                    + "]"
                    );
                }
                return new AppliedWork(
                        result.processedBlocks(),
                        0,
                        0,
                        0,
                        result.commitResult().directSections(),
                        ApplyWorkKind.SPARSE_DIRECT,
                        result.processedBlocks(),
                        elapsedNanos
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
                            Math.min(maxBlocks, maxBlockEntities),
                            this.applyMetrics
                    );
                    this.blockEntityIndex += processed;
                    if (this.blockEntityIndex >= this.currentBlockEntities.size()) {
                        this.blockEntitiesApplied = true;
                    }
                    long elapsedNanos = System.nanoTime() - startedAt;
                    if (this.debugApplyEnabled()) {
                        LumaDebugLog.log(
                                this.handle(),
                                "world-op-apply",
                                "Block-entity step chunk={}:{} max={} processed={} nextIndex={} total={} completed={} elapsedMicros={}",
                                this.currentBatch.chunk().x(),
                                this.currentBatch.chunk().z(),
                                Math.min(maxBlocks, maxBlockEntities),
                                processed,
                                this.blockEntityIndex,
                                this.currentBlockEntities.size(),
                                this.blockEntitiesApplied,
                                elapsedNanos / 1_000L
                        );
                    }
                    if (this.blockApplyDiagnosticsEnabled()) {
                        LumaDiagnosticsLog.blockApplySpan(
                                "block-entity-step",
                                elapsedNanos,
                                "label=" + this.handle().label()
                                        + ", operationId=" + this.handle().id()
                                        + ", chunk=" + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                                        + ", max=" + Math.min(maxBlocks, maxBlockEntities)
                                        + ", processed=" + processed
                                        + ", nextIndex=" + this.blockEntityIndex
                                        + ", total=" + this.currentBlockEntities.size()
                                        + ", completed=" + this.blockEntitiesApplied
                        );
                    }
                    return new AppliedWork(processed, 0, 0, 0, 0, ApplyWorkKind.BLOCK_ENTITY, processed, elapsedNanos);
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
                        this.currentBatch.chunk(),
                        this.currentBatch.entityBatch(),
                        this.entityIndex,
                        Math.min(maxBlocks, maxEntityOperations),
                        this.applyMetrics
                );
                this.entityIndex += processed;
                if (this.entityIndex >= entityOperationCount) {
                    this.entitiesApplied = true;
                }
                long elapsedNanos = System.nanoTime() - startedAt;
                if (this.debugApplyEnabled()) {
                    LumaDebugLog.log(
                            this.handle(),
                            "world-op-apply",
                            "Entity step chunk={}:{} max={} processed={} nextIndex={} total={} completed={} elapsedMicros={}",
                            this.currentBatch.chunk().x(),
                            this.currentBatch.chunk().z(),
                            Math.min(maxBlocks, maxEntityOperations),
                            processed,
                            this.entityIndex,
                            entityOperationCount,
                            this.entitiesApplied,
                            elapsedNanos / 1_000L
                    );
                }
                if (this.blockApplyDiagnosticsEnabled()) {
                    LumaDiagnosticsLog.blockApplySpan(
                            "entity-step",
                            elapsedNanos,
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", chunk=" + this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z()
                                    + ", max=" + Math.min(maxBlocks, maxEntityOperations)
                                    + ", processed=" + processed
                                    + ", nextIndex=" + this.entityIndex
                                    + ", total=" + entityOperationCount
                                    + ", completed=" + this.entitiesApplied
                    );
                }
                return new AppliedWork(processed, 0, 0, 0, 0, ApplyWorkKind.ENTITY, processed, elapsedNanos);
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

        private boolean blockApplyDiagnosticsEnabled() {
            return LumaDiagnosticsLog.blockApplyEnabled() && this.profile != WorldApplyProfile.NORMAL;
        }

        @Override
        protected Optional<String> applyMetricsSummary() {
            return Optional.of(WorldOperationManager.this.metricsReporter.summary(this.handle(), this.applyMetrics));
        }

        @Override
        protected ActiveOperation followUpOperation() {
            if (this.snapshot().stage() != OperationStage.COMPLETED || !this.lightUpdateQueue.hasPending()) {
                return null;
            }
            LumaDiagnosticsLog.lightEvent(
                    "scheduled",
                    "parentLabel=" + this.handle().label()
                            + ", parentOperationId=" + this.handle().id()
                            + ", pendingChecks=" + this.lightUpdateQueue.pendingCount()
                            + ", projectId=" + this.handle().projectId()
            );
            return new LightRefreshActiveOperation(
                    this.level(),
                    new OperationHandle(
                            UUID.randomUUID().toString(),
                            this.handle().projectId(),
                            "light-refresh",
                            Instant.now(),
                            this.handle().debugEnabled()
                    ),
                    this.lightUpdateQueue,
                    this::releasePreloadTickets,
                    WorldOperationManager.this::executor,
                    WorldOperationManager.this.metricsReporter
            );
        }

        @Override
        protected void complete(String detail) {
            if (!this.lightUpdateQueue.hasPending()) {
                this.releasePreloadTickets();
            }
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
                int directSections,
                ApplyWorkKind kind,
                int costUnits,
                long elapsedNanos
        ) {

            private static AppliedWork none() {
                return new AppliedWork(0, 0, 0, 0, 0, ApplyWorkKind.UNKNOWN, 0, 0L);
            }
        }
    }

    private static Thread backgroundThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "Lumi-WorldOp-" + NEXT_BACKGROUND_THREAD_INDEX.getAndIncrement());
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return thread;
    }
}
