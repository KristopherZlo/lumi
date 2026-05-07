package io.github.luma.minecraft.debug;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.ChunkBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedSectionApplyBatch;
import io.github.luma.minecraft.world.SectionBatch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Formats focused history diagnostics without making normal gameplay logs noisy.
 */
public final class HistoryDebugLog {

    private static final int ACTION_SAMPLE_LIMIT = 12;
    private static final int BATCH_SAMPLE_LIMIT = 16;
    private static final String[] TRACE_ID_FRAGMENTS = {"piston", "observer", "redstone", "repeater", "comparator",
            "lever", "button", "pressure_plate", "tripwire", "target"};

    public void logUndoRedoSelection(BuildProject project, String direction, UndoRedoAction action,
            List<StoredBlockChange> targetChanges, List<StoredEntityChange> targetEntityChanges,
            List<StoredBlockChange> pendingAdjustments, List<StoredEntityChange> pendingEntityAdjustments
    ) {
        if (!LumaDebugLog.enabled(project) || action == null) {
            return;
        }
        LumaDebugLog.log(project, "history-action",
                "Selected {} action id={} actor={} blocks={} entities={} adjustmentBlocks={} adjustmentEntities={} targetSample=[{}]",
                direction, action.id(), action.actor(), size(targetChanges), size(targetEntityChanges),
                size(pendingAdjustments), size(pendingEntityAdjustments), this.sampleChanges(targetChanges));
    }

    public void logLiveUndoRedoBlock(BuildProject project, String route, String actionId, WorldMutationSource source,
            StoredBlockChange change) {
        if (LumaDebugLog.enabled(project) && change != null) {
            LumaDebugLog.log(project, "history-action", "Recorded live block route={} source={} action={} actor={} change={}",
                    route, source, blank(actionId), WorldMutationContext.currentActor(), this.describe(change));
        }
    }

    public void logCapturedBlock(BuildProject project, String route, WorldMutationSource source, BlockPos pos,
            BlockState oldState, BlockState newState, int pendingBefore, int pendingAfter
    ) {
        if (LumaDebugLog.enabled(project)) {
            LumaDebugLog.log(project, "capture-block", "Accepted route={} source={} action={} actor={} pos={} {} -> {} pending={}->{}",
                    route, source, blank(WorldMutationContext.currentActionId()), WorldMutationContext.currentActor(),
                    this.format(pos), this.describe(oldState), this.describe(newState), pendingBefore, pendingAfter);
        }
    }

    public void logDeferredBlock(BuildProject project, WorldMutationSource source, BlockPos pos, BlockState oldState,
            BlockState liveState, int pendingSize) {
        if (LumaDebugLog.enabled(project)) {
            LumaDebugLog.log(project, "capture-block", "Deferred route=stabilization source={} action={} actor={} pos={} old={} live={} pending={}",
                    source, blank(WorldMutationContext.currentActionId()), WorldMutationContext.currentActor(),
                    this.format(pos), this.describe(oldState), this.describe(liveState), pendingSize);
        }
    }

    public void logSkippedDeferredBlock(BuildProject project, WorldMutationSource source, BlockPos pos,
            BlockState oldState, BlockState newState, String reason) {
        if (LumaDebugLog.enabled(project)) {
            LumaDebugLog.log(project, "capture-block", "Skipped deferred source={} action={} actor={} pos={} reason={} {} -> {}",
                    source, blank(WorldMutationContext.currentActionId()), WorldMutationContext.currentActor(),
                    this.format(pos), reason == null ? "unknown" : reason, this.describe(oldState), this.describe(newState));
        }
    }

    public void logReplayBatch(OperationHandle handle, ChunkBatch batch) {
        if (!LumaDebugLog.enabled(handle) || batch == null) {
            return;
        }
        List<String> sample = this.sampleMechanismPlacements(batch);
        if (!sample.isEmpty()) {
            LumaDebugLog.log(handle, "mechanism-replay", "Replay batch chunk={}:{} mechanismTargets=[{}]",
                    batch.chunk().x(), batch.chunk().z(), String.join("; ", sample));
        }
    }

    public void logExactReplay(OperationHandle handle, ServerLevel level, String phase, BlockPos pos,
            BlockState currentState, BlockState targetState, boolean applied
    ) {
        if (!LumaDebugLog.enabled(handle)
                || !applied && !this.shouldTrace(currentState) && !this.shouldTrace(targetState)) {
            return;
        }
        LumaDebugLog.log(handle, "mechanism-replay", "Exact replay phase={} time={} pos={} applied={} current={} target={}",
                phase, time(level), this.format(pos), applied, this.describe(currentState), this.describe(targetState));
    }

    public void logExactGuard(ServerLevel level, int exactStates, int callbackPositions, int ticks) {
        if (LumaDebugLog.globalEnabled() && (exactStates > 0 || callbackPositions > 0)) {
            LumaDebugLog.log("mechanism-replay", "Registered replay guard time={} exactStates={} callbackPositions={} ticks={}",
                    time(level), exactStates, callbackPositions, ticks);
        }
    }

    public void logSuppressedCallback(String callback, ServerLevel level, BlockPos pos, BlockState state, String detail) {
        if (LumaDebugLog.globalEnabled()) {
            LumaDebugLog.log("mechanism-callback", "Suppressed {} time={} source={} action={} pos={} state={} {}",
                    callback, time(level), WorldMutationContext.currentSource(), blank(WorldMutationContext.currentActionId()),
                    this.format(pos), this.describe(state), detail == null ? "" : detail);
        }
    }

    public void logRedstoneReplayPlan(BlockPos pos, BlockState currentState, BlockState targetState, int updateCount, boolean queued) {
        if (LumaDebugLog.globalEnabled()) {
            LumaDebugLog.log("mechanism-replay", "Redstone replay plan pos={} updates={} queued={} current={} target={}",
                    this.format(pos), updateCount, queued, this.describe(currentState), this.describe(targetState));
        }
    }

    public void logRedstoneReplayUpdate(ServerLevel level, BlockPos pos, Object sourceBlock) {
        if (LumaDebugLog.globalEnabled()) {
            LumaDebugLog.log("mechanism-replay", "Applying redstone replay update time={} pos={} sourceBlock={}",
                    time(level), this.format(pos), sourceBlock);
        }
    }

    boolean shouldTrace(BlockState state) {
        return state != null && this.shouldTraceBlockId(this.blockId(state));
    }

    boolean shouldTrace(StoredBlockChange change) {
        return change != null && (this.shouldTrace(change.oldValue()) || this.shouldTrace(change.newValue()));
    }

    String describe(BlockState state) {
        if (state == null) {
            return "minecraft:air";
        }
        String blockId = this.blockId(state);
        if (state.getProperties().isEmpty()) {
            return blockId;
        }
        List<String> properties = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            properties.add(property.getName() + "=" + this.propertyValue(state, property));
        }
        properties.sort(Comparator.naturalOrder());
        return blockId + "[" + String.join(",", properties) + "]";
    }

    private boolean shouldTrace(StatePayload payload) {
        return payload != null && this.shouldTraceBlockId(payload.blockId());
    }

    private String sampleChanges(List<StoredBlockChange> changes) {
        List<StoredBlockChange> source = changes == null ? List.of() : changes;
        List<String> samples = source.stream().filter(this::shouldTrace).limit(ACTION_SAMPLE_LIMIT).map(this::describe).toList();
        if (samples.isEmpty()) {
            samples = source.stream().limit(ACTION_SAMPLE_LIMIT).map(this::describe).toList();
        }
        return String.join("; ", samples);
    }

    private List<String> sampleMechanismPlacements(ChunkBatch batch) {
        List<String> sample = new ArrayList<>();
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            this.addPlacementSamples(sample, section.toPlacements());
        }
        for (SectionBatch section : batch.orderedSections()) {
            this.addPlacementSamples(sample, section.placements());
        }
        return List.copyOf(sample);
    }

    private void addPlacementSamples(List<String> sample, List<PreparedBlockPlacement> placements) {
        if (sample.size() >= BATCH_SAMPLE_LIMIT) {
            return;
        }
        for (PreparedBlockPlacement placement : placements == null ? List.<PreparedBlockPlacement>of() : placements) {
            if (placement != null && placement.pos() != null && this.shouldTrace(placement.state())) {
                sample.add(this.format(placement.pos()) + "=" + this.describe(placement.state()));
            }
            if (sample.size() >= BATCH_SAMPLE_LIMIT) {
                return;
            }
        }
    }

    private String describe(StoredBlockChange change) {
        return change == null
                ? "<none>"
                : this.format(change.pos()) + " " + this.describe(change.oldValue()) + " -> " + this.describe(change.newValue());
    }

    private String describe(StatePayload payload) {
        if (payload == null) {
            return "minecraft:air";
        }
        String suffix = payload.blockEntityTag() == null ? "" : "+be";
        return this.shouldTraceBlockId(payload.blockId()) ? payload.toStateSnbt() + suffix : payload.blockId() + suffix;
    }

    private boolean shouldTraceBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        for (String fragment : TRACE_ID_FRAGMENTS) {
            if (blockId.contains(fragment)) {
                return true;
            }
        }
        return blockId.equals("minecraft:dispenser")
                || blockId.equals("minecraft:dropper")
                || blockId.equals("minecraft:slime_block")
                || blockId.equals("minecraft:honey_block");
    }

    private String blockId(BlockState state) {
        return state == null || state.is(Blocks.AIR) ? "minecraft:air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private String format(BlockPoint point) {
        return point == null ? "unknown" : point.x() + "," + point.y() + "," + point.z();
    }

    private String format(BlockPos pos) {
        return pos == null ? "unknown" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String propertyValue(BlockState state, Property<?> property) { return this.propertyValueUnchecked(state, property); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String propertyValueUnchecked(BlockState state, Property property) {
        return property.getName((Comparable) state.getValue(property)).toLowerCase(Locale.ROOT);
    }

    private static int size(List<?> list) { return list == null ? 0 : list.size(); }

    private static long time(ServerLevel level) { return level == null ? -1 : level.getGameTime(); }

    private static String blank(String value) { return value == null || value.isBlank() ? "<none>" : value; }
}
