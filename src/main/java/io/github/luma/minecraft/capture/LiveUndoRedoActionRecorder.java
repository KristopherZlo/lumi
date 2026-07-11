package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.time.Instant;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Attaches captured world deltas to the active action id. */
final class LiveUndoRedoActionRecorder {

    private final UndoRedoHistoryManager history = UndoRedoHistoryManager.getInstance();

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

    void recordBlocks(
            TrackedProject project,
            ServerLevel level,
            List<StoredBlockChange> changes,
            Instant now
    ) {
        List<StoredBlockChange> recordable = changes == null
                ? List.of()
                : changes.stream().filter(change -> change != null && !change.isNoOp()).toList();
        if (!recordable.isEmpty()) {
            this.record(project, level, recordable, List.of(), now);
        }
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
        this.history.recordAction(
                project.project().id().toString(),
                level.dimension().identifier().toString(),
                WorldMutationContext.currentActionId(),
                WorldMutationContext.currentActor(),
                blocks,
                entities,
                now
        );
    }

    private boolean hasAction() {
        String actionId = WorldMutationContext.currentActionId();
        return actionId != null && !actionId.isBlank();
    }
}
