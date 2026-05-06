package io.github.luma.minecraft.world;

import java.util.ArrayList;
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
    private final Map<Long, PreparedBlockPlacement> pending = new LinkedHashMap<>();
    private final Map<Long, PreparedBlockPlacement> recordedPlacements = new LinkedHashMap<>();
    private List<PreparedBlockPlacement> drainPlacements = List.of();
    private int nextIndex = 0;
    private boolean drainPrepared = false;

    void record(ChunkBatch batch) {
        if (batch == null) {
            return;
        }
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            this.record(section.toPlacements());
        }
        for (SectionBatch section : batch.orderedSections()) {
            this.record(section.placements());
        }
    }

    boolean hasPending() {
        if (!this.drainPrepared) {
            return !this.pending.isEmpty();
        }
        return this.nextIndex < this.drainPlacements.size();
    }

    int pendingCount() {
        if (this.drainPrepared) {
            return Math.max(0, this.drainPlacements.size() - this.nextIndex);
        }
        return this.pending.size();
    }

    int drain(ServerLevel level, int maxBlocks, long deadlineNanos) {
        if (level == null || maxBlocks <= 0 || !this.hasPending()) {
            return 0;
        }

        this.prepareDrainPlacements();
        int applied = 0;
        while (this.hasPending() && applied < maxBlocks && System.nanoTime() < deadlineNanos) {
            this.applyExact(level, this.drainPlacements.get(this.nextIndex));
            this.nextIndex += 1;
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
            if (placement == null || placement.pos() == null || placement.state() == null) {
                continue;
            }
            BlockPos immutablePos = placement.pos().immutable();
            this.pending.put(
                    immutablePos.asLong(),
                    new PreparedBlockPlacement(
                            immutablePos,
                            placement.state(),
                            placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy()
                    )
            );
            this.recordedPlacements.put(
                    immutablePos.asLong(),
                    new PreparedBlockPlacement(
                            immutablePos,
                            placement.state(),
                            placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy()
                    )
            );
        }
    }

    private void applyExact(ServerLevel level, PreparedBlockPlacement placement) {
        PersistentBlockStatePolicy.PersistentBlockState target = this.blockStatePolicy.normalize(
                placement.state(),
                placement.blockEntityTag()
        );
        BlockPos pos = placement.pos();
        BlockState currentState = level.getBlockState(pos);
        BlockState targetState = target.state();
        CompoundTag targetBlockEntityTag = target.blockEntityTag();
        if (!this.updateDecider.requiresUpdate(level, pos, currentState, targetState, targetBlockEntityTag)) {
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
    }

    private void prepareDrainPlacements() {
        if (this.drainPrepared) {
            return;
        }

        this.drainPlacements = List.copyOf(new ArrayList<>(this.pending.values()));
        this.nextIndex = 0;
        this.drainPrepared = true;
    }

    private void clearIfComplete() {
        if (this.hasPending()) {
            return;
        }
        this.pending.clear();
        this.drainPlacements = List.of();
        this.nextIndex = 0;
        this.drainPrepared = false;
    }
}
