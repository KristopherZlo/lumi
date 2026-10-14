package io.github.lumi.domain.model;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.TreeSet;

public record SectionBlob(List<String> blockStates, Map<Integer, CanonicalNbt> blockEntities) {
    public static final int BLOCK_COUNT = 16 * 16 * 16;

    public SectionBlob {
        Objects.requireNonNull(blockStates, "blockStates");
        blockStates = PaletteBlockStates.copyOf(blockStates);
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

    public PaletteBlockStates palette() {
        return (PaletteBlockStates) blockStates;
    }

    /** Returns each encoded state once; order is intentionally unspecified. */
    public Iterable<String> distinctBlockStates() {
        return palette().palette();
    }

    /** Compact immutable palette with unsigned 16-bit cell indexes. */
    public static final class PaletteBlockStates extends AbstractList<String>
            implements RandomAccess {
        private final List<String> palette;
        private final short[] indexes;

        private PaletteBlockStates(List<String> palette, short[] indexes) {
            this.palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
            this.indexes = Objects.requireNonNull(indexes, "indexes").clone();
            if (this.palette.isEmpty() || this.palette.size() > BLOCK_COUNT
                    || this.indexes.length != BLOCK_COUNT) {
                throw new IllegalArgumentException("Invalid section palette");
            }
            String previous = null;
            for (String state : this.palette) {
                if (previous != null && previous.compareTo(state) >= 0) {
                    throw new IllegalArgumentException(
                            "Section palette must be in canonical order");
                }
                previous = state;
            }
            for (short index : this.indexes) {
                if (Short.toUnsignedInt(index) >= this.palette.size()) {
                    throw new IllegalArgumentException("Block state references missing palette entry");
                }
            }
        }

        private static PaletteBlockStates copyOf(List<String> states) {
            Objects.requireNonNull(states, "states");
            if (states instanceof PaletteBlockStates palette) {
                return palette;
            }
            if (states.size() != BLOCK_COUNT) {
                throw new IllegalArgumentException(
                        "Section must contain exactly " + BLOCK_COUNT + " block states");
            }
            List<String> palette = new ArrayList<>(new TreeSet<>(states));
            Map<String, Integer> paletteIndexes = new HashMap<>();
            for (int index = 0; index < palette.size(); index++) {
                paletteIndexes.put(palette.get(index), index);
            }
            short[] indexes = new short[BLOCK_COUNT];
            for (int index = 0; index < states.size(); index++) {
                indexes[index] = paletteIndexes.get(states.get(index)).shortValue();
            }
            return new PaletteBlockStates(palette, indexes);
        }

        public List<String> palette() {
            return palette;
        }

        public int paletteIndex(int blockIndex) {
            return Short.toUnsignedInt(indexes[blockIndex]);
        }

        public short[] copyIndexes() {
            return indexes.clone();
        }

        @Override
        public String get(int index) {
            return palette.get(paletteIndex(index));
        }

        @Override
        public int size() {
            return BLOCK_COUNT;
        }
    }
}
