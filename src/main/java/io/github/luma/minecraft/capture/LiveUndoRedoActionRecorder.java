package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.debug.HistoryDebugLog;
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
        if (change == null || change.isNoOp()) {
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
            this.historyManager.recordCurrentCausalChange(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    actionId,
                    WorldMutationContext.currentActor(),
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

        if (change.hidden()) {
            return;
        }

        if (this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            return;
        }

        this.historyDebugLog.logSkippedLiveUndoRedoBlock(
                trackedProject.project(),
                "missing-action-context",
                "live-undo-requires-action-id",
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
        List<StoredBlockChange> recordableChanges = recordableBlockChanges(changes);
        if (recordableChanges.isEmpty()) {
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
                    recordableChanges,
                    List.of(),
                    now
            );
            return;
        }

        for (StoredBlockChange change : recordableChanges) {
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
        WorldMutationSource source = WorldMutationContext.currentSource();
        boolean actionAllowed = WorldMutationContext.currentAccessAllowed() || !level.getServer().isDedicatedServer();
        if (actionAllowed && !actionId.isBlank() && this.sourcePolicy.isExplicitRootSource(source)) {
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
            if (actionStartedAt == null) {
                this.historyManager.recordCurrentCausalAction(
                        trackedProject.project().id().toString(),
                        level.dimension().identifier().toString(),
                        actionId,
                        WorldMutationContext.currentActor(),
                        List.of(),
                        List.of(change),
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

        if (this.sourcePolicy.isExplicitRootSource(source) || requiresCausalActionForEntityReplay(source)) {
            return;
        }
    }

    void recordEntityAction(
            TrackedProject trackedProject,
            ServerLevel level,
            List<StoredEntityChange> changes,
            Instant now,
            Instant actionStartedAt
    ) {
        List<StoredEntityChange> recordableChanges = changes == null
                ? List.of()
                : changes.stream()
                .filter(change -> change != null && !change.isNoOp())
                .toList();
        if (recordableChanges.isEmpty()) {
            return;
        }
        if (recordableChanges.size() == 1) {
            this.recordEntityAction(trackedProject, level, recordableChanges.getFirst(), now, actionStartedAt);
            return;
        }

        String actionId = WorldMutationContext.currentActionId();
        boolean actionAllowed = WorldMutationContext.currentAccessAllowed() || !level.getServer().isDedicatedServer();
        if (actionStartedAt != null && actionAllowed && !actionId.isBlank()) {
            this.historyManager.recordDelayedEntityChanges(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    actionId,
                    WorldMutationContext.currentActor(),
                    recordableChanges,
                    actionStartedAt,
                    now
            );
            return;
        }

        for (StoredEntityChange change : recordableChanges) {
            this.recordEntityAction(trackedProject, level, change, now, actionStartedAt);
        }
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
        List<StoredBlockChange> skippedChanges = new ArrayList<>();
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
                skippedChanges.add(change);
            }
        }

        for (Map.Entry<CaptureSessionState.DeferredActionContext, List<StoredBlockChange>> entry : actionChanges.entrySet()) {
            CaptureSessionState.DeferredActionContext context = entry.getKey();
            this.historyManager.recordCurrentCausalAction(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    context.actionId(),
                    context.actor(),
                    entry.getValue(),
                    List.of(),
                    now
            );
            this.historyDebugLog.logLiveUndoRedoActionBatch(
                    trackedProject.project(),
                    "reconciled-causal",
                    context.actionId(),
                    context.actor(),
                    entry.getValue()
            );
        }

        for (StoredBlockChange change : skippedChanges) {
            if (change.hidden()) {
                this.historyDebugLog.logSkippedLiveUndoRedoBlock(
                        trackedProject.project(),
                        "reconciled-related",
                        "hidden-without-action-context",
                        "",
                        null,
                        change
                );
                continue;
            }
            this.historyDebugLog.logSkippedLiveUndoRedoBlock(
                    trackedProject.project(),
                    "reconciled-related",
                    "missing-action-context",
                    "",
                    null,
                    change
            );
        }
    }

    static List<StoredBlockChange> recordableBlockChanges(List<StoredBlockChange> changes) {
        return changes == null
                ? List.of()
                : changes.stream()
                .filter(change -> change != null && !change.isNoOp())
                .toList();
    }

    static boolean requiresCausalActionForEntityReplay(WorldMutationSource source) {
        return source == WorldMutationSource.EXPLOSION || source == WorldMutationSource.MOB;
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
