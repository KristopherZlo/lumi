package io.github.luma.minecraft.world;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

final class RedstoneReplayUpdateBatch {

    private static final RedstoneReplayUpdateBatch EMPTY = new RedstoneReplayUpdateBatch(List.of());

    private final List<RedstoneReplayUpdate> updates;

    private RedstoneReplayUpdateBatch(List<RedstoneReplayUpdate> updates) {
        this.updates = updates == null ? List.of() : List.copyOf(updates);
    }

    static RedstoneReplayUpdateBatch empty() {
        return EMPTY;
    }

    static RedstoneReplayUpdateBatch of(List<RedstoneReplayUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return EMPTY;
        }
        return new RedstoneReplayUpdateBatch(updates);
    }

    boolean isEmpty() {
        return this.updates.isEmpty();
    }

    List<RedstoneReplayUpdate> updates() {
        return this.updates;
    }

    void propagate(ServerLevel level) {
        if (level == null || this.updates.isEmpty()) {
            return;
        }
        for (RedstoneReplayUpdate update : this.updates) {
            level.updateNeighborsAt(update.pos(), update.sourceBlock());
        }
    }
}

record RedstoneReplayUpdate(BlockPos pos, Block sourceBlock) {

    RedstoneReplayUpdate {
        if (pos == null) {
            throw new IllegalArgumentException("pos is required");
        }
        if (sourceBlock == null) {
            throw new IllegalArgumentException("sourceBlock is required");
        }
        pos = pos.immutable();
    }
}
