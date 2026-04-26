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

    public static ExternalSourceInfo recovery() {
        return new ExternalSourceInfo("SYSTEM", "recovery", "Recovery Draft", "", null, false, false, Map.of());
    }

    public static ExternalSourceInfo restore() {
        return new ExternalSourceInfo("SYSTEM", "restore", "Restore", "", null, false, false, Map.of());
    }

    public static ExternalSourceInfo worldEdit(
            String operationType,
            String operationLabel,
            String actor,
            Bounds3i sourceBounds,
            boolean usedClipboard,
            boolean usedSelection,
            Map<String, String> metadata
    ) {
        return external("WORLDEDIT", operationType, operationLabel, actor, sourceBounds, usedClipboard, usedSelection, metadata);
    }

    public static ExternalSourceInfo axiom(
            String operationType,
            String operationLabel,
            String actor,
            Bounds3i sourceBounds,
            boolean usedClipboard,
            boolean usedSelection,
            Map<String, String> metadata
    ) {
        return external("AXIOM", operationType, operationLabel, actor, sourceBounds, usedClipboard, usedSelection, metadata);
    }

    public static ExternalSourceInfo external(
            String tool,
            String operationType,
            String operationLabel,
            String actor,
            Bounds3i sourceBounds,
            boolean usedClipboard,
            boolean usedSelection,
            Map<String, String> metadata
    ) {
        return new ExternalSourceInfo(
                tool,
                operationType,
                operationLabel,
                actor,
                sourceBounds,
                usedClipboard,
                usedSelection,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }
}
