package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class WorldLightUpdateQueue {

    private static final int[][] NEIGHBOR_OFFSETS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

    private final LongSet exactPositions = new LongOpenHashSet();
    private final LongSet surfaceCandidatePositions = new LongOpenHashSet();
    private final LongArrayList positions = new LongArrayList();
    private int nextIndex = 0;
    private boolean drainPrepared = false;

    void add(SectionLightUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (BlockPos pos : batch.exactPositions()) {
            this.exactPositions.add(pos.asLong());
        }
        for (BlockPos pos : batch.surfaceCandidatePositions()) {
            this.surfaceCandidatePositions.add(pos.asLong());
        }
    }

    boolean hasPending() {
        if (!this.drainPrepared) {
            return !this.exactPositions.isEmpty() || !this.surfaceCandidatePositions.isEmpty();
        }
        return this.nextIndex < this.positions.size();
    }

    int pendingCount() {
        if (this.drainPrepared) {
            return Math.max(0, this.positions.size() - this.nextIndex);
        }
        return this.exactPositions.size() + this.surfaceCandidatePositions.size();
    }

    int drain(ServerLevel level, int maxChecks, long deadlineNanos) {
        if (level == null || maxChecks <= 0 || !this.hasPending()) {
            return 0;
        }

        this.prepareDrainPositions();
        int applied = 0;
        while (this.hasPending() && applied < maxChecks && System.nanoTime() < deadlineNanos) {
            long packedPos = this.positions.getLong(this.nextIndex);
            level.getLightEngine().checkBlock(new BlockPos(
                    BlockPos.getX(packedPos),
                    BlockPos.getY(packedPos),
                    BlockPos.getZ(packedPos)
            ));
            this.nextIndex += 1;
            applied += 1;
        }
        this.clearIfComplete();
        return applied;
    }

    void prepareDrainPositions() {
        if (this.drainPrepared) {
            return;
        }

        LongSet selected = new LongOpenHashSet(this.exactPositions);
        for (long packedPos : this.surfaceCandidatePositions) {
            if (this.isSurfaceCandidate(packedPos)) {
                selected.add(packedPos);
            }
        }
        this.positions.clear();
        this.positions.addAll(selected);
        this.nextIndex = 0;
        this.drainPrepared = true;
    }

    private boolean isSurfaceCandidate(long packedPos) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            if (!this.surfaceCandidatePositions.contains(BlockPos.asLong(
                    x + offset[0],
                    y + offset[1],
                    z + offset[2]
            ))) {
                return true;
            }
        }
        return false;
    }

    private void clearIfComplete() {
        if (!this.hasPending()) {
            this.exactPositions.clear();
            this.surfaceCandidatePositions.clear();
            this.positions.clear();
            this.nextIndex = 0;
            this.drainPrepared = false;
        }
    }
}
