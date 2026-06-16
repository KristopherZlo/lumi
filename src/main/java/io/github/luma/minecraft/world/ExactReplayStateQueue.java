package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.OperationHandle;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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

    private final SafeExactReplayStateApplier exactReplayStateApplier;
    private final ExactReplayTargetPolicy targetPolicy = new ExactReplayTargetPolicy();
    private final Map<Long, PreparedBlockPlacement> pending = new LinkedHashMap<>();
    private final Map<Long, PreparedBlockPlacement> recordedPlacements = new LinkedHashMap<>();
    private Iterator<Map.Entry<Long, PreparedBlockPlacement>> drainIterator;
    private boolean drainPrepared = false;

    ExactReplayStateQueue() {
        this(new SafeExactReplayStateApplier());
    }

    ExactReplayStateQueue(SafeExactReplayStateApplier exactReplayStateApplier) {
        this.exactReplayStateApplier = exactReplayStateApplier == null
                ? new SafeExactReplayStateApplier()
                : exactReplayStateApplier;
    }

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

    boolean hasRecordedPlacements() {
        return !this.recordedPlacements.isEmpty();
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
            this.exactReplayStateApplier.apply(level, placement, handle, "final-pass");
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
            PreparedBlockPlacement placement = new PreparedBlockPlacement(
                    this.blockPos(section, localIndex),
                    state,
                    blockEntityTag,
                    buffer.replayHintAt(localIndex)
            );
            boolean finalReplay = this.targetPolicy.requiresFinalReplay(placement);
            boolean postReplayGuard = this.targetPolicy.requiresPostReplayGuard(placement);
            if (!finalReplay && !postReplayGuard) {
                long packedPos = this.packedPos(section, localIndex);
                this.pending.remove(packedPos);
                this.recordedPlacements.remove(packedPos);
                return;
            }
            this.record(placement);
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
                placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy(),
                placement.replayHint()
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
