package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import java.util.Arrays;
import java.util.Objects;

/** Exact changed cells retained only until the enclosing chunk is synchronized. */
public final class SectionApplyResult {
    private final SectionKey key;
    private final short[] changedCells;
    private final boolean blockEntitiesChanged;

    public SectionApplyResult(SectionKey key, short[] changedCells, int changedCount) {
        this(key, changedCells, changedCount, false);
    }

    public SectionApplyResult(
            SectionKey key,
            short[] changedCells,
            int changedCount,
            boolean blockEntitiesChanged) {
        this.key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(changedCells, "changedCells");
        if (changedCount < 0 || changedCount > changedCells.length) {
            throw new IllegalArgumentException("Invalid changed cell count");
        }
        this.changedCells = Arrays.copyOf(changedCells, changedCount);
        this.blockEntitiesChanged = blockEntitiesChanged;
    }

    public SectionKey key() {
        return key;
    }

    public int changedCount() {
        return changedCells.length;
    }

    public boolean blockEntitiesChanged() {
        return blockEntitiesChanged;
    }

    short[] changedCells() {
        return changedCells;
    }
}
