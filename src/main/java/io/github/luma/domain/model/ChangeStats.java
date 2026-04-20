package io.github.luma.domain.model;

public record ChangeStats(
        int changedBlocks,
        int changedChunks,
        int distinctBlockTypes
) {

    public static ChangeStats empty() {
        return new ChangeStats(0, 0, 0);
    }
}
