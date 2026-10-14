package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;

public record BlockSnapshot(String blockState, Optional<CanonicalNbt> blockEntity) {
    public BlockSnapshot {
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(blockEntity, "blockEntity");
        if (blockState.isBlank()) {
            throw new IllegalArgumentException("blockState must not be blank");
        }
    }
}
