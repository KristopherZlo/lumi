package io.github.lumi.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SectionBlob(List<String> blockStates, Map<Integer, CanonicalNbt> blockEntities) {
    public static final int BLOCK_COUNT = 16 * 16 * 16;

    public SectionBlob {
        blockStates = List.copyOf(Objects.requireNonNull(blockStates, "blockStates"));
        blockEntities = Map.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
        if (blockStates.size() != BLOCK_COUNT) {
            throw new IllegalArgumentException("Section must contain exactly " + BLOCK_COUNT + " block states");
        }
        if (blockEntities.keySet().stream().anyMatch(index -> index == null || index < 0 || index >= BLOCK_COUNT)) {
            throw new IllegalArgumentException("Block entity index must be within the section");
        }
    }
}
