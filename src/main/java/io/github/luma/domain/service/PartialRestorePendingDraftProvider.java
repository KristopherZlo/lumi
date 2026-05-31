package io.github.luma.domain.service;

import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves pending draft state for partial restore planning.
 */
final class PartialRestorePendingDraftProvider {

    private final RecoveryRepository recoveryRepository;
    private final HistoryCaptureManager captureManager;

    PartialRestorePendingDraftProvider() {
        this(new RecoveryRepository(), HistoryCaptureManager.getInstance());
    }

    PartialRestorePendingDraftProvider(
            RecoveryRepository recoveryRepository,
            HistoryCaptureManager captureManager
    ) {
        this.recoveryRepository = recoveryRepository;
        this.captureManager = captureManager;
    }

    Optional<RecoveryDraft> snapshot(ServerLevel level, ProjectLayout layout, String projectId) throws IOException {
        Optional<RecoveryDraft> liveDraft = Optional.empty();
        if (level != null && level.getServer() != null && projectId != null && !projectId.isBlank()) {
            liveDraft = this.captureManager.snapshotDraft(level.getServer(), projectId);
        }
        return liveDraft.isPresent() ? liveDraft : this.recoveryRepository.loadDraft(layout);
    }

    Optional<RecoveryDraft> freeze(ServerLevel level, ProjectLayout layout, String projectId) throws IOException {
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> frozenSession = this.captureManager.freezeWorkingDraft(
                level.getServer(),
                projectId
        );
        Optional<RecoveryDraft> frozenDraft = frozenSession.map(TrackedChangeBuffer::toDraft);
        return frozenDraft.isPresent() ? frozenDraft : persistedDraft;
    }
}
