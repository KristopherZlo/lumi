package io.github.luma.domain.model;

public record PendingChangeSummary(
        int addedBlocks,
        int removedBlocks,
        int changedBlocks,
        int changedEntities
) {

    public PendingChangeSummary(int addedBlocks, int removedBlocks, int changedBlocks) {
        this(addedBlocks, removedBlocks, changedBlocks, 0);
    }

    public static PendingChangeSummary empty() {
        return new PendingChangeSummary(0, 0, 0, 0);
    }

    public int total() {
        return this.addedBlocks + this.removedBlocks + this.changedBlocks + this.changedEntities;
    }

    public boolean isEmpty() {
        return this.total() == 0;
    }
}
