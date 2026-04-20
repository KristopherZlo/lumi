package io.github.luma.domain.model;

import java.util.Map;

public record ExternalSourceInfo(
        String tool,
        String operationType,
        String operationLabel,
        String actor,
        Bounds3i sourceBounds,
        boolean usedClipboard,
        boolean usedSelection,
        Map<String, String> metadata
) {

    public static ExternalSourceInfo manual() {
        return new ExternalSourceInfo("MANUAL", "manual", "Manual Save", "", null, false, false, Map.of());
    }
}
