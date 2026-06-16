package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;

/**
 * Converts recoverable operation-boundary exceptions into logged failed work
 * instead of letting them escape a server tick.
 */
final class WorldOperationSafetyBoundary {

    private WorldOperationSafetyBoundary() {
    }

    static boolean run(String phase, String label, CheckedAction action) {
        if (action == null) {
            return true;
        }
        try {
            action.run();
            return true;
        } catch (Exception exception) {
            WorldApplyExceptionLogger.record(
                    phase,
                    (BlockPos) null,
                    exception,
                    label == null || label.isBlank() ? "" : "label=" + label
            );
            return false;
        }
    }

    @FunctionalInterface
    interface CheckedAction {

        void run() throws Exception;
    }
}
