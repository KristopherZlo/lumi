package io.github.luma.domain.model;

public record PatchStats(
        int changedBlocks,
        int changedChunks
) {

    public static PatchStats empty() {
        return new PatchStats(0, 0);
    }
}
