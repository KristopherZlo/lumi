package io.github.lumi.domain.model;

public record CommitStatistics(int sections, int entityChunks, long blocks, int entities) {
    public CommitStatistics {
        if (sections < 0 || entityChunks < 0 || blocks < 0 || entities < 0) {
            throw new IllegalArgumentException("Commit statistics cannot be negative");
        }
    }
}
