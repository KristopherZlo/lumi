package io.github.lumi.domain.model;

import java.util.AbstractList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;

public record SectionBlob(List<String> blockStates, Map<Integer, CanonicalNbt> blockEntities) {
    public static final int BLOCK_COUNT = 16 * 16 * 16;

    public SectionBlob {
        Objects.requireNonNull(blockStates, "blockStates");
        blockStates = blockStates instanceof PaletteBlockStates
                ? blockStates : List.copyOf(blockStates);
        blockEntities = Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
        if (blockStates.size() != BLOCK_COUNT) {
            throw new IllegalArgumentException("Section must contain exactly " + BLOCK_COUNT + " block states");
        }
        if (blockEntities.keySet().stream().anyMatch(index -> index == null || index < 0 || index >= BLOCK_COUNT)) {
            throw new IllegalArgumentException("Block entity index must be within the section");
        }
    }

    /** Builds an immutable section without expanding its persisted palette. */
    public static SectionBlob fromPalette(
            List<String> palette,
            short[] indexes,
            Map<Integer, CanonicalNbt> blockEntities) {
        return new SectionBlob(new PaletteBlockStates(palette, indexes), blockEntities);
    }

    /** Returns each encoded state once; order is intentionally unspecified. */
    public Iterable<String> distinctBlockStates() {
        return blockStates instanceof PaletteBlockStates states
                ? states.distinct : new HashSet<>(blockStates);
    }

    private static final class PaletteBlockStates extends AbstractList<String>
            implements RandomAccess {
        private final List<String> palette;
        private final short[] indexes;
        private final List<String> distinct;

        private PaletteBlockStates(List<String> palette, short[] indexes) {
            this.palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
            this.indexes = Objects.requireNonNull(indexes, "indexes").clone();
            if (this.palette.isEmpty() || this.palette.size() > BLOCK_COUNT
                    || this.indexes.length != BLOCK_COUNT) {
                throw new IllegalArgumentException("Invalid section palette");
            }
            var used = new LinkedHashSet<String>();
            for (short index : this.indexes) {
                if (Short.toUnsignedInt(index) >= this.palette.size()) {
                    throw new IllegalArgumentException("Block state references missing palette entry");
                }
                used.add(this.palette.get(Short.toUnsignedInt(index)));
            }
            distinct = List.copyOf(used);
        }

        @Override
        public String get(int index) {
            return palette.get(Short.toUnsignedInt(indexes[index]));
        }

        @Override
        public int size() {
            return BLOCK_COUNT;
        }
    }
}
