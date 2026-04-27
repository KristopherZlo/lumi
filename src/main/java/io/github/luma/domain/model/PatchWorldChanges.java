package io.github.luma.domain.model;

import java.util.List;

public record PatchWorldChanges(
        List<StoredBlockChange> blockChanges,
        List<StoredEntityChange> entityChanges
) {

    public PatchWorldChanges {
        blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
        entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
    }

    public boolean isEmpty() {
        return this.blockChanges.isEmpty() && this.entityChanges.isEmpty();
    }
}
