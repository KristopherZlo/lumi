package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import java.util.Arrays;
import java.util.Objects;

/** Exact changed cells retained only until the enclosing chunk is synchronized. */
public final class SectionApplyResult {
    private final SectionKey key;
    private final short[] changedCells;
    private final boolean blockEntitiesChanged;
    private final short[] lightColumns;
    private final int lightChangeCount;

    public SectionApplyResult(SectionKey key, short[] changedCells, int changedCount) {
        this(key, changedCells, changedCount, false, new short[256]);
    }

    public SectionApplyResult(
            SectionKey key,
            short[] changedCells,
            int changedCount,
            boolean blockEntitiesChanged) {
        this(key, changedCells, changedCount, blockEntitiesChanged,
                new short[256]);
    }

    SectionApplyResult(
            SectionKey key,
            short[] changedCells,
            int changedCount,
            boolean blockEntitiesChanged,
            short[] lightColumns) {
        this.key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(changedCells, "changedCells");
        Objects.requireNonNull(lightColumns, "lightColumns");
        if (changedCount < 0 || changedCount > changedCells.length) {
            throw new IllegalArgumentException("Invalid changed cell count");
        }
        if (lightColumns.length != 256) {
            throw new IllegalArgumentException("Light columns must contain 256 masks");
        }
        this.changedCells = Arrays.copyOf(changedCells, changedCount);
        this.blockEntitiesChanged = blockEntitiesChanged;
        this.lightColumns = lightColumns.clone();
        int checks = 0;
        for (short mask : this.lightColumns) {
            checks += Integer.bitCount(mask & 0xffff);
        }
        lightChangeCount = checks;
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

    public boolean lightChanged() {
        return lightChangeCount != 0;
    }

    short[] changedCells() {
        return changedCells;
    }

    short[] lightColumns() {
        return lightColumns;
    }

    int lightChangeCount() {
        return lightChangeCount;
    }
}
