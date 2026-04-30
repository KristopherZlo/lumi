package io.github.luma.domain.model;

import java.util.List;

public record PatchSectionWorldChanges(
        List<PatchSectionFrame> sectionFrames,
        List<StoredEntityChange> entityChanges
) {

    public PatchSectionWorldChanges {
        sectionFrames = sectionFrames == null ? List.of() : List.copyOf(sectionFrames);
        entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
    }
}
