package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

final class SectionLightUpdateBatch {

    private final List<BlockPos> positions = new ArrayList<>();

    void add(BlockPos pos) {
        if (pos != null) {
            this.positions.add(pos.immutable());
        }
    }

    boolean isEmpty() {
        return this.positions.isEmpty();
    }

    int size() {
        return this.positions.size();
    }

    List<BlockPos> positions() {
        return this.positions;
    }
}
