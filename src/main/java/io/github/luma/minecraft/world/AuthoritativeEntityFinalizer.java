package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/** Reasserts authoritative entity chunks after block and redstone fallout settles. */
final class AuthoritativeEntityFinalizer {

    private final EntityBatchApplyStep applyStep;
    private final Map<ChunkPoint, EntityBatch> replacements = new LinkedHashMap<>();
    private List<Map.Entry<ChunkPoint, EntityBatch>> ordered = List.of();
    private int batchIndex;
    private int operationIndex;

    AuthoritativeEntityFinalizer() {
        this(BlockChangeApplier::applyEntityBatch);
    }

    AuthoritativeEntityFinalizer(EntityBatchApplyStep applyStep) {
        this.applyStep = applyStep;
    }

    void record(ChunkBatch batch) {
        if (batch == null || !batch.entityBatch().replaceEntities()) {
            return;
        }
        this.replacements.put(batch.chunk(), batch.entityBatch());
        this.ordered = List.of();
    }

    boolean advance(
            ServerLevel level,
            int maxOperations,
            long deadlineNanos,
            WorldApplyMetrics metrics,
            DeferredWorldMutationContext replayedEntityContext
    ) {
        if (this.ordered.isEmpty() && !this.replacements.isEmpty()) {
            this.ordered = new ArrayList<>(this.replacements.entrySet());
        }
        int remaining = Math.max(1, maxOperations);
        while (this.batchIndex < this.ordered.size()
                && remaining > 0
                && System.nanoTime() < deadlineNanos) {
            Map.Entry<ChunkPoint, EntityBatch> entry = this.ordered.get(this.batchIndex);
            int processed = this.applyStep.apply(
                    level,
                    entry.getKey(),
                    entry.getValue(),
                    this.operationIndex,
                    remaining,
                    metrics,
                    replayedEntityContext
            );
            if (processed <= 0) {
                this.batchIndex += 1;
                this.operationIndex = 0;
                continue;
            }
            this.operationIndex += processed;
            remaining -= processed;
            if (this.operationIndex >= BlockChangeApplier.entityOperationCount(entry.getValue())) {
                this.batchIndex += 1;
                this.operationIndex = 0;
            }
        }
        return this.batchIndex >= this.ordered.size();
    }

    int pendingChunks() {
        return Math.max(0, this.replacements.size() - this.batchIndex);
    }

    @FunctionalInterface
    interface EntityBatchApplyStep {
        int apply(
                ServerLevel level,
                ChunkPoint chunk,
                EntityBatch batch,
                int startIndex,
                int maxEntities,
                WorldApplyMetrics metrics,
                DeferredWorldMutationContext replayedEntityContext
        );
    }
}
