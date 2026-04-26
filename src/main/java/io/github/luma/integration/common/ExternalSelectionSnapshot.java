package io.github.luma.integration.common;

import io.github.luma.domain.model.Bounds3i;
import java.util.Map;

public record ExternalSelectionSnapshot(
        String toolId,
        String actor,
        String dimensionId,
        Bounds3i bounds,
        boolean precise,
        Map<String, String> metadata
) {

    public ExternalSelectionSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
