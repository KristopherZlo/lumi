package io.github.luma.minecraft.world;

/**
 * Hook for chunk-batch preprocessing and postprocessing.
 */
public interface BatchProcessor {

    BatchProcessor NO_OP = new BatchProcessor() {
    };

    default ChunkBatch processSet(ChunkBatch batch) {
        return batch;
    }

    default void postProcessSet(ChunkBatch batch) {
    }
}
