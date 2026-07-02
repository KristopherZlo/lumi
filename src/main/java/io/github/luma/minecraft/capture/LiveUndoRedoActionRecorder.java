package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StatePayload;
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
import java.util.Set;
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
    private static final Set<String> FALLING_BLOCK_IDS = Set.of(
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:gravel",
            "minecraft:suspicious_sand",
            "minecraft:suspicious_gravel",
            "minecraft:dragon_egg",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:white_concrete_powder",
            "minecraft:light_gray_concrete_powder",
            "minecraft:gray_concrete_powder",
            "minecraft:black_concrete_powder",
            "minecraft:brown_concrete_powder",
            "minecraft:red_concrete_powder",
            "minecraft:orange_concrete_powder",
            "minecraft:yellow_concrete_powder",
            "minecraft:lime_concrete_powder",
            "minecraft:green_concrete_powder",
            "minecraft:cyan_concrete_powder",
            "minecraft:light_blue_concrete_powder",
            "minecraft:blue_concrete_powder",
            "minecraft:purple_concrete_powder",
            "minecraft:magenta_concrete_powder",
            "minecraft:pink_concrete_powder"
    );

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
            if (defersImmediateCausalChange(WorldMutationContext.currentSource(), change)) {
                this.historyDebugLog.logSkippedLiveUndoRedoBlock(
                        trackedProject.project(),
                        "growth-deferred-immediate",
                        "waiting-for-settled-reconciliation",
                        actionId,
                        WorldMutationContext.currentSource(),
                        change
                );
                return;
            }
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

        this.historyManager.recordRelatedEntityChange(
                trackedProject.project().id().toString(),
                level.dimension().identifier().toString(),
                change,
                now,
                relatedJoinWindowFor(source),
                relatedJoinRadiusFor(source)
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

        for (StoredBlockChange change : relatedChanges) {
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
            this.historyManager.recordRelatedChange(
                    trackedProject.project().id().toString(),
                    level.dimension().identifier().toString(),
                    change,
                    now,
                    relatedJoinWindowFor(change),
                    relatedJoinRadiusFor(change)
            );
            this.historyDebugLog.logLiveUndoRedoBlock(
                    trackedProject.project(),
                    "reconciled-related",
                    "",
                    null,
                    change
            );
        }
    }

    static Duration relatedJoinWindowFor(WorldMutationSource source) {
        return isSpreadingFalloutSource(source) ? SPREADING_FALLOUT_JOIN_WINDOW : SECONDARY_ACTION_JOIN_WINDOW;
    }

    static int relatedJoinRadiusFor(WorldMutationSource source) {
        return isSpreadingFalloutSource(source) ? SPREADING_FALLOUT_JOIN_RADIUS : SECONDARY_SOURCE_JOIN_RADIUS;
    }

    static Duration relatedJoinWindowFor(StoredBlockChange change) {
        return isSpreadingFalloutChange(change) ? SPREADING_FALLOUT_JOIN_WINDOW : SECONDARY_ACTION_JOIN_WINDOW;
    }

    static int relatedJoinRadiusFor(StoredBlockChange change) {
        return isSpreadingFalloutChange(change) ? SPREADING_FALLOUT_JOIN_RADIUS : SECONDARY_SOURCE_JOIN_RADIUS;
    }

    static List<StoredBlockChange> recordableBlockChanges(List<StoredBlockChange> changes) {
        return changes == null
                ? List.of()
                : changes.stream()
                .filter(change -> change != null && !change.isNoOp())
                .toList();
    }

    static boolean defersImmediateCausalChange(WorldMutationSource source, StoredBlockChange change) {
        return false;
    }

    static boolean requiresCausalActionForEntityReplay(WorldMutationSource source) {
        return source == WorldMutationSource.EXPLOSION || source == WorldMutationSource.MOB;
    }

    private static boolean isSpreadingFalloutSource(WorldMutationSource source) {
        return source == WorldMutationSource.FLUID || source == WorldMutationSource.FALLING_BLOCK;
    }

    private static boolean isSpreadingFalloutChange(StoredBlockChange change) {
        return change != null
                && (isFluidState(change.oldValue())
                || isFluidState(change.newValue())
                || isFallingBlockState(change.oldValue())
                || isFallingBlockState(change.newValue()));
    }

    private static boolean isFluidState(StatePayload payload) {
        if (payload == null) {
            return false;
        }
        String blockId = payload.blockId();
        return "minecraft:water".equals(blockId) || "minecraft:lava".equals(blockId);
    }

    private static boolean isFallingBlockState(StatePayload payload) {
        return payload != null && FALLING_BLOCK_IDS.contains(payload.blockId());
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
