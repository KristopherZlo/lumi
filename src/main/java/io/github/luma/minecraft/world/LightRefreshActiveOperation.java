package io.github.luma.minecraft.world;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Applies deferred light checks after a prepared world operation has completed.
 */
final class LightRefreshActiveOperation extends WorldOperationManager.ActiveOperation {

    private static final int LIGHT_PUBLISH_TICKS = 2;

    private final WorldLightUpdateQueue lightUpdateQueue;
    private final Runnable inheritedTicketRelease;
    private final Supplier<ExecutorService> executorSupplier;
    private final WorldOperationMetricsReporter metricsReporter;
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

    LightRefreshActiveOperation(
            ServerLevel level,
            OperationHandle handle,
            WorldLightUpdateQueue lightUpdateQueue,
            Runnable inheritedTicketRelease,
            Supplier<ExecutorService> executorSupplier,
            WorldOperationMetricsReporter metricsReporter
    ) {
        super(level, handle, "light checks");
        this.lightUpdateQueue = lightUpdateQueue == null ? new WorldLightUpdateQueue() : lightUpdateQueue;
        this.inheritedTicketRelease = inheritedTicketRelease == null ? () -> { } : inheritedTicketRelease;
        this.executorSupplier = executorSupplier;
        this.metricsReporter = metricsReporter;
        LumaDiagnosticsLog.lightEvent(
                "operation-start",
                "label=" + this.handle().label()
                        + ", operationId=" + this.handle().id()
                        + ", projectId=" + this.handle().projectId()
                        + ", pendingChecks=" + this.lightUpdateQueue.pendingCount()
        );
    }

    @Override
    protected boolean drainBeforeShutdown() {
        return true;
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
        if (!this.lightUpdateQueue.prepareDrainPositionsAsync(this.executorSupplier.get())) {
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
        return describeDirtyChunks(this.dirtyChunks);
    }

    static String describeDirtyChunks(List<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "none";
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        StringJoiner sample = new StringJoiner("|");
        for (int index = 0; index < chunks.size(); index++) {
            ChunkPoint chunk = chunks.get(index);
            minX = Math.min(minX, chunk.x());
            maxX = Math.max(maxX, chunk.x());
            minZ = Math.min(minZ, chunk.z());
            maxZ = Math.max(maxZ, chunk.z());
            if (index < 8) {
                sample.add(chunk.x() + ":" + chunk.z());
            }
        }
        return "count=" + chunks.size()
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
        return Optional.of(this.metricsReporter.summary(this.handle(), this.applyMetrics));
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
