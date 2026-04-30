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

    WorldApplyProfile profileFor(String label) {
        if (label != null && label.startsWith("bulk-diagnostic-")) {
            return WorldApplyProfile.DIAGNOSTIC_TURBO;
        }
        if (label != null && HIGH_THROUGHPUT_LABELS.contains(label)) {
            return WorldApplyProfile.HISTORY_FAST;
        }
        return WorldApplyProfile.NORMAL;
    }
}
