package io.github.luma.minecraft.bootstrap;

public record WorldInitialBackupProgress(
        int completedChunks,
        int totalChunks,
        int backedUpChunks,
        long compressedBytes,
        String currentDimensionId
) {

    public WorldInitialBackupProgress {
        completedChunks = Math.max(0, completedChunks);
        totalChunks = Math.max(0, totalChunks);
        backedUpChunks = Math.max(0, backedUpChunks);
        compressedBytes = Math.max(0L, compressedBytes);
        currentDimensionId = currentDimensionId == null ? "" : currentDimensionId;
    }

    public double fraction() {
        if (this.totalChunks <= 0) {
            return 0.0D;
        }
        return Math.min(1.0D, Math.max(0.0D, (double) this.completedChunks / (double) this.totalChunks));
    }
}
