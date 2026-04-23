package io.github.luma.gbreak.client.ui.state;

public record FakeCommitEntry(
        String id,
        String title,
        String author,
        String createdAt,
        String variantName,
        String kindLabel,
        int changedBlocks,
        int changedChunks,
        boolean latest
) {
}
