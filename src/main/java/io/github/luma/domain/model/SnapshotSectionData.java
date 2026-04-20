package io.github.luma.domain.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

public record SnapshotSectionData(
        int sectionY,
        List<CompoundTag> palette,
        short[] paletteIndexes
) {
}
