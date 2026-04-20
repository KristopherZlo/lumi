package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;

/**
 * Prepared world-edit operation that feeds the dispatcher.
 */
public interface EditOperation {

    OperationHandle handle();

    String unitLabel();

    LocalQueue localQueue();

    BatchProcessor batchProcessor();

    HistoryStore historyStore();

    void onComplete() throws Exception;
}
