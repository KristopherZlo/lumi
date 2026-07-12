package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.time.Instant;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Attaches captured world deltas to the active action id. */
final class LiveUndoRedoActionRecorder {

    private final UndoRedoHistoryManager history = UndoRedoHistoryManager.getInstance();
    private final MutationSourcePolicy sourcePolicy = new MutationSourcePolicy();

    void recordBlock(
            TrackedProject project,
            ServerLevel level,
            StoredBlockChange change,
            Instant now
    ) {
        if (change == null || change.isNoOp()) {
            return;
        }
        this.record(project, level, List.of(change), List.of(), now);
    }

    void recordEntity(
            TrackedProject project,
            ServerLevel level,
            StoredEntityChange change,
            Instant now,
            Instant actionStartedAt
    ) {
        if (change == null || change.isNoOp() || !this.hasAction()) {
            return;
        }
        if (actionStartedAt == null) {
            this.record(project, level, List.of(), List.of(change), now);
            return;
        }
        this.history.recordDelayedEntityChanges(
                project.project().id().toString(),
                level.dimension().identifier().toString(),
                WorldMutationContext.currentActionId(),
                WorldMutationContext.currentActor(),
                List.of(change),
                actionStartedAt,
                now
        );
    }

    private void record(
            TrackedProject project,
            ServerLevel level,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant now
    ) {
        if (project == null || level == null || !this.hasAction()) {
            return;
        }
        String projectId = project.project().id().toString();
        String dimensionId = level.dimension().identifier().toString();
        if (this.sourcePolicy.isExplicitRootSource(WorldMutationContext.currentSource())) {
            this.history.recordAction(
                    projectId, dimensionId, WorldMutationContext.currentActionId(),
                    WorldMutationContext.currentActor(), blocks, entities, now
            );
        } else {
            this.history.recordCurrentCausalAction(
                    projectId, dimensionId, WorldMutationContext.currentActionId(),
                    WorldMutationContext.currentActor(), blocks, entities, now
            );
        }
    }

    private boolean hasAction() {
        String actionId = WorldMutationContext.currentActionId();
        return actionId != null && !actionId.isBlank();
    }
}
