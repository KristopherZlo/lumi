package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.debug.LumiTestFailpoints;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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
    private static final Duration SERVER_STOP_LIGHT_REFRESH_GRACE = Duration.ofSeconds(2);
    private static final long SERVER_STOP_LIGHT_REFRESH_PAUSE_MILLIS = 10L;
    private static final double MIN_ADAPTIVE_SCALE = 0.25D;
    private static final double MAX_ADAPTIVE_SCALE = 1.25D;
    private static final WorldOperationManager INSTANCE = new WorldOperationManager();

    private final WorldApplyOperationProfile applyOperationProfile = new WorldApplyOperationProfile();
    private final WorldApplyBudgetPlanner budgetPlanner = new WorldApplyBudgetPlanner();
    private final WorldApplyTickWorkGate tickWorkGate = new WorldApplyTickWorkGate();
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private ExecutorService backgroundExecutor = createExecutor();
    private final WorldOperationRegistry operationRegistry = new WorldOperationRegistry();

    private WorldOperationManager() {
    }

    public static WorldOperationManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean hasActiveOperation(MinecraftServer server) {
        return this.operationRegistry.hasActive(this.serverKey(server));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server) {
        return this.operationRegistry.snapshot(this.serverKey(server));
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server, String projectId) {
        return this.operationRegistry.snapshot(this.serverKey(server), projectId);
    }

    public synchronized Optional<OperationSnapshot> snapshot(MinecraftServer server, OperationHandle handle) {
        return this.operationRegistry.snapshot(this.serverKey(server), handle);
    }

    public synchronized Optional<String> applyMetrics(OperationHandle handle) {
        return this.operationRegistry.applyMetrics(handle);
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
            this.operationRegistry.putActive(serverKey, operation);
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
        String serverKey = this.serverKey(level.getServer());
        synchronized (this) {
            this.ensureIdle(serverKey);
            ExactReplayStateGuard.getInstance().clear(level);
            PreparedApplyActiveOperation operation = new PreparedApplyActiveOperation(
                    level,
                    new OperationHandle(UUID.randomUUID().toString(), projectId, label, Instant.now(), debugEnabled),
                    unitLabel,
                    work
            );
            this.operationRegistry.putActive(serverKey, operation);
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
        ExactReplayStateGuard.getInstance().tick(server);

        ActiveOperation operation;
        synchronized (this) {
            operation = this.operationRegistry.active(this.serverKey(server));
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
            long elapsedNanos = System.nanoTime() - startedAt;
            operation.recordAdvanceCost(elapsedNanos, budget.maxNanos());
            LumaLoadLog.record(
                    "world-op-tick",
                    operation.handle().label() + ".advance",
                    elapsedNanos,
                    "stage=" + operation.snapshot().stage()
                            + ", budgetMicros=" + (budget.maxNanos() / 1_000L)
                            + ", adaptiveScale=" + operation.adaptiveScale()
            );
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

    public void shutdown(MinecraftServer server) {
        if (server != null) {
            this.finishServerOperationBeforeShutdown(server);
        }
        this.shutdown();
    }

    private synchronized void complete(MinecraftServer server, ActiveOperation operation) {
        String serverKey = this.serverKey(server);
        ActiveOperation active = this.operationRegistry.active(serverKey);
        if (active == operation) {
            this.operationRegistry.removeActive(serverKey);
            this.operationRegistry.remember(serverKey, operation)
                    .ifPresent(metrics -> LumaLoadLog.operationMetrics(operation.handle(), metrics));
            ActiveOperation followUp = operation.followUpOperation();
            if (followUp != null) {
                this.operationRegistry.putActive(serverKey, followUp);
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

    private void finishServerOperationBeforeShutdown(MinecraftServer server) {
        String serverKey = this.serverKey(server);
        ActiveOperation operation;
        synchronized (this) {
            operation = this.operationRegistry.active(serverKey);
        }
        if (operation == null) {
            return;
        }

        if (operation instanceof LightRefreshActiveOperation) {
            this.tryCompleteLightRefreshBeforeShutdown(server, operation);
        }

        synchronized (this) {
            ActiveOperation active = this.operationRegistry.removeActive(serverKey);
            if (active == null) {
                return;
            }
            if (!active.snapshot().terminal()) {
                active.fail(new IllegalStateException("Server stopped before world operation completed"));
            }
            this.operationRegistry.remember(serverKey, active)
                    .ifPresent(metrics -> LumaLoadLog.operationMetrics(active.handle(), metrics));
            LumaMod.LOGGER.warn(
                    "Cancelled active world operation {} for project {} during server shutdown",
                    active.handle().label(),
                    active.handle().projectId()
            );
            LumaLoadLog.event(
                    "world-op",
                    "cancelled-server-stop",
                    "label=" + active.handle().label()
                            + ", projectId=" + active.handle().projectId()
                            + ", stage=" + active.snapshot().stage()
            );
        }
    }

    private void tryCompleteLightRefreshBeforeShutdown(MinecraftServer server, ActiveOperation operation) {
        long deadlineNanos = System.nanoTime() + SERVER_STOP_LIGHT_REFRESH_GRACE.toNanos();
        WorldApplyBudget budget = this.budgetPlanner.plan(1.0D, MAX_ADAPTIVE_SCALE, WorldApplyProfile.MAXIMUM);
        LumaDiagnosticsLog.lightEvent(
                "server-stop-drain-start",
                "label=" + operation.handle().label()
                        + ", operationId=" + operation.handle().id()
                        + ", projectId=" + operation.handle().projectId()
        );
        while (System.nanoTime() < deadlineNanos) {
            synchronized (this) {
                if (this.operationRegistry.active(this.serverKey(server)) != operation) {
                    return;
                }
            }
            try {
                if (operation.advance(budget, deadlineNanos)) {
                    this.complete(server, operation);
                    LumaDiagnosticsLog.lightEvent(
                            "server-stop-drain-complete",
                            "label=" + operation.handle().label()
                                    + ", operationId=" + operation.handle().id()
                                    + ", projectId=" + operation.handle().projectId()
                    );
                    return;
                }
            } catch (Exception exception) {
                operation.fail(exception);
                this.complete(server, operation);
                LumaMod.LOGGER.warn(
                        "Light refresh operation {} failed during server shutdown drain",
                        operation.handle().id(),
                        exception
                );
                return;
            }
            if (!this.pauseServerStopLightDrain()) {
                break;
            }
        }
        LumaDiagnosticsLog.lightEvent(
                "server-stop-drain-timeout",
                "label=" + operation.handle().label()
                        + ", operationId=" + operation.handle().id()
                        + ", projectId=" + operation.handle().projectId()
                        + ", stage=" + operation.snapshot().stage()
        );
    }

    private boolean pauseServerStopLightDrain() {
        try {
            Thread.sleep(SERVER_STOP_LIGHT_REFRESH_PAUSE_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private WorldApplyBudget currentTickBudget(ActiveOperation operation) {
        double fraction = operation.snapshot().progress().fraction();
        WorldApplyProfile profile = this.applyProfile(operation);
        return operation.planBudget(this.budgetPlanner, fraction, profile);
    }

    private WorldApplyProfile applyProfile(ActiveOperation operation) {
        if (operation == null
                || operation.handle() == null) {
            return WorldApplyProfile.NORMAL;
        }
        return this.applyOperationProfile.profileFor(operation.handle().label());
    }

    private void ensureIdle(String serverKey) {
        if (this.operationRegistry.hasActive(serverKey)) {
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
            boolean completeOnServerThread
    ) {

        public PreparedApplyOperation(List<PreparedChunkBatch> batches, CompletionAction onComplete) {
            this(batches, onComplete, false);
        }

        public PreparedApplyOperation(
                List<PreparedChunkBatch> batches,
                CompletionAction onComplete,
                boolean completeOnServerThread
        ) {
            this(
                    LocalQueue.completed(batches == null
                            ? List.of()
                            : batches.stream().map(ChunkBatch::fromPrepared).toList()),
                    onComplete,
                    completeOnServerThread
            );
        }

        public int totalWorkUnits() {
            return this.localQueue == null ? 0 : this.localQueue.totalWorkUnits();
        }
    }

    abstract static class ActiveOperation {

        private final ServerLevel level;
        private final OperationHandle handle;
        private final String unitLabel;
        private volatile OperationSnapshot snapshot;
        private volatile OperationStage lastLoggedStage;
        private volatile int lastLoggedPercent = -1;
        protected final WorldApplyPerformanceGovernor performanceGovernor = new WorldApplyPerformanceGovernor();

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

        protected ActiveOperation followUpOperation() {
            return null;
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
                this.logBlockApplyPrepared();
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
                        "Apply tick start label={} progress={}/{} adaptiveScale={} budget=[{}] currentBatch={} dispatcherPending={} lightPending={} redstonePending={}",
                        this.handle().label(),
                        this.appliedWorkUnits,
                        this.prepared.totalWorkUnits(),
                        this.adaptiveScale(),
                        budget.summary(),
                        this.currentBatch == null ? "none" : this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z(),
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
                                    processedWorkThisTick
                            );
                    if (!chunkStartDecision.allowed()) {
                        stopReason = "chunk-cost-" + chunkStartDecision.reason();
                        this.logChunkCostDefer(chunkStartDecision);
                        break;
                    }
                    startedChunksThisTick += 1;
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
                    this.logBlockApplyChunkStart(this.currentBatch);
                    WorldOperationManager.this.historyDebugLog.logReplayBatch(this.handle(), this.currentBatch);
                }

                WorldApplyTickGateDecision decision = WorldOperationManager.this.tickWorkGate.decide(
                        this.hasPendingNativeSection(),
                        this.hasPendingNativeSection() ? this.pendingNativeSection().safetyProfile().path() : null,
                        processedWorkThisTick,
                        processedNativeSectionsThisTick,
                        processedNativeCellsThisTick,
                        processedRewriteSectionsThisTick,
                        processedDirectSectionsThisTick,
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
                    int maxBlocks = this.maxWorkForCurrentStep(budget, processedWorkThisTick, processedNativeCellsThisTick);
                    int maxDirectSections = Math.max(0, budget.maxDirectSections() - processedDirectSectionsThisTick);
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
                    this.exactReplayStateQueue.record(this.currentBatch);
                    this.logBlockApplyChunkFinish(this.currentBatch);
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
            long applyTickElapsedNanos = System.nanoTime() - applyTickStartedAt;
            this.applyMetrics.recordApplyTick(processedWorkThisTick, applyTickElapsedNanos);
            this.logBlockApplyTickSummary(
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
                    tickStartLightChecks,
                    applyTickElapsedNanos
            );
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".applyTick",
                    applyTickElapsedNanos,
                    "workUnits=" + processedWorkThisTick
                            + ", stop=" + stopReason
                            + ", nativeCells=" + processedNativeCellsThisTick
                            + ", rewriteSections=" + processedRewriteSectionsThisTick
                            + ", directSections=" + processedDirectSectionsThisTick
            );

            if (this.currentBatch == null && (this.dispatcher == null || !this.dispatcher.hasPending())) {
                if (!this.drainDeferredRedstoneUpdates(budget, deadlineNanos)) {
                    return false;
                }
                if (!this.drainExactReplayStates(budget, deadlineNanos)) {
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
                ExactReplayStateGuard.getInstance().guard(
                        this.level(),
                        this.exactReplayStateQueue.takeRecordedPlacements(),
                        EXACT_REPLAY_GUARD_TICKS
                );
            }
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.appliedWorkUnits,
                    this.prepared.totalWorkUnits(),
                    this.applyDetail("Reasserting exact states, "
                            + this.exactReplayStateQueue.pendingCount()
                            + " blocks queued")
            );
            return !this.exactReplayStateQueue.hasPending() || reapplied > 0;
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

        private String preservedPreparationMarker(String detail) {
            if (detail == null
                    || (!detail.startsWith("Decoded initial snapshot")
                    && !detail.startsWith("Decoded exact initial snapshot"))) {
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
                    "Apply tick stop={} workThisTick={} nativeSectionsThisTick={} nativeCellsThisTick={} rewriteSectionsThisTick={} directSectionsThisTick={} chunksStarted={} chunksFinished={} totalsDelta=[processedBlocks={}, rewriteSections={}, nativeSections={}, fallbackSections={}, lightChecks={}] currentBatch={} dispatcherPending={} lightPending={} redstonePending={}",
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
                    this.lightUpdateQueue.pendingCount(),
                    this.redstoneUpdateQueue.pendingCount()
            );
        }

        private void logBlockApplyPrepared() {
            if (!this.blockApplyDiagnosticsEnabled()) {
                return;
            }
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

        private void logBlockApplyChunkStart(ChunkBatch batch) {
            if (!this.blockApplyDiagnosticsEnabled() || batch == null) {
                return;
            }
            BlockBatchShape shape = BlockBatchShape.from(batch);
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
                            + ", rewriteSections=" + this.rewriteSectionCount(batch)
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

        private void logBlockApplyTickSummary(
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
                int tickStartLightChecks,
                long elapsedNanos
        ) {
            if (!this.blockApplyDiagnosticsEnabled()) {
                return;
            }
            LumaDiagnosticsLog.blockApplySpan(
                    "apply-tick",
                    elapsedNanos,
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", stop=" + stopReason
                            + ", workThisTick=" + processedWorkThisTick
                            + ", nativeSectionsThisTick=" + processedNativeSectionsThisTick
                            + ", nativeCellsThisTick=" + processedNativeCellsThisTick
                            + ", rewriteSectionsThisTick=" + processedRewriteSectionsThisTick
                            + ", directSectionsThisTick=" + processedDirectSectionsThisTick
                            + ", chunksStarted=" + startedChunksThisTick
                            + ", chunksFinished=" + finishedChunksThisTick
                            + ", processedDelta=" + (this.applyMetrics.processedBlocks() - tickStartProcessedBlocks)
                            + ", rewriteSectionsDelta=" + (this.applyMetrics.rewriteSections() - tickStartRewriteSections)
                            + ", nativeSectionsDelta=" + (this.applyMetrics.nativeSections() - tickStartNativeSections)
                            + ", fallbackSectionsDelta=" + (this.applyMetrics.fallbackSections() - tickStartFallbackSections)
                            + ", lightChecksDelta=" + (this.applyMetrics.lightChecks() - tickStartLightChecks)
                            + ", currentBatch=" + (this.currentBatch == null ? "none" : this.currentBatch.chunk().x() + ":" + this.currentBatch.chunk().z())
                            + ", dispatcherPending=" + (this.dispatcher != null && this.dispatcher.hasPending())
                            + ", lightPending=" + this.lightUpdateQueue.pendingCount()
                            + ", redstonePending=" + this.redstoneUpdateQueue.pendingCount()
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
                            this.commitSummary(result.commitResult())
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
                                    + ", commit=[" + this.commitSummary(result.commitResult()) + "]"
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
                            this.commitSummary(result.commitResult())
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
                                    + ", commit=[" + this.commitSummary(result.commitResult()) + "]"
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
                        Math.min(maxBlocks, maxEntityOperations)
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
                    this::releasePreloadTickets
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

    private record BlockBatchShape(
            int setTargets,
            int deleteTargets,
            int sparseTargets,
            int nativeTargets,
            int rewriteTargets
    ) {

        private static BlockBatchShape from(ChunkBatch batch) {
            if (batch == null) {
                return new BlockBatchShape(0, 0, 0, 0, 0);
            }
            int[] counts = new int[5];
            for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
                int before = counts[0] + counts[1];
                addNativeTargets(counts, section);
                int added = counts[0] + counts[1] - before;
                if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                    counts[4] += added;
                } else {
                    counts[3] += added;
                }
            }
            for (SectionBatch section : batch.orderedSections()) {
                if (section.placements() == null) {
                    continue;
                }
                for (PreparedBlockPlacement placement : section.placements()) {
                    addTarget(counts, placement.state());
                    counts[2] += 1;
                }
            }
            return new BlockBatchShape(counts[0], counts[1], counts[2], counts[3], counts[4]);
        }

        private static void addNativeTargets(int[] counts, PreparedSectionApplyBatch section) {
            if (section == null || section.buffer() == null) {
                return;
            }
            section.buffer().changedCells().forEachSetCell(localIndex ->
                    addTarget(counts, section.buffer().targetStateAt(localIndex))
            );
        }

        private static void addTarget(int[] counts, BlockState state) {
            if (state != null && state.isAir()) {
                counts[1] += 1;
            } else {
                counts[0] += 1;
            }
        }
    }

    private final class LightRefreshActiveOperation extends ActiveOperation {

        private final WorldLightUpdateQueue lightUpdateQueue;
        private final Runnable inheritedTicketRelease;
        private final WorldApplyMetrics applyMetrics = new WorldApplyMetrics();
        private final long startedAtNanos = System.nanoTime();
        private List<ChunkPoint> dirtyChunks = List.of();
        private List<SectionPos> dirtySections = List.of();
        private WorldApplyChunkPreloader lightChunkPreloader;
        private ChunkSkylightRefreshQueue skylightRefreshQueue;
        private CompletableFuture<?> lightBarrierFuture;
        private int publishTicksRemaining = -1;
        private boolean lightPreparationRecorded = false;
        private boolean checkedLightWork = false;
        private boolean prepareWaitLogged = false;
        private boolean barrierCompleteLogged = false;
        private boolean finalDirtyMarkDone = false;
        private boolean publishStartLogged = false;
        private boolean releasedTickets = false;
        private int totalAppliedChecks = 0;
        private int totalMarkedChunks = 0;
        private int finalMarkedChunks = 0;
        private int totalAttemptedDirtyChunks = 0;
        private int totalMissingDirtyChunks = 0;
        private int finalMissingDirtyChunks = 0;
        private int preparedChecks = 0;
        private int preparedDirtyChunkCount = 0;
        private int preparedDirtySectionCount = 0;
        private int totalSkylightSectionUpdates = 0;
        private int totalMissingSkylightSections = 0;
        private int totalSkylightChunkRefreshes = 0;
        private int totalMissingSkylightChunks = 0;

        private LightRefreshActiveOperation(
                ServerLevel level,
                OperationHandle handle,
                WorldLightUpdateQueue lightUpdateQueue,
                Runnable inheritedTicketRelease
        ) {
            super(level, handle, "light checks");
            this.lightUpdateQueue = lightUpdateQueue == null ? new WorldLightUpdateQueue() : lightUpdateQueue;
            this.inheritedTicketRelease = inheritedTicketRelease == null ? () -> { } : inheritedTicketRelease;
            LumaDiagnosticsLog.lightEvent(
                    "operation-start",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", projectId=" + this.handle().projectId()
                            + ", pendingChecks=" + this.lightUpdateQueue.pendingCount()
            );
        }

        @Override
        boolean advance(WorldApplyBudget budget, long deadlineNanos) {
            if (this.lightUpdateQueue.hasPending()) {
                return this.drainDeferredLightUpdates(budget, deadlineNanos);
            }

            if (!this.awaitLightEngineBarrier()) {
                return false;
            }
            this.markDirtyChunksAfterLightBarrier();
            if (!this.awaitLightPublishTicks()) {
                return false;
            }

            LumaDiagnosticsLog.lightEvent(
                    "operation-complete",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", checkedLightWork=" + this.checkedLightWork
                            + ", totalAppliedChecks=" + this.totalAppliedChecks
                            + ", totalMarkedChunks=" + this.totalMarkedChunks
                            + ", finalMarkedChunks=" + this.finalMarkedChunks
                            + ", totalAttemptedDirtyChunks=" + this.totalAttemptedDirtyChunks
                            + ", totalMissingDirtyChunks=" + this.totalMissingDirtyChunks
                            + ", finalMissingDirtyChunks=" + this.finalMissingDirtyChunks
                            + ", preparedChecks=" + this.preparedChecks
                            + ", dirtyChunks=" + this.dirtyChunks.size()
                            + ", dirtySections=" + this.dirtySections.size()
                            + ", skylightSectionUpdates=" + this.totalSkylightSectionUpdates
                            + ", missingSkylightSections=" + this.totalMissingSkylightSections
                            + ", skylightChunkRefreshes=" + this.totalSkylightChunkRefreshes
                            + ", missingSkylightChunks=" + this.totalMissingSkylightChunks
                            + ", lightPreloadComplete=" + (this.lightChunkPreloader == null || this.lightChunkPreloader.complete())
                            + ", dirtyChunkSummary=" + this.dirtyChunkSummary()
            );
            this.complete(this.checkedLightWork ? "Light refreshed" : "No light refresh needed");
            return true;
        }

        private void markDirtyChunksAfterLightBarrier() {
            if (this.finalDirtyMarkDone) {
                return;
            }
            this.finalDirtyMarkDone = true;
            int marked = 0;
            int missing = 0;
            for (ChunkPoint dirtyChunk : this.dirtyChunks) {
                if (dirtyChunk == null) {
                    continue;
                }
                LevelChunk chunk = this.level().getChunkSource().getChunkNow(dirtyChunk.x(), dirtyChunk.z());
                if (chunk == null) {
                    missing += 1;
                    continue;
                }
                chunk.markUnsaved();
                marked += 1;
            }
            this.finalMarkedChunks = marked;
            this.finalMissingDirtyChunks = missing;
            this.totalMarkedChunks += marked;
            this.totalAttemptedDirtyChunks += marked + missing;
            this.totalMissingDirtyChunks += missing;
            LumaDiagnosticsLog.lightEvent(
                    "final-dirty-mark",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", markedChunks=" + marked
                            + ", missingChunks=" + missing
                            + ", dirtyChunks=" + this.dirtyChunks.size()
                            + ", checkedLightWork=" + this.checkedLightWork
                            + ", dirtyChunkSummary=" + this.dirtyChunkSummary()
            );
        }

        private boolean drainDeferredLightUpdates(WorldApplyBudget budget, long deadlineNanos) {
            LumiTestFailpoints.hit(LumiTestFailpoints.LIGHT_REFRESH_DRAIN_START);
            if (!this.lightUpdateQueue.prepareDrainPositionsAsync(WorldOperationManager.this.executor())) {
                if (!this.prepareWaitLogged) {
                    this.prepareWaitLogged = true;
                    LumaDiagnosticsLog.lightEvent(
                            "prepare-start",
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", pendingChecks=" + this.lightUpdateQueue.pendingCount()
                    );
                }
                this.progressSink().update(
                        OperationStage.PREPARING,
                        0,
                        Math.max(1, this.lightUpdateQueue.pendingCount()),
                        "Preparing light updates"
                );
                return false;
            }
            if (!this.lightPreparationRecorded) {
                this.dirtyChunks = this.lightUpdateQueue.preparedDirtyChunks();
                this.dirtySections = this.lightUpdateQueue.preparedDirtySections();
                this.preparedChecks = this.lightUpdateQueue.preparedCheckCount();
                this.preparedDirtyChunkCount = this.lightUpdateQueue.dirtyChunkCount();
                this.preparedDirtySectionCount = this.lightUpdateQueue.dirtySectionCount();
                this.lightChunkPreloader = WorldApplyChunkPreloader.forChunks(this.dirtyChunks);
                this.skylightRefreshQueue = new ChunkSkylightRefreshQueue(this.dirtyChunks, this.dirtySections);
                this.applyMetrics.recordPreparationDuration(System.nanoTime() - this.startedAtNanos);
                this.applyMetrics.recordLightPrepared(
                        this.lightUpdateQueue.preparedCheckCount(),
                        this.lightUpdateQueue.dirtyChunkCount()
                );
                this.lightPreparationRecorded = true;
                LumaDiagnosticsLog.lightEvent(
                        "prepared",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", preparedChecks=" + this.preparedChecks
                                + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                + ", dirtySections=" + this.preparedDirtySectionCount
                                + ", dirtyChunkSummary=" + this.dirtyChunkSummary()
                );
            }

            if (!this.advanceLightChunkPreload(budget, deadlineNanos)) {
                return false;
            }
            if (!this.refreshChunkSkylightState(budget, deadlineNanos)) {
                return false;
            }

            int pendingBefore = this.lightUpdateQueue.pendingCount();
            int maxChecks = Math.max(128, budget.maxLightChecks());
            long startedAt = System.nanoTime();
            int appliedChecks = this.lightUpdateQueue.drain(this.level(), maxChecks, deadlineNanos);
            WorldLightUpdateQueue.TouchedChunkMarkResult markResult = this.lightUpdateQueue.markTouchedChunksUnsaved(
                    this.level(),
                    Math.max(1, budget.maxPreloadChunks() * 4),
                    deadlineNanos
            );
            long elapsedNanos = System.nanoTime() - startedAt;
            this.totalAppliedChecks += appliedChecks;
            this.totalMarkedChunks += markResult.markedChunks();
            this.totalAttemptedDirtyChunks += markResult.attemptedChunks();
            this.totalMissingDirtyChunks += markResult.missingChunks();
            this.applyMetrics.recordLightChecks(appliedChecks);
            this.applyMetrics.recordLightDrainTick(elapsedNanos);
            this.applyMetrics.recordApplyTick(appliedChecks, elapsedNanos);
            this.performanceGovernor.recordWork(ApplyWorkKind.LIGHT_DRAIN, appliedChecks, elapsedNanos);
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".lightDrainTick",
                    elapsedNanos,
                    "appliedChecks=" + appliedChecks
                            + ", markedChunks=" + markResult.markedChunks()
                            + ", missingChunks=" + markResult.missingChunks()
                            + ", attemptedChunks=" + markResult.attemptedChunks()
                            + ", pendingBefore=" + pendingBefore
            );
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Light refresh drain maxChecks={} applied={} markedChunks={} missingChunks={} attemptedChunks={} pendingBefore={} pendingAfter={} elapsedMicros={}",
                        maxChecks,
                        appliedChecks,
                        markResult.markedChunks(),
                        markResult.missingChunks(),
                        markResult.attemptedChunks(),
                        pendingBefore,
                        this.lightUpdateQueue.pendingCount(),
                        elapsedNanos / 1_000L
                );
            }
            LumaDiagnosticsLog.lightSpan(
                    "drain-tick",
                    elapsedNanos,
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", maxChecks=" + maxChecks
                            + ", appliedChecks=" + appliedChecks
                            + ", markedChunks=" + markResult.markedChunks()
                            + ", missingChunks=" + markResult.missingChunks()
                            + ", attemptedChunks=" + markResult.attemptedChunks()
                            + ", remainingDirtyChunks=" + markResult.remainingChunks()
                            + ", pendingBefore=" + pendingBefore
                            + ", pendingAfter=" + this.lightUpdateQueue.pendingCount()
                            + ", totalAppliedChecks=" + this.totalAppliedChecks
                            + ", totalMarkedChunks=" + this.totalMarkedChunks
                            + ", totalMissingDirtyChunks=" + this.totalMissingDirtyChunks
                            + ", preparedChecks=" + this.preparedChecks
                            + ", dirtyChunks=" + this.preparedDirtyChunkCount
            );
            int totalChecks = Math.max(1, this.preparedChecks);
            this.progressSink().update(
                    OperationStage.APPLYING,
                    Math.max(0, totalChecks - this.lightUpdateQueue.pendingCount()),
                    totalChecks,
                    "Updating light"
            );
            return false;
        }

        private boolean advanceLightChunkPreload(WorldApplyBudget budget, long deadlineNanos) {
            if (this.lightChunkPreloader == null
                    || !this.lightChunkPreloader.required()
                    || this.lightChunkPreloader.complete()) {
                return true;
            }
            long startedAt = System.nanoTime();
            WorldApplyChunkPreloader.PreloadTickResult result = this.lightChunkPreloader.advance(
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
            this.progressSink().update(
                    OperationStage.PRELOADING,
                    result.completedChunks(),
                    result.totalChunks(),
                    "Preloading light chunks " + result.completedChunks() + "/" + result.totalChunks()
            );
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".lightPreloadTick",
                    elapsedNanos,
                    "chunks=" + result.completedChunks() + "/" + result.totalChunks()
                            + ", newlyLoaded=" + result.newlyLoadedChunks()
                            + ", alreadyLoaded=" + result.alreadyLoadedChunks()
                            + ", ticketed=" + result.ticketedChunks()
                            + ", outstandingTickets=" + result.outstandingTickets()
                            + ", syncFallbackLoads=" + result.syncFallbackLoads()
            );
            if (this.debugApplyEnabled()) {
                LumaDebugLog.log(
                        this.handle(),
                        "world-op-apply",
                        "Light chunk preload chunks={}/{} newlyLoaded={} alreadyLoaded={} ticketed={} outstandingTickets={} syncFallbackLoads={} elapsedMicros={} complete={}",
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
            LumaDiagnosticsLog.lightSpan(
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
            return result.complete();
        }

        private boolean refreshChunkSkylightState(WorldApplyBudget budget, long deadlineNanos) {
            if (this.skylightRefreshQueue == null
                    || !this.skylightRefreshQueue.required()
                    || this.skylightRefreshQueue.complete()) {
                return true;
            }

            int maxSections = Math.max(1, budget.maxRewriteSections() + budget.maxNativeSections());
            int maxChunks = Math.max(1, budget.maxPreloadChunks() / 4);
            long startedAt = System.nanoTime();
            ChunkSkylightRefreshQueue.RefreshTickResult result = this.skylightRefreshQueue.drain(
                    new ServerChunkSkylightRefreshAccess(this.level()),
                    maxSections,
                    maxChunks,
                    deadlineNanos
            );
            long elapsedNanos = System.nanoTime() - startedAt;
            this.totalSkylightSectionUpdates += result.sectionUpdates();
            this.totalMissingSkylightSections += result.missingSections();
            this.totalSkylightChunkRefreshes += result.refreshedChunks();
            this.totalMissingSkylightChunks += result.missingChunks();
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.preparedDirtySectionCount - result.remainingSections(),
                    Math.max(1, this.preparedDirtySectionCount + this.preparedDirtyChunkCount),
                    "Refreshing skylight sources"
            );
            LumaLoadLog.record(
                    "world-op",
                    this.handle().label() + ".skylightSourceRefreshTick",
                    elapsedNanos,
                    "sectionUpdates=" + result.sectionUpdates()
                            + ", missingSections=" + result.missingSections()
                            + ", attemptedSections=" + result.attemptedSections()
                            + ", refreshedChunks=" + result.refreshedChunks()
                            + ", missingChunks=" + result.missingChunks()
                            + ", attemptedChunks=" + result.attemptedChunks()
                            + ", remainingSections=" + result.remainingSections()
                            + ", remainingChunks=" + result.remainingChunks()
            );
            LumaDiagnosticsLog.lightSpan(
                    "skylight-source-refresh-tick",
                    elapsedNanos,
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", maxSections=" + maxSections
                            + ", maxChunks=" + maxChunks
                            + ", sectionUpdates=" + result.sectionUpdates()
                            + ", missingSections=" + result.missingSections()
                            + ", attemptedSections=" + result.attemptedSections()
                            + ", refreshedChunks=" + result.refreshedChunks()
                            + ", missingChunks=" + result.missingChunks()
                            + ", attemptedChunks=" + result.attemptedChunks()
                            + ", remainingSections=" + result.remainingSections()
                            + ", remainingChunks=" + result.remainingChunks()
                            + ", totalSectionUpdates=" + this.totalSkylightSectionUpdates
                            + ", totalMissingSections=" + this.totalMissingSkylightSections
                            + ", totalChunkRefreshes=" + this.totalSkylightChunkRefreshes
                            + ", totalMissingChunks=" + this.totalMissingSkylightChunks
                            + ", complete=" + result.complete()
            );
            return result.complete();
        }

        private boolean awaitLightEngineBarrier() {
            ThreadedLevelLightEngine lightEngine = this.level().getChunkSource().getLightEngine();
            lightEngine.tryScheduleUpdate();
            if (this.lightBarrierFuture == null) {
                this.lightBarrierFuture = this.createLightBarrier(lightEngine);
                LumaLoadLog.record(
                        "world-op",
                        this.handle().label() + ".lightBarrier",
                        0L,
                        "dirtyChunks=" + this.dirtyChunks.size()
                );
                LumaDiagnosticsLog.lightEvent(
                        "barrier-start",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", preparedChecks=" + this.preparedChecks
                                + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                + ", dirtySections=" + this.preparedDirtySectionCount
                                + ", skylightSectionUpdates=" + this.totalSkylightSectionUpdates
                                + ", skylightChunkRefreshes=" + this.totalSkylightChunkRefreshes
                                + ", dirtyChunkSummary=" + this.dirtyChunkSummary()
                );
            }
            boolean futureDone = this.lightBarrierFuture.isDone();
            boolean hasLightWork = this.level().getLightEngine().hasLightWork();
            if (!futureDone || hasLightWork) {
                this.checkedLightWork = true;
                this.applyMetrics.recordLightEngineFlushTick();
                LumaDiagnosticsLog.lightEvent(
                        "barrier-wait",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", futureDone=" + futureDone
                                + ", hasLightWork=" + hasLightWork
                                + ", flushTicks=" + this.applyMetrics.lightEngineFlushTicks()
                                + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                + ", dirtySections=" + this.preparedDirtySectionCount
                                + ", preparedChecks=" + this.preparedChecks
                );
                this.progressSink().update(
                        OperationStage.FINALIZING,
                        this.preparedChecks,
                        Math.max(1, this.preparedChecks),
                        "Waiting for light engine"
                );
                return false;
            }
            this.lightBarrierFuture.join();
            if (!this.barrierCompleteLogged) {
                this.barrierCompleteLogged = true;
                LumaDiagnosticsLog.lightEvent(
                        "barrier-complete",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", checkedLightWork=" + this.checkedLightWork
                                + ", flushTicks=" + this.applyMetrics.lightEngineFlushTicks()
                                + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                + ", dirtySections=" + this.preparedDirtySectionCount
                                + ", preparedChecks=" + this.preparedChecks
                );
            }
            return true;
        }

        private CompletableFuture<?> createLightBarrier(ThreadedLevelLightEngine lightEngine) {
            if (this.dirtyChunks.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<?>[] futures = new CompletableFuture<?>[this.dirtyChunks.size()];
            for (int index = 0; index < this.dirtyChunks.size(); index++) {
                ChunkPoint chunk = this.dirtyChunks.get(index);
                futures[index] = lightEngine.waitForPendingTasks(chunk.x(), chunk.z());
            }
            return CompletableFuture.allOf(futures);
        }

        private boolean awaitLightPublishTicks() {
            if (this.publishTicksRemaining < 0) {
                this.publishTicksRemaining = LIGHT_PUBLISH_TICKS;
                this.publishStartLogged = true;
                LumaDiagnosticsLog.lightEvent(
                        "publish-start",
                        "label=" + this.handle().label()
                                + ", operationId=" + this.handle().id()
                                + ", publishTicks=" + LIGHT_PUBLISH_TICKS
                                + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                + ", preparedChecks=" + this.preparedChecks
                );
            }
            if (this.publishTicksRemaining <= 0) {
                if (this.publishStartLogged) {
                    LumaDiagnosticsLog.lightEvent(
                            "publish-complete",
                            "label=" + this.handle().label()
                                    + ", operationId=" + this.handle().id()
                                    + ", dirtyChunks=" + this.preparedDirtyChunkCount
                                    + ", preparedChecks=" + this.preparedChecks
                    );
                }
                return true;
            }
            this.checkedLightWork = true;
            this.publishTicksRemaining -= 1;
            LumaDiagnosticsLog.lightEvent(
                    "publish-tick",
                    "label=" + this.handle().label()
                            + ", operationId=" + this.handle().id()
                            + ", remainingAfter=" + this.publishTicksRemaining
                            + ", dirtyChunks=" + this.preparedDirtyChunkCount
                            + ", preparedChecks=" + this.preparedChecks
            );
            this.progressSink().update(
                    OperationStage.FINALIZING,
                    this.preparedChecks,
                    Math.max(1, this.preparedChecks),
                    "Publishing light updates"
            );
            return false;
        }

        private String dirtyChunkSummary() {
            if (this.dirtyChunks.isEmpty()) {
                return "none";
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            StringJoiner sample = new StringJoiner("|");
            for (int index = 0; index < this.dirtyChunks.size(); index++) {
                ChunkPoint chunk = this.dirtyChunks.get(index);
                minX = Math.min(minX, chunk.x());
                maxX = Math.max(maxX, chunk.x());
                minZ = Math.min(minZ, chunk.z());
                maxZ = Math.max(maxZ, chunk.z());
                if (index < 8) {
                    sample.add(chunk.x() + ":" + chunk.z());
                }
            }
            return "count=" + this.dirtyChunks.size()
                    + ", minX=" + minX
                    + ", maxX=" + maxX
                    + ", minZ=" + minZ
                    + ", maxZ=" + maxZ
                    + ", sample=" + sample;
        }

        private boolean debugApplyEnabled() {
            return LumaDebugLog.enabled(this.handle());
        }

        @Override
        protected Optional<String> applyMetricsSummary() {
            this.applyMetrics.recordTotalDuration(Duration.between(this.handle().startedAt(), Instant.now()).toNanos());
            return Optional.of(this.applyMetrics.summary());
        }

        @Override
        protected void complete(String detail) {
            this.releaseLightTickets();
            super.complete(detail);
        }

        @Override
        protected void fail(Exception exception) {
            this.releaseLightTickets();
            super.fail(exception);
        }

        private void releaseLightTickets() {
            if (this.releasedTickets) {
                return;
            }
            this.releasedTickets = true;
            ChunkPreloadAccess access = new ServerLevelChunkPreloadAccess(this.level());
            if (this.lightChunkPreloader != null) {
                this.lightChunkPreloader.release(access);
            }
            this.inheritedTicketRelease.run();
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
