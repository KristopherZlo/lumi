package io.github.luma.domain.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

public record SnapshotSectionData(
        int sectionY,
        List<CompoundTag> palette,
        int bitsPerEntry,
        long[] packedStorage,
        ContentRef contentRef
) {

    public SnapshotSectionData(int sectionY, List<CompoundTag> palette, short[] paletteIndexes) {
        this(sectionY, palette, paletteIndexes, null);
    }

    public SnapshotSectionData(
            int sectionY,
            List<CompoundTag> palette,
            short[] paletteIndexes,
            ContentRef contentRef
    ) {
        this(
                sectionY,
                palette,
                bitsPerEntry(palette),
                packPaletteIndexes(paletteIndexes, bitsPerEntry(palette)),
                contentRef
        );
    }

    public SnapshotSectionData(int sectionY, List<CompoundTag> palette, int bitsPerEntry, long[] packedStorage) {
        this(sectionY, palette, bitsPerEntry, packedStorage, null);
    }

    public SnapshotSectionData {
        palette = copyPalette(palette);
        packedStorage = packedStorage == null ? new long[0] : packedStorage.clone();
        if (palette.size() <= 1) {
            bitsPerEntry = 0;
            packedStorage = new long[0];
        }
    }

    @Override
    public long[] packedStorage() {
        return this.packedStorage.clone();
    }

    public int paletteIndexAt(int localX, int localY, int localZ) {
        return this.paletteIndexAt(SectionChangeMask.localIndex(localX, localY, localZ));
    }

    public int paletteIndexAt(int localIndex) {
        if (localIndex < 0 || localIndex >= SectionChangeMask.ENTRY_COUNT || this.palette.isEmpty()) {
            return -1;
        }
        if (this.bitsPerEntry <= 0 || this.packedStorage.length == 0) {
            return 0;
        }
        int valuesPerLong = valuesPerLong(this.bitsPerEntry);
        int storageIndex = localIndex / valuesPerLong;
        if (storageIndex < 0 || storageIndex >= this.packedStorage.length) {
            return -1;
        }
        int bitOffset = (localIndex - storageIndex * valuesPerLong) * this.bitsPerEntry;
        long mask = (1L << this.bitsPerEntry) - 1L;
        return (int) ((this.packedStorage[storageIndex] >>> bitOffset) & mask);
    }

    public short[] paletteIndexes() {
        short[] indexes = new short[SectionChangeMask.ENTRY_COUNT];
        for (int index = 0; index < indexes.length; index++) {
            indexes[index] = (short) this.paletteIndexAt(index);
        }
        return indexes;
    }

    public static int packedLongCount(int bitsPerEntry) {
        if (bitsPerEntry <= 0) {
            return 0;
        }
        int valuesPerLong = valuesPerLong(bitsPerEntry);
        return (SectionChangeMask.ENTRY_COUNT + valuesPerLong - 1) / valuesPerLong;
    }

    private static long[] packPaletteIndexes(short[] paletteIndexes, int bitsPerEntry) {
        if (bitsPerEntry <= 0) {
            return new long[0];
        }
        long[] packed = new long[packedLongCount(bitsPerEntry)];
        short[] indexes = paletteIndexes == null ? new short[0] : paletteIndexes;
        long mask = (1L << bitsPerEntry) - 1L;
        int valuesPerLong = valuesPerLong(bitsPerEntry);
        for (int index = 0; index < SectionChangeMask.ENTRY_COUNT; index++) {
            long value = (index < indexes.length ? indexes[index] : 0) & mask;
            int storageIndex = index / valuesPerLong;
            int bitOffset = (index - storageIndex * valuesPerLong) * bitsPerEntry;
            packed[storageIndex] |= value << bitOffset;
        }
        return packed;
    }

    private static int bitsPerEntry(List<CompoundTag> palette) {
        int paletteSize = palette == null ? 0 : palette.size();
        if (paletteSize <= 1) {
            return 0;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static int valuesPerLong(int bitsPerEntry) {
        return Math.max(1, Long.SIZE / bitsPerEntry);
    }

    private static List<CompoundTag> copyPalette(List<CompoundTag> palette) {
        if (palette == null || palette.isEmpty()) {
            return List.of();
        }
        return palette.stream()
                .map(tag -> tag == null ? new CompoundTag() : tag.copy())
                .toList();
    }
}
