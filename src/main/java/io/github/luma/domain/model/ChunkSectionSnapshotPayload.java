package io.github.luma.domain.model;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * Immutable runtime payload for one copied chunk section.
 *
 * <p>The section keeps the compact packed palette form captured from the live
 * world so the server thread can hand off snapshot persistence and later diff
 * work without walking every block position synchronously.
 */
public record ChunkSectionSnapshotPayload(
        int sectionY,
        List<CompoundTag> palette,
        long[] packedStorage,
        int bitsPerEntry
) {

    private static final int ENTRY_COUNT = 16 * 16 * 16;

    public ChunkSectionSnapshotPayload {
        palette = copyPalette(palette);
        packedStorage = packedStorage == null ? new long[0] : packedStorage.clone();
    }

    @Override
    public List<CompoundTag> palette() {
        return this.palette;
    }

    @Override
    public long[] packedStorage() {
        return this.packedStorage.clone();
    }

    public short[] unpackPaletteIndexes() {
        short[] indexes = new short[ENTRY_COUNT];
        if (this.palette.isEmpty() || this.bitsPerEntry <= 0 || this.packedStorage.length == 0) {
            return indexes;
        }

        for (int index = 0; index < ENTRY_COUNT; index++) {
            indexes[index] = (short) this.paletteIndexAtStorageIndex(index);
        }
        return indexes;
    }

    public int paletteIndexAt(int localX, int localY, int localZ) {
        if (this.palette.isEmpty() || this.bitsPerEntry <= 0 || this.packedStorage.length == 0) {
            return 0;
        }
        return this.paletteIndexAtStorageIndex(localIndex(localX, localY, localZ));
    }

    public static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private int paletteIndexAtStorageIndex(int index) {
        // Minecraft SimpleBitStorage pads each long instead of spilling values across boundaries.
        int valuesPerLong = Math.max(1, Long.SIZE / this.bitsPerEntry);
        int storageIndex = index / valuesPerLong;
        if (storageIndex < 0 || storageIndex >= this.packedStorage.length) {
            return 0;
        }
        int bitOffset = (index - storageIndex * valuesPerLong) * this.bitsPerEntry;
        long mask = this.bitsPerEntry >= Long.SIZE ? -1L : (1L << this.bitsPerEntry) - 1L;
        return (int) ((this.packedStorage[storageIndex] >>> bitOffset) & mask);
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
