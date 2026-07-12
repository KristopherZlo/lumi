package io.github.luma.minecraft.world;

import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import java.util.List;

public record PreparedApplyOperation(
        LocalQueue localQueue,
        CompletionAction onComplete,
        boolean completeOnServerThread,
        DeferredWorldMutationContext replayedEntityContext
) {

    public PreparedApplyOperation(
            LocalQueue localQueue,
            CompletionAction onComplete,
            boolean completeOnServerThread
    ) {
        this(localQueue, onComplete, completeOnServerThread, null);
    }

    public PreparedApplyOperation(List<PreparedChunkBatch> batches, CompletionAction onComplete) {
        this(batches, onComplete, false);
    }

    public PreparedApplyOperation(
            List<PreparedChunkBatch> batches,
            CompletionAction onComplete,
            boolean completeOnServerThread
    ) {
        this(batches, onComplete, completeOnServerThread, null);
    }

    public PreparedApplyOperation(
            List<PreparedChunkBatch> batches,
            CompletionAction onComplete,
            boolean completeOnServerThread,
            DeferredWorldMutationContext replayedEntityContext
    ) {
        this(
                LocalQueue.completed(batches == null
                        ? List.of()
                        : batches.stream().map(ChunkBatch::fromPrepared).toList()),
                onComplete,
                completeOnServerThread,
                replayedEntityContext
        );
    }

    public int totalWorkUnits() {
        return this.localQueue == null ? 0 : this.localQueue.totalWorkUnits();
    }
}
