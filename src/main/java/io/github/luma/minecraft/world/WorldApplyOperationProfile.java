package io.github.luma.minecraft.world;

import java.util.Set;

final class WorldApplyOperationProfile {

    private static final Set<String> HISTORY_FAST_LABELS = Set.of(
            "restore-version",
            "partial-restore",
            "zone-restore",
            "recovery",
            "restore-draft",
            "quick-rollback",
            "undo-action",
            "redo-action",
            "merge-variant"
    );
    private static final Set<String> MAXIMUM_LABELS = Set.of(
            "light-refresh"
    );
    private static final Set<String> SAVE_LABELS = Set.of(
            "save-version",
            "amend-version"
    );

    WorldApplyProfile profileFor(String label) {
        if (label != null && label.startsWith("bulk-diagnostic-")) {
            return WorldApplyProfile.DIAGNOSTIC_TURBO;
        }
        if (label != null && HISTORY_FAST_LABELS.contains(label)) {
            return WorldApplyProfile.HISTORY_FAST;
        }
        if (label != null && MAXIMUM_LABELS.contains(label)) {
            return WorldApplyProfile.MAXIMUM;
        }
        return WorldApplyProfile.NORMAL;
    }

    boolean requiresPostApplyVerification(String label) {
        return label != null && HISTORY_FAST_LABELS.contains(label);
    }

    boolean blocksWorldMutations(String label) {
        return blocksBackgroundWorldMutations(label) || blocksPreparedWorldMutations(label);
    }

    static boolean blocksBackgroundWorldMutations(String label) {
        return label != null && SAVE_LABELS.contains(label);
    }

    static boolean blocksPreparedWorldMutations(String label) {
        return label != null && HISTORY_FAST_LABELS.contains(label);
    }
}
