package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * Records volatile live undo/redo actions independently from the durable
 * working draft.
 */
final class LiveUndoRedoActionRecorder {

    private static final Duration SECONDARY_ACTION_JOIN_WINDOW = Duration.ofSeconds(10);
    private static final int SECONDARY_SOURCE_JOIN_RADIUS = 2;
    private static final Duration SPREADING_FALLOUT_JOIN_WINDOW = Duration.ofSeconds(60);
    private static final int SPREADING_FALLOUT_JOIN_RADIUS = 8;

    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final UndoRedoActionGroupingPolicy groupingPolicy = new UndoRedoActionGroupingPolicy();
    private final MutationSourcePolicy sourcePolicy = new MutationSourcePolicy();
    private final HistoryDebugLog historyDebugLog;

    LiveUndoRedoActionRecorder(HistoryDebugLog historyDebugLog) {
        this.historyDebugLog = historyDebugLog;
    }

    void recordBlockAction(
            TrackedProject trackedProject,
            ServerLevel level,
            StoredBlockChange change,
            Instant now
    ) {
        if (change == null || change.isNoOp() || change.hidden()) {
            return;
        }

        String actionId = this.groupingPolicy.actionIdForBlockChange(
                WorldMutationContext.currentSource(),
                WorldMutationContext.currentActionId(),
                change
        );
        boolean actionAllowed = WorldMutationContext.currentAccessAllowed() || !level.getServer().isDedicatedServer();
        if (actionAllowed && !actionId.isBlank() && this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            this.historyManager.recordChange(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    actionId,
                    WorldMutationContext.currentActor(),
                    change,
                    now
            );
            this.historyDebugLog.logLiveUndoRedoBlock(
                    trackedProject.project(),
                    "root",
                    actionId,
                    WorldMutationContext.currentSource(),
                    change
            );
            return;
        }
        if (actionAllowed && !actionId.isBlank()) {
            this.historyManager.recordCausalChange(
                    trackedProject.project().id().toString(),
                    actionId,
                    change,
                    now
            );
            this.historyDebugLog.logLiveUndoRedoBlock(
                    trackedProject.project(),
                    "causal",
                    actionId,
                    WorldMutationContext.currentSource(),
                    change
            );
            return;
        }

        if (this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            return;
        }

        this.historyManager.recordRelatedChange(
                trackedProject.project().id().toString(),
                level.dimension().identifier().toString(),
                change,
                now,
                relatedJoinWindowFor(WorldMutationContext.currentSource()),
                relatedJoinRadiusFor(WorldMutationContext.currentSource())
        );
        this.historyDebugLog.logLiveUndoRedoBlock(
                trackedProject.project(),
                "related",
                actionId,
                WorldMutationContext.currentSource(),
                change
        );
    }

    void recordBlockAction(
            TrackedProject trackedProject,
            ServerLevel level,
            List<StoredBlockChange> changes,
            Instant now
    ) {
        List<StoredBlockChange> visibleChanges = changes == null
                ? List.of()
                : changes.stream()
                .filter(change -> change != null && !change.isNoOp() && !change.hidden())
                .toList();
        if (visibleChanges.isEmpty()) {
            return;
        }

        WorldMutationSource source = WorldMutationContext.currentSource();
        String actionId = WorldMutationContext.currentActionId();
        boolean actionAllowed = WorldMutationContext.currentAccessAllowed() || !level.getServer().isDedicatedServer();
        if (actionAllowed && actionId != null && !actionId.isBlank() && this.sourcePolicy.isExplicitRootSource(source)) {
            this.historyManager.recordAction(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    actionId,
                    WorldMutationContext.currentActor(),
                    visibleChanges,
                    List.of(),
                    now
            );
            return;
        }

        for (StoredBlockChange change : visibleChanges) {
            this.recordBlockAction(trackedProject, level, change, now);
        }
    }

    void recordEntityAction(
            TrackedProject trackedProject,
            ServerLevel level,
            StoredEntityChange change,
            Instant now,
            Instant actionStartedAt
    ) {
        if (change == null || change.isNoOp()) {
            return;
        }

        String actionId = WorldMutationContext.currentActionId();
        boolean actionAllowed = WorldMutationContext.currentAccessAllowed() || !level.getServer().isDedicatedServer();
        if (actionAllowed && !actionId.isBlank() && this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            if (actionStartedAt == null) {
                this.historyManager.recordEntityChange(
                        trackedProject.project().id().toString(),
                        level.dimension().identifier().toString(),
                        actionId,
                        WorldMutationContext.currentActor(),
                        change,
                        now
                );
            } else {
                this.historyManager.recordDelayedEntityChange(
                        trackedProject.project().id().toString(),
                        level.dimension().identifier().toString(),
                        actionId,
                        WorldMutationContext.currentActor(),
                        change,
                        actionStartedAt,
                        now
                );
            }
            return;
        }
        if (actionAllowed && !actionId.isBlank()) {
            this.historyManager.recordCausalEntityChange(
                    trackedProject.project().id().toString(),
                    actionId,
                    change,
                    now
            );
            return;
        }

        if (this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            return;
        }

        this.historyManager.recordRelatedEntityChange(
                trackedProject.project().id().toString(),
                level.dimension().identifier().toString(),
                change,
                now,
                relatedJoinWindowFor(WorldMutationContext.currentSource()),
                relatedJoinRadiusFor(WorldMutationContext.currentSource())
        );
    }

    void recordReconciledChanges(
            TrackedProject trackedProject,
            ServerLevel level,
            SessionStabilizationService.ReconciliationResult result,
            Instant now
    ) {
        List<StoredBlockChange> changes = result == null ? List.of() : result.deltaChanges();
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Map<CaptureSessionState.DeferredActionContext, List<StoredBlockChange>> actionChanges = new LinkedHashMap<>();
        List<StoredBlockChange> relatedChanges = new ArrayList<>();
        Map<ChunkPoint, CaptureSessionState.DeferredActionContext> deferredContexts =
                result.deferredActionContexts();
        for (StoredBlockChange change : changes) {
            if (change == null || change.isNoOp()) {
                continue;
            }
            CaptureSessionState.DeferredActionContext deferredContext =
                    deferredContexts.get(ChunkPoint.from(change.pos()));
            if (this.canRecordDeferredAction(level, deferredContext)) {
                actionChanges.computeIfAbsent(deferredContext, ignored -> new ArrayList<>()).add(change);
            } else {
                relatedChanges.add(change);
            }
        }

        for (Map.Entry<CaptureSessionState.DeferredActionContext, List<StoredBlockChange>> entry : actionChanges.entrySet()) {
            CaptureSessionState.DeferredActionContext context = entry.getKey();
            this.historyManager.recordCausalAction(
                    trackedProject.project().id().toString(),
                    context.actionId(),
                    entry.getValue(),
                    List.of(),
                    now
            );
        }

        for (StoredBlockChange change : relatedChanges) {
            this.historyManager.recordRelatedChange(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    change,
                    now,
                    SECONDARY_ACTION_JOIN_WINDOW,
                    SECONDARY_SOURCE_JOIN_RADIUS
            );
        }
    }

    static Duration relatedJoinWindowFor(WorldMutationSource source) {
        return isSpreadingFalloutSource(source) ? SPREADING_FALLOUT_JOIN_WINDOW : SECONDARY_ACTION_JOIN_WINDOW;
    }

    static int relatedJoinRadiusFor(WorldMutationSource source) {
        return isSpreadingFalloutSource(source) ? SPREADING_FALLOUT_JOIN_RADIUS : SECONDARY_SOURCE_JOIN_RADIUS;
    }

    private static boolean isSpreadingFalloutSource(WorldMutationSource source) {
        return source == WorldMutationSource.FLUID || source == WorldMutationSource.FALLING_BLOCK;
    }

    private boolean canRecordDeferredAction(
            ServerLevel level,
            CaptureSessionState.DeferredActionContext deferredContext
    ) {
        if (deferredContext == null || !deferredContext.hasAction()) {
            return false;
        }
        return deferredContext.accessAllowed() || !level.getServer().isDedicatedServer();
    }
}
