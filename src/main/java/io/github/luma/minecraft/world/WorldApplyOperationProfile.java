package io.github.luma.minecraft.world;

import java.util.Set;

final class WorldApplyOperationProfile {

    private static final Set<String> HIGH_THROUGHPUT_LABELS = Set.of(
            "restore-version",
            "partial-restore",
            "recovery",
            "undo-action",
            "redo-action",
            "merge-variant"
    );

    boolean isHighThroughput(String label) {
        return label != null
                && (HIGH_THROUGHPUT_LABELS.contains(label) || label.startsWith("bulk-diagnostic-"));
    }
}
