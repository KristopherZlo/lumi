package io.github.luma.domain.model;

public record PendingChangeSummary(
        int addedBlocks,
        int removedBlocks,
        int changedBlocks
) {

    public static PendingChangeSummary empty() {
        return new PendingChangeSummary(0, 0, 0);
    }

    public int total() {
        return this.addedBlocks + this.removedBlocks + this.changedBlocks;
    }

    public boolean isEmpty() {
        return this.total() == 0;
    }
}
