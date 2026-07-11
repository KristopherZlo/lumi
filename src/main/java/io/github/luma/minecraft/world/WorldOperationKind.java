package io.github.luma.minecraft.world;

/** Defines execution guarantees shared by operations with the same workload. */
enum WorldOperationKind {
    SAVE(WorldApplyProfile.NORMAL, false, true, false),
    HISTORY_APPLY(WorldApplyProfile.HISTORY_FAST, true, false, true),
    LIGHT_REFRESH(WorldApplyProfile.MAXIMUM, false, false, false),
    DIAGNOSTIC(WorldApplyProfile.DIAGNOSTIC_TURBO, false, false, false),
    OTHER(WorldApplyProfile.NORMAL, false, false, false);

    private final WorldApplyProfile profile;
    private final boolean finalVerification;
    private final boolean blocksBackgroundMutations;
    private final boolean blocksPreparedMutations;

    WorldOperationKind(
            WorldApplyProfile profile,
            boolean finalVerification,
            boolean blocksBackgroundMutations,
            boolean blocksPreparedMutations
    ) {
        this.profile = profile;
        this.finalVerification = finalVerification;
        this.blocksBackgroundMutations = blocksBackgroundMutations;
        this.blocksPreparedMutations = blocksPreparedMutations;
    }

    static WorldOperationKind fromLabel(String label) {
        if (label != null && label.startsWith("bulk-diagnostic-")) {
            return DIAGNOSTIC;
        }
        return switch (label == null ? "" : label) {
            case "save-version", "amend-version" -> SAVE;
            case "restore-version", "partial-restore", "zone-restore", "recovery", "restore-draft",
                    "quick-rollback", "merge-variant", "undo-action", "redo-action" -> HISTORY_APPLY;
            case "light-refresh" -> LIGHT_REFRESH;
            default -> OTHER;
        };
    }

    WorldApplyProfile profile() {
        return this.profile;
    }

    boolean requiresFinalVerification() {
        return this.finalVerification;
    }

    boolean blocksBackgroundMutations() {
        return this.blocksBackgroundMutations;
    }

    boolean blocksPreparedMutations() {
        return this.blocksPreparedMutations;
    }
}
