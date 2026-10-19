package io.github.lumi.domain.model;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.TreeSet;

public record SectionBlob(List<String> blockStates, Map<Integer, CanonicalNbt> blockEntities,
        WorldDataVersion dataVersion, Map<Integer, String> actionBiomes) {
    public static final int BLOCK_COUNT = 16 * 16 * 16;

    public SectionBlob {
        Objects.requireNonNull(dataVersion, "dataVersion");
        Objects.requireNonNull(blockStates, "blockStates");
        blockStates = PaletteBlockStates.copyOf(blockStates);
        blockEntities = Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
        actionBiomes = Map.copyOf(actionBiomes);
        actionBiomes.forEach((cell, biome) -> {
            if (cell < 0 || cell >= 64 || biome.isBlank()) throw new IllegalArgumentException("Invalid action biome cell");
        });
        if (blockStates.size() != BLOCK_COUNT) {
            throw new IllegalArgumentException("Section must contain exactly " + BLOCK_COUNT + " block states");
        }
        if (blockEntities.keySet().stream().anyMatch(index -> index == null || index < 0 || index >= BLOCK_COUNT)) {
            throw new IllegalArgumentException("Block entity index must be within the section");
        }
    }

    public SectionBlob(List<String> states, Map<Integer, CanonicalNbt> entities, WorldDataVersion version) {
        this(states, entities, version, Map.of());
    }

    /** Only action-replay endpoints carry this overlay; ordinary Save capture does not. */
    public SectionBlob withActionBiomes(Map<Integer, String> biomes) {
        return new SectionBlob(blockStates, blockEntities, dataVersion, biomes);
    }

    public SectionBlob(List<String> blockStates, Map<Integer, CanonicalNbt> blockEntities) {
        this(blockStates, blockEntities, WorldDataVersion.LEGACY_12111);
    }

    /** Builds an immutable section without expanding its persisted palette. */
    public static SectionBlob fromPalette(
            List<String> palette,
            short[] indexes,
            Map<Integer, CanonicalNbt> blockEntities) {
        return fromPalette(palette, indexes, blockEntities, WorldDataVersion.LEGACY_12111);
    }

    public static SectionBlob fromPalette(List<String> palette, short[] indexes,
            Map<Integer, CanonicalNbt> blockEntities, WorldDataVersion dataVersion) {
        return new SectionBlob(new PaletteBlockStates(palette, indexes), blockEntities, dataVersion);
    }

    public PaletteBlockStates palette() {
        return (PaletteBlockStates) blockStates;
    }

    /** Returns each encoded state once; order is intentionally unspecified. */
    public Iterable<String> distinctBlockStates() {
        return palette().palette();
    }

    /** Conservative retained action estimate; temporary codec buffers have a separate fixed bound. */
    public long retainedActionBytes() {
        long bytes = 16_384 + blockEntities.values().stream().mapToLong(nbt -> 96L + nbt.residentByteSize()).sum();
        for (String state : distinctBlockStates()) bytes += 48L + 2L * state.length();
        for (String biome : actionBiomes.values()) bytes += 96L + 2L * biome.length();
        return bytes;
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
