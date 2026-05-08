package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reasserts persisted block states after vanilla replay fallout has drained.
 *
 * <p>Undo, redo, and restore apply complete history states. Neighbor updates
 * that are still needed for attachment cleanup can recompute redstone-only
 * properties after the main block pass, so a final exact pass keeps the world
 * at the saved state rather than the freshly simulated one.
 */
final class ExactReplayStateQueue {

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final BlockPlacementUpdateDecider updateDecider = new BlockPlacementUpdateDecider();
    private final WorldApplyBlockUpdatePolicy updatePolicy = new WorldApplyBlockUpdatePolicy();
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final ExactReplayTargetPolicy targetPolicy = new ExactReplayTargetPolicy();
    private final Map<Long, PreparedBlockPlacement> pending = new LinkedHashMap<>();
    private final Map<Long, PreparedBlockPlacement> recordedPlacements = new LinkedHashMap<>();
    private Iterator<Map.Entry<Long, PreparedBlockPlacement>> drainIterator;
    private boolean drainPrepared = false;

    void record(ChunkBatch batch) {
        if (batch == null) {
            return;
        }
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            this.record(section);
        }
        for (SectionBatch section : batch.orderedSections()) {
            this.record(section.placements());
        }
    }

    boolean hasPending() {
        if (!this.drainPrepared) {
            return !this.pending.isEmpty();
        }
        return this.drainIterator != null && this.drainIterator.hasNext();
    }

    int pendingCount() {
        return this.pending.size();
    }

    int drain(ServerLevel level, int maxBlocks, long deadlineNanos) {
        return this.drain(level, maxBlocks, deadlineNanos, null);
    }

    int drain(ServerLevel level, int maxBlocks, long deadlineNanos, OperationHandle handle) {
        if (level == null || maxBlocks <= 0 || !this.hasPending()) {
            return 0;
        }

        this.prepareDrainPlacements();
        int applied = 0;
        while (this.hasPending() && applied < maxBlocks && System.nanoTime() < deadlineNanos) {
            PreparedBlockPlacement placement = this.drainIterator.next().getValue();
            this.applyExact(level, placement, handle);
            this.drainIterator.remove();
            applied += 1;
        }
        this.clearIfComplete();
        return applied;
    }

    List<PreparedBlockPlacement> takeRecordedPlacements() {
        List<PreparedBlockPlacement> placements = List.copyOf(this.recordedPlacements.values());
        this.recordedPlacements.clear();
        return placements;
    }

    private void record(List<PreparedBlockPlacement> placements) {
        for (PreparedBlockPlacement placement : placements == null ? List.<PreparedBlockPlacement>of() : placements) {
            this.record(placement);
        }
    }

    private void record(PreparedSectionApplyBatch section) {
        if (section == null || section.buffer() == null || section.chunk() == null) {
            return;
        }
        LumiSectionBuffer buffer = section.buffer();
        buffer.changedCells().forEachSetCell(localIndex -> {
            BlockState state = buffer.targetStateAt(localIndex);
            if (state == null) {
                return;
            }
            CompoundTag blockEntityTag = buffer.blockEntityPlan().tagAt(localIndex);
            boolean finalReplay = this.targetPolicy.requiresFinalReplay(state, blockEntityTag);
            boolean postReplayGuard = this.targetPolicy.requiresPostReplayGuard(state);
            if (!finalReplay && !postReplayGuard) {
                long packedPos = this.packedPos(section, localIndex);
                this.pending.remove(packedPos);
                this.recordedPlacements.remove(packedPos);
                return;
            }
            this.record(new PreparedBlockPlacement(
                    this.blockPos(section, localIndex),
                    state,
                    blockEntityTag
            ));
        });
    }

    private void record(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return;
        }
        BlockPos immutablePos = placement.pos().immutable();
        PreparedBlockPlacement copied = this.copy(immutablePos, placement);
        if (this.targetPolicy.requiresFinalReplay(copied)) {
            this.pending.put(immutablePos.asLong(), copied);
        } else {
            this.pending.remove(immutablePos.asLong());
        }
        if (this.targetPolicy.requiresPostReplayGuard(copied)) {
            this.recordedPlacements.put(immutablePos.asLong(), copied);
        } else {
            this.recordedPlacements.remove(immutablePos.asLong());
        }
    }

    private void applyExact(ServerLevel level, PreparedBlockPlacement placement, OperationHandle handle) {
        PersistentBlockStatePolicy.PersistentBlockState target = this.blockStatePolicy.normalize(
                placement.state(),
                placement.blockEntityTag()
        );
        BlockPos pos = placement.pos();
        BlockState currentState = level.getBlockState(pos);
        BlockState targetState = target.state();
        CompoundTag targetBlockEntityTag = target.blockEntityTag();
        if (!this.updateDecider.requiresUpdate(level, pos, currentState, targetState, targetBlockEntityTag)) {
            this.historyDebugLog.logExactReplay(handle, level, "final-pass", pos, currentState, targetState, false);
            return;
        }

        level.removeBlockEntity(pos);
        level.setBlock(pos, targetState, this.updatePolicy.placementFlags(targetState));
        if (targetBlockEntityTag != null) {
            BlockEntity blockEntity = BlockEntity.loadStatic(
                    pos,
                    targetState,
                    targetBlockEntityTag.copy(),
                    level.registryAccess()
            );
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }
        this.historyDebugLog.logExactReplay(handle, level, "final-pass", pos, currentState, targetState, true);
    }

    private void prepareDrainPlacements() {
        if (this.drainPrepared) {
            return;
        }

        this.drainIterator = this.pending.entrySet().iterator();
        this.drainPrepared = true;
    }

    private void clearIfComplete() {
        if (this.hasPending()) {
            return;
        }
        this.drainIterator = null;
        this.drainPrepared = false;
    }

    private PreparedBlockPlacement copy(BlockPos immutablePos, PreparedBlockPlacement placement) {
        return new PreparedBlockPlacement(
                immutablePos,
                placement.state(),
                placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy()
        );
    }

    private BlockPos blockPos(PreparedSectionApplyBatch section, int localIndex) {
        return new BlockPos(
                (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
        );
    }

    private long packedPos(PreparedSectionApplyBatch section, int localIndex) {
        return BlockPos.asLong(
                (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
        );
    }
}
