package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

final class SectionLightUpdateBatch {

    private final List<BlockPos> exactPositions = new ArrayList<>();
    private final List<BlockPos> surfaceCandidatePositions = new ArrayList<>();

    void add(BlockPos pos) {
        this.addSurfaceCandidate(pos);
    }

    void addExact(BlockPos pos) {
        if (pos != null) {
            this.exactPositions.add(pos.immutable());
        }
    }

    void addSurfaceCandidate(BlockPos pos) {
        if (pos != null) {
            this.surfaceCandidatePositions.add(pos.immutable());
        }
    }

    boolean isEmpty() {
        return this.exactPositions.isEmpty() && this.surfaceCandidatePositions.isEmpty();
    }

    int size() {
        return this.exactPositions.size() + this.surfaceCandidatePositions.size();
    }

    List<BlockPos> positions() {
        List<BlockPos> positions = new ArrayList<>(this.size());
        positions.addAll(this.exactPositions);
        positions.addAll(this.surfaceCandidatePositions);
        return positions;
    }

    List<BlockPos> exactPositions() {
        return this.exactPositions;
    }

    List<BlockPos> surfaceCandidatePositions() {
        return this.surfaceCandidatePositions;
    }
}
