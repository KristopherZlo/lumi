package io.github.luma.minecraft.world;

import java.util.BitSet;
import java.util.List;

/**
 * One prepared chunk section containing block placements and a changed-cell
 * mask.
 */
public record SectionBatch(
        int sectionY,
        BitSet changedCells,
        List<PreparedBlockPlacement> placements
) {

    public int placementCount() {
        return this.placements == null ? 0 : this.placements.size();
    }
}
