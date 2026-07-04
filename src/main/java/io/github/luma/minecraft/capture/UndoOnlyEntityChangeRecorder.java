package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class UndoOnlyEntityChangeRecorder {

    private final EntityMutationCapturePolicy capturePolicy;
    private final CaptureEligibilityService eligibility;
    private final TrackedProjectCatalog trackedProjects;
    private final LiveUndoRedoActionRecorder liveUndoRedoActionRecorder;

    UndoOnlyEntityChangeRecorder(
            EntityMutationCapturePolicy capturePolicy,
            CaptureEligibilityService eligibility,
            TrackedProjectCatalog trackedProjects,
            LiveUndoRedoActionRecorder liveUndoRedoActionRecorder
    ) {
        this.capturePolicy = capturePolicy;
        this.eligibility = eligibility;
        this.trackedProjects = trackedProjects;
        this.liveUndoRedoActionRecorder = liveUndoRedoActionRecorder;
    }

    void record(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        WorldMutationSource source = WorldMutationContext.currentSource();
        if (!this.canUseMutationSource(level, source)) {
            return;
        }

        try {
            Optional<StoredEntityChange> capturedMutation =
                    this.capturePolicy.captureUndoOnly(source, oldPayload, newPayload);
            if (capturedMutation.isEmpty()) {
                return;
            }
            StoredEntityChange capturedChange = capturedMutation.get();
            BlockPos pos = this.entityMutationPos(oldPayload, newPayload);
            Instant now = Instant.now();
            for (TrackedProject trackedProject : this.trackedProjects.matching(level, pos)) {
                if (!this.canUseProjectInCurrentMode(trackedProject)) {
                    continue;
                }
                this.liveUndoRedoActionRecorder.recordEntityAction(
                        trackedProject,
                        level,
                        capturedChange,
                        now,
                        actionStartedAt
                );
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Tracked undo-only entity {} mutation {} for project {} at {}",
                        source,
                        capturedChange.entityId(),
                        trackedProject.project().name(),
                        pos
                );
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture undo-only entity change in {}", level.dimension().identifier(), exception);
        }
    }

    void recordBatch(
            ServerLevel level,
            List<EntityPayload> oldPayloads,
            Instant actionStartedAt
    ) {
        WorldMutationSource source = WorldMutationContext.currentSource();
        if (level == null
                || oldPayloads == null
                || oldPayloads.isEmpty()
                || !this.canUseMutationSource(level, source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            Map<String, TrackedProject> liveUndoProjects = new LinkedHashMap<>();
            Map<String, List<StoredEntityChange>> liveUndoChanges = new LinkedHashMap<>();
            for (EntityPayload oldPayload : oldPayloads) {
                Optional<StoredEntityChange> capturedMutation =
                        this.capturePolicy.captureUndoOnly(source, oldPayload, null);
                if (capturedMutation.isEmpty()) {
                    continue;
                }
                StoredEntityChange capturedChange = capturedMutation.get();
                BlockPos pos = this.entityMutationPos(oldPayload, null);
                for (TrackedProject trackedProject : this.trackedProjects.matching(level, pos)) {
                    if (!this.canUseProjectInCurrentMode(trackedProject)) {
                        continue;
                    }
                    String projectId = trackedProject.project().id().toString();
                    liveUndoProjects.putIfAbsent(projectId, trackedProject);
                    liveUndoChanges.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(capturedChange);
                }
            }

            for (Map.Entry<String, List<StoredEntityChange>> entry : liveUndoChanges.entrySet()) {
                TrackedProject trackedProject = liveUndoProjects.get(entry.getKey());
                if (trackedProject != null) {
                    this.liveUndoRedoActionRecorder.recordEntityAction(
                            trackedProject,
                            level,
                            entry.getValue(),
                            now,
                            actionStartedAt
                    );
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture undo-only entity batch in {}", level.dimension().identifier(), exception);
        }
    }

    private boolean canUseMutationSource(ServerLevel level, WorldMutationSource source) {
        return level != null && this.eligibility.canUseMutationSource(
                level.getServer() != null && level.getServer().isDedicatedServer(),
                WorldMutationContext.currentAccessAllowed(),
                source
        );
    }

    private boolean canUseProjectInCurrentMode(TrackedProject trackedProject) {
        return trackedProject != null && LumaAccessControl.getInstance().canUse(
                trackedProject.project().settings(),
                WorldMutationContext.currentSurvivalMode(),
                WorldMutationContext.currentAccessAllowed()
        );
    }

    private BlockPos entityMutationPos(EntityPayload oldPayload, EntityPayload newPayload) {
        if (newPayload != null) {
            return newPayload.blockPos();
        }
        return oldPayload == null ? BlockPos.ZERO : oldPayload.blockPos();
    }
}
