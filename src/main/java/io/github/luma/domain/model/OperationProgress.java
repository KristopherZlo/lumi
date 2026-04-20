package io.github.luma.domain.model;

public record OperationProgress(
        int completedUnits,
        int totalUnits,
        String unitLabel
) {

    public static OperationProgress empty(String unitLabel) {
        return new OperationProgress(0, 0, unitLabel == null ? "items" : unitLabel);
    }

    public double fraction() {
        if (this.totalUnits <= 0) {
            return 0.0D;
        }
        return Math.min(1.0D, Math.max(0.0D, (double) this.completedUnits / (double) this.totalUnits));
    }
}
