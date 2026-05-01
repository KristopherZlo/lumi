package io.github.luma.ui;

import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;

/**
 * Maps stage-local operation progress into a user-facing overall percentage.
 *
 * <p>Background save steps often complete whole sub-stages at once, so the raw
 * {@code completed / total} ratio can jump to 100% long before the operation
 * actually finishes. This presenter keeps the HUD honest by assigning each
 * stage its own range in the overall progress bar.
 */
public final class OperationProgressPresenter {

    private OperationProgressPresenter() {
    }

    public static int displayPercent(OperationSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }

        int rawPercent = rawPercent(snapshot.progress());
        return switch (snapshot.stage()) {
            case QUEUED -> 0;
            case PREPARING -> stagePercent(rawPercent, 5, 45, 18);
            case PRELOADING -> stagePercent(rawPercent, 46, 59, 52);
            case WRITING -> stagePercent(rawPercent, 46, 80, 64);
            case APPLYING -> stagePercent(rawPercent, 60, 94, 72);
            case FINALIZING -> stagePercent(rawPercent, 95, 99, 97);
            case COMPLETED -> 100;
            case FAILED -> rawPercent < 0 ? 0 : Math.min(99, rawPercent);
        };
    }

    public static String progressSummary(OperationSnapshot snapshot) {
        if (snapshot == null) {
            return "0%";
        }

        OperationProgress progress = snapshot.progress();
        int percent = displayPercent(snapshot);
        if (progress.totalUnits() <= 0) {
            return percent + "%";
        }

        return progress.completedUnits()
                + " / "
                + progress.totalUnits()
                + " "
                + progress.unitLabel()
                + " ("
                + percent
                + "%)";
    }

    private static int rawPercent(OperationProgress progress) {
        if (progress == null || progress.totalUnits() <= 0) {
            return -1;
        }

        return Math.max(0, Math.min(100, (int) Math.floor(progress.fraction() * 100.0D)));
    }

    private static int stagePercent(int rawPercent, int start, int end, int fallback) {
        if (rawPercent < 0) {
            return fallback;
        }

        double fraction = Math.max(0.0D, Math.min(1.0D, rawPercent / 100.0D));
        return start + (int) Math.round((end - start) * fraction);
    }
}
