package io.github.lumi.storage.repository;

/** Counts old unreachable items without changing repository state. */
public record GarbageCollectionInspection(int commits, int objects) {
    public GarbageCollectionInspection {
        if (commits < 0 || objects < 0) {
            throw new IllegalArgumentException("Garbage counts cannot be negative");
        }
    }
}
