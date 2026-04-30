package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class WorldLightUpdateQueue {

    private final LongArrayList positions = new LongArrayList();
    private int nextIndex = 0;

    void add(SectionLightUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (BlockPos pos : batch.positions()) {
            this.positions.add(pos.asLong());
        }
    }

    boolean hasPending() {
        return this.nextIndex < this.positions.size();
    }

    int pendingCount() {
        return Math.max(0, this.positions.size() - this.nextIndex);
    }

    int drain(ServerLevel level, int maxChecks, long deadlineNanos) {
        if (level == null || maxChecks <= 0 || !this.hasPending()) {
            return 0;
        }

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

    private void clearIfComplete() {
        if (!this.hasPending()) {
            this.positions.clear();
            this.nextIndex = 0;
        }
    }
}
