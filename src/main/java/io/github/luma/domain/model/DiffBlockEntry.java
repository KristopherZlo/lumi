package io.github.luma.domain.model;

public record DiffBlockEntry(
        BlockPoint pos,
        String leftState,
        String rightState,
        ChangeType changeType
) {
}
