package io.github.luma.minecraft.world;

/**
 * Runtime hook for undo/history side effects produced during chunk commits.
 */
public interface HistoryStore {

    HistoryStore NO_OP = batch -> {
    };

    void record(ChunkBatch batch);
}
