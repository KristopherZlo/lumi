package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongComparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

final class RedstoneReplayUpdateQueue {

    private final Map<UpdateKey, RedstoneReplayUpdate> pendingUpdates = new LinkedHashMap<>();
    private List<RedstoneReplayUpdate> drainUpdates = List.of();
    private int nextIndex = 0;
    private boolean drainPrepared = false;

    void add(RedstoneReplayUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (RedstoneReplayUpdate update : batch.updates()) {
            this.pendingUpdates.putIfAbsent(new UpdateKey(update.pos().asLong(), update.sourceBlock()), update);
        }
    }

    boolean hasPending() {
        if (!this.drainPrepared) {
            return !this.pendingUpdates.isEmpty();
        }
        return this.nextIndex < this.drainUpdates.size();
    }

    int pendingCount() {
        if (this.drainPrepared) {
            return Math.max(0, this.drainUpdates.size() - this.nextIndex);
        }
        return this.pendingUpdates.size();
    }

    int drain(ServerLevel level, int maxUpdates, long deadlineNanos) {
        if (level == null || maxUpdates <= 0 || !this.hasPending()) {
            return 0;
        }

        this.prepareDrainUpdates();
        int applied = 0;
        while (this.hasPending() && applied < maxUpdates && System.nanoTime() < deadlineNanos) {
            RedstoneReplayUpdate update = this.drainUpdates.get(this.nextIndex);
            level.updateNeighborsAt(update.pos(), update.sourceBlock());
            this.nextIndex += 1;
            applied += 1;
        }
        this.clearIfComplete();
        return applied;
    }

    private void prepareDrainUpdates() {
        if (this.drainPrepared) {
            return;
        }

        List<RedstoneReplayUpdate> updates = new ArrayList<>(this.pendingUpdates.values());
        updates.sort((first, second) -> LOCALITY_ORDER.compare(
                first.pos().asLong(),
                second.pos().asLong()
        ));
        this.drainUpdates = List.copyOf(updates);
        this.nextIndex = 0;
        this.drainPrepared = true;
    }

    private void clearIfComplete() {
        if (this.hasPending()) {
            return;
        }
        this.pendingUpdates.clear();
        this.drainUpdates = List.of();
        this.nextIndex = 0;
        this.drainPrepared = false;
    }

    private static final LongComparator LOCALITY_ORDER = (first, second) -> {
        int sectionComparison = Long.compare(sectionKey(first), sectionKey(second));
        return sectionComparison != 0 ? sectionComparison : Long.compare(first, second);
    };

    private static long sectionKey(long packedPos) {
        return SectionPos.asLong(
                Math.floorDiv(BlockPos.getX(packedPos), 16),
                Math.floorDiv(BlockPos.getY(packedPos), 16),
                Math.floorDiv(BlockPos.getZ(packedPos), 16)
        );
    }

    private record UpdateKey(long packedPos, Block sourceBlock) {
    }
}
