package io.github.luma.minecraft.world;

import java.util.List;

public record PreparedApplyOperation(
        LocalQueue localQueue,
        CompletionAction onComplete,
        boolean completeOnServerThread
) {

    public PreparedApplyOperation(List<PreparedChunkBatch> batches, CompletionAction onComplete) {
        this(batches, onComplete, false);
    }

    public PreparedApplyOperation(
            List<PreparedChunkBatch> batches,
            CompletionAction onComplete,
            boolean completeOnServerThread
    ) {
        this(
                LocalQueue.completed(batches == null
                        ? List.of()
                        : batches.stream().map(ChunkBatch::fromPrepared).toList()),
                onComplete,
                completeOnServerThread
        );
    }

    public int totalWorkUnits() {
        return this.localQueue == null ? 0 : this.localQueue.totalWorkUnits();
    }
}
