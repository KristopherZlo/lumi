package io.github.luma.domain.model;

public record MaterialDeltaEntry(
        String blockId,
        int leftCount,
        int rightCount
) {

    public int delta() {
        return this.rightCount - this.leftCount;
    }
}
