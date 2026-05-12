package io.github.luma.minecraft.world;

import java.util.Set;

final class WorldApplyOperationProfile {

    private static final Set<String> HISTORY_FAST_LABELS = Set.of(
            "restore-version",
            "partial-restore",
            "recovery",
            "quick-rollback",
            "undo-action",
            "redo-action",
            "merge-variant",
            "light-refresh"
    );

    WorldApplyProfile profileFor(String label) {
        if (label != null && label.startsWith("bulk-diagnostic-")) {
            return WorldApplyProfile.DIAGNOSTIC_TURBO;
        }
        if (label != null && HISTORY_FAST_LABELS.contains(label)) {
            return WorldApplyProfile.HISTORY_FAST;
        }
        return WorldApplyProfile.NORMAL;
    }
}
