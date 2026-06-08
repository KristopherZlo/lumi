package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import java.io.IOException;
import java.util.List;

/**
 * Plans the exact initial/WORLD_ROOT state source appended to direct restores.
 */
final class ExactRootStateRestorePlanner {

    private final RestoreChunkCollector chunkCollector;
    private final RestoreBaselineRequirementValidator baselineRequirementValidator;

    ExactRootStateRestorePlanner(RestoreChunkCollector chunkCollector) {
        this(new BaselineChunkRepository(), chunkCollector);
    }

    ExactRootStateRestorePlanner(
            BaselineChunkRepository baselineChunkRepository,
            RestoreChunkCollector chunkCollector
    ) {
        this.chunkCollector = chunkCollector;
        this.baselineRequirementValidator = new RestoreBaselineRequirementValidator(baselineChunkRepository);
    }

    ExactRootStateRestorePlan plan(
            ProjectLayout layout,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) throws IOException {
        if (!this.shouldAppend(targetVersion, pendingDraft, directPlan)) {
            return ExactRootStateRestorePlan.none();
        }
        List<ChunkPoint> affectedChunks = this.exactRootStateChunks(layout, pendingDraft, directPlan);
        if (affectedChunks.isEmpty()) {
            return ExactRootStateRestorePlan.none();
        }
        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT) {
            List<ChunkPoint> baselineChunks = this.baselineRequirementValidator.requirePresent(
                    layout,
                    affectedChunks,
                    "exact world-root restore plan"
            );
            return ExactRootStateRestorePlan.worldRoot(baselineChunks);
        }
        return ExactRootStateRestorePlan.initialSnapshot(affectedChunks);
    }

    boolean shouldAppend(
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) {
        if (targetVersion == null) {
            return false;
        }
        boolean hasPendingDraft = pendingDraft != null && !pendingDraft.isEmpty();
        boolean hasPatchReplay = directPlan != null && directPlan.stepCount() > 0;
        if (!hasPendingDraft && !hasPatchReplay) {
            return false;
        }
        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT) {
            return true;
        }
        return targetVersion.versionKind() == VersionKind.INITIAL
                && targetVersion.snapshotId() != null
                && !targetVersion.snapshotId().isBlank();
    }

    private List<ChunkPoint> exactRootStateChunks(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) throws IOException {
        List<ProjectVersion> replayVersions = directPlan == null
                ? List.of()
                : directPlan.allVersions();
        return this.chunkCollector.mergeChunks(
                this.chunkCollector.touchedChunksForVersions(layout, replayVersions),
                this.chunkCollector.touchedChunksForDraft(pendingDraft)
        );
    }
}

record ExactRootStateRestorePlan(boolean append, List<ChunkPoint> chunks) {

    static ExactRootStateRestorePlan none() {
        return new ExactRootStateRestorePlan(false, List.of());
    }

    static ExactRootStateRestorePlan initialSnapshot(List<ChunkPoint> chunks) {
        return new ExactRootStateRestorePlan(true, chunks);
    }

    static ExactRootStateRestorePlan worldRoot(List<ChunkPoint> chunks) {
        return new ExactRootStateRestorePlan(true, chunks);
    }

    int sourceCount() {
        return this.append && !this.chunks.isEmpty() ? 1 : 0;
    }

    ExactRootStateRestorePlan {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
