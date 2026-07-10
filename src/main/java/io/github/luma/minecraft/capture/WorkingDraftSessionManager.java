package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the durable working draft independently from volatile live undo/redo.
 */
final class WorkingDraftSessionManager {

    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final CapturePersistenceCoordinator persistenceCoordinator;
    private final CaptureSessionRegistry sessionRegistry = new CaptureSessionRegistry();
    private final CaptureDiagnosticsRegistry diagnosticsRegistry = new CaptureDiagnosticsRegistry();

    WorkingDraftSessionManager() {
        this(new CapturePersistenceCoordinator());
    }

    WorkingDraftSessionManager(CapturePersistenceCoordinator persistenceCoordinator) {
        this.persistenceCoordinator = persistenceCoordinator;
    }

    TrackedChangeBuffer buffer(String projectId) {
        return this.sessionRegistry.buffer(projectId);
    }

    CaptureSessionState session(String projectId) {
        return this.sessionRegistry.session(projectId);
    }

    CaptureSessionState ensureSession(String projectId, TrackedChangeBuffer buffer) {
        return this.sessionRegistry.ensureSession(projectId, buffer);
    }

    boolean hasBuffer(String projectId) {
        return this.sessionRegistry.hasBuffer(projectId);
    }

    boolean isDirty(String projectId) {
        return this.sessionRegistry.isDirty(projectId);
    }

    void markDirty(String projectId) {
        this.sessionRegistry.markDirty(projectId);
    }

    Instant lastDraftFlush(String projectId) {
        return this.sessionRegistry.lastDraftFlush(projectId);
    }

    boolean activeDraftUpdatedAfter(String projectId, Instant threshold) {
        TrackedChangeBuffer buffer = this.sessionRegistry.buffer(projectId);
        return buffer != null
                && buffer.updatedAt() != null
                && threshold != null
                && buffer.updatedAt().isAfter(threshold);
    }

    boolean hasDraftFingerprint(String projectId, int fingerprint) {
        return this.sessionRegistry.hasDraftFingerprint(projectId, fingerprint);
    }

    void recordUnchangedFlush(String projectId, Instant flushedAt) {
        this.sessionRegistry.recordUnchangedFlush(projectId, flushedAt);
    }

    List<Map.Entry<String, TrackedChangeBuffer>> activeBufferEntries() {
        return this.sessionRegistry.activeBufferEntries();
    }

    List<String> activeProjectIds() {
        return this.sessionRegistry.activeProjectIds();
    }

    void markPersistedDraftCurrentRun(String projectId, TrackedProject trackedProject) throws IOException {
        this.markCurrentRunDraft(projectId, trackedProject, true);
    }

    private void markCurrentRunDraft(String projectId, TrackedProject trackedProject, boolean durable) throws IOException {
        this.sessionRegistry.markCurrentRunDraft(projectId);
        if (durable && trackedProject != null) {
            this.recoveryRepository.markExpectedDraft(trackedProject.layout());
        }
    }

    private void clearCurrentRunDraft(String projectId, TrackedProject trackedProject) throws IOException {
        this.sessionRegistry.clearCurrentRunDraft(projectId);
        if (trackedProject != null) {
            this.recoveryRepository.clearExpectedDraft(trackedProject.layout());
        }
    }

    CaptureSessionDiagnostics diagnosticsForSession(String projectId) {
        return this.diagnosticsRegistry.forSession(projectId);
    }

    void clearSessionDiagnostics(String projectId) {
        this.diagnosticsRegistry.clear(projectId);
    }

    TrackedChangeBuffer getOrCreate(
            TrackedProject trackedProject,
            WorldMutationSource source,
            Instant now
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer existing = this.sessionRegistry.buffer(projectId);
        CaptureSessionDiagnostics diagnostics = this.diagnosticsForSession(projectId);
        if (existing != null) {
            this.sessionRegistry.ensureSession(projectId, existing);
            diagnostics.seedFromBuffer(existing);
            return existing;
        }

        ProjectVariant activeVariant = trackedProject.variants().stream()
                .filter(variant -> variant.id().equals(trackedProject.project().activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + trackedProject.project().name()));

        Optional<RecoveryDraft> draft = this.recoveryRepository.loadDraft(trackedProject.layout());
        boolean resumedDraft = draft
                .filter(candidate -> projectId.equals(candidate.projectId()))
                .filter(candidate -> activeVariant.id().equals(candidate.variantId()))
                .isPresent();
        TrackedChangeBuffer buffer = draft
                .filter(candidate -> projectId.equals(candidate.projectId()))
                .filter(candidate -> activeVariant.id().equals(candidate.variantId()))
                .map(candidate -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), candidate))
                .orElseGet(() -> TrackedChangeBuffer.create(
                        UUID.randomUUID().toString(),
                        projectId,
                        activeVariant.id(),
                        activeVariant.headVersionId(),
                        defaultActor(source),
                        source,
                        now
                ));

        this.sessionRegistry.open(
                projectId,
                buffer,
                resumedDraft ? CaptureSessionState.resume(buffer) : CaptureSessionState.create(buffer)
        );
        if (resumedDraft) {
            this.clearCurrentRunDraft(projectId, trackedProject);
        }
        diagnostics.seedFromBuffer(buffer);
        LumaMod.LOGGER.info(
                "Opened active working draft for project {} on variant {} from base {} using {} source",
                trackedProject.project().name(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                source
        );
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Opened working draft {} for project {} on variant {} from base {} using {}",
                buffer.id(),
                trackedProject.project().name(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                resumedDraft ? "persisted draft" : "new session"
        );
        return buffer;
    }

    void discardIfEmpty(TrackedProject trackedProject, String reason) throws IOException {
        if (trackedProject == null) {
            return;
        }
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer buffer = this.sessionRegistry.buffer(projectId);
        if (buffer == null || !buffer.isEmpty()) {
            return;
        }
        this.clearCurrentRunDraft(projectId, trackedProject);
        this.sessionRegistry.close(projectId);
        this.clearSessionDiagnostics(projectId);
        this.persistenceCoordinator.deleteDraft(
                trackedProject.layout(),
                projectId,
                trackedProject.project().name()
        );
        LumaMod.LOGGER.info("Discarded empty working draft for project {} {}", trackedProject.project().name(), reason);
    }

    void persistIdleDraft(
            TrackedProject trackedProject,
            TrackedChangeBuffer session,
            Instant now,
            Duration flushInterval
    ) {
        String projectId = trackedProject.project().id().toString();
        Instant lastFlush = this.sessionRegistry.lastDraftFlush(projectId);
        if (lastFlush != null && Duration.between(lastFlush, now).compareTo(flushInterval) < 0) {
            return;
        }
        int draftFingerprint = session.contentFingerprint();
        if (this.sessionRegistry.hasDraftFingerprint(projectId, draftFingerprint)) {
            this.sessionRegistry.recordUnchangedFlush(projectId, now);
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Skipped unchanged working draft flush for project {} after stabilization",
                    trackedProject.project().name()
            );
            return;
        }
        this.persistenceCoordinator.enqueueDraftFlush(
                trackedProject.layout(),
                projectId,
                trackedProject.project().name(),
                session.toDraft()
        );
        this.sessionRegistry.recordDraftFlush(projectId, now, draftFingerprint);
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Queued async working draft flush for project {} with {} pending changes after {}s idle",
                trackedProject.project().name(),
                session.size(),
                Duration.between(session.updatedAt(), now).getSeconds()
        );
    }

    Optional<RecoveryDraft> snapshotDraft(TrackedProject trackedProject) throws IOException {
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer buffer = this.sessionRegistry.buffer(projectId);
        if (buffer != null) {
            return buffer.isEmpty() ? Optional.empty() : Optional.of(buffer.toDraft());
        }
        return this.recoveryRepository.loadDraft(trackedProject.layout());
    }

    boolean hasInterruptedDraft(String projectId, TrackedProject trackedProject) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        TrackedChangeBuffer buffer = this.sessionRegistry.buffer(projectId);
        if (buffer != null && !buffer.isEmpty()) {
            return false;
        }
        if (this.sessionRegistry.hasCurrentRunDraft(projectId)) {
            return false;
        }
        if (trackedProject == null) {
            return false;
        }
        if (this.recoveryRepository.hasExpectedDraft(trackedProject.layout())) {
            return false;
        }
        return this.recoveryRepository.loadDraft(trackedProject.layout())
                .filter(draft -> !draft.isEmpty())
                .isPresent();
    }

    boolean hasPendingDraftFlush(String projectId) {
        return this.persistenceCoordinator.hasPendingDraftFlush(projectId);
    }

    Optional<TrackedChangeBuffer> freezeAfterReconciliation(String projectId, TrackedProject trackedProject) throws IOException {
        return this.freezeAfterReconciliation(projectId, trackedProject, PersistenceDrainMode.ALL, false);
    }

    Optional<TrackedChangeBuffer> freezeIdleAfterReconciliation(String projectId, TrackedProject trackedProject) throws IOException {
        return this.freezeAfterReconciliation(projectId, trackedProject, PersistenceDrainMode.DRAFT_FLUSHES_ONLY, true);
    }

    Optional<TrackedChangeBuffer> freezeForRecoveryAfterReconciliation(
            String projectId,
            TrackedProject trackedProject
    ) throws IOException {
        return this.freezeAfterReconciliation(projectId, trackedProject, PersistenceDrainMode.DRAFT_FLUSHES_ONLY, false);
    }

    Optional<TrackedChangeBuffer> freezeForShutdownAfterReconciliation(
            String projectId,
            TrackedProject trackedProject
    ) throws IOException {
        return this.freezeAfterReconciliation(projectId, trackedProject, PersistenceDrainMode.DRAFT_FLUSHES_ONLY, true);
    }

    private Optional<TrackedChangeBuffer> freezeAfterReconciliation(
            String projectId,
            TrackedProject trackedProject,
            PersistenceDrainMode drainMode,
            boolean durableCurrentRunMarker
    ) throws IOException {
        LumiTestFailpoints.hit(LumiTestFailpoints.BEFORE_DRAFT_FREEZE);
        if (trackedProject != null) {
            if (drainMode == PersistenceDrainMode.ALL) {
                this.persistenceCoordinator.drainProject(projectId, trackedProject.project().name());
            } else {
                this.persistenceCoordinator.drainDraftFlushes(projectId, trackedProject.project().name());
            }
        }
        TrackedChangeBuffer session = this.sessionRegistry.removeBuffer(projectId);
        boolean persistedDraftIsCurrent = this.sessionRegistry.matchesPersistedDraft(projectId, session);
        this.sessionRegistry.close(projectId);
        this.clearSessionDiagnostics(projectId);
        if (session == null) {
            if (trackedProject == null) {
                LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_DRAFT_FREEZE);
                return Optional.empty();
            }
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Freezing project {} without active working draft; loading persisted draft fallback",
                    trackedProject.project().name()
            );
            Optional<TrackedChangeBuffer> persistedDraft = this.recoveryRepository.loadDraft(trackedProject.layout())
                    .map(draft -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), draft));
            if (persistedDraft.isPresent() && !durableCurrentRunMarker) {
                this.clearCurrentRunDraft(projectId, trackedProject);
            }
            LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_DRAFT_FREEZE);
            return persistedDraft;
        }

        if (trackedProject != null && !session.isEmpty()) {
            if (persistedDraftIsCurrent) {
                this.markCurrentRunDraft(projectId, trackedProject, durableCurrentRunMarker);
                LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_DRAFT_FREEZE);
                LumaMod.LOGGER.info(
                        "Skipped shutdown draft rewrite for project {} because the active working draft is already persisted",
                        trackedProject.project().name()
                );
                return Optional.of(session);
            }
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Freezing active working draft {} for project {} with {} pending changes",
                    session.id(),
                    trackedProject.project().name(),
                    session.size()
            );
            this.recoveryRepository.saveDraft(trackedProject.layout(), session.toDraft());
            LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_DRAFT_FREEZE);
            this.markCurrentRunDraft(projectId, trackedProject, durableCurrentRunMarker);
            LumaMod.LOGGER.info(
                    "Persisted active working draft for project {} with {} pending changes",
                    trackedProject.project().name(),
                    session.size()
            );
        }
        Optional<TrackedChangeBuffer> frozenSession = session.isEmpty() ? Optional.empty() : Optional.of(session);
        LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_DRAFT_FREEZE);
        return frozenSession;
    }

    Optional<TrackedChangeBuffer> consumeAfterReconciliation(String projectId, TrackedProject trackedProject) throws IOException {
        if (trackedProject != null) {
            this.persistenceCoordinator.drainDraftFlushes(projectId, trackedProject.project().name());
        }
        TrackedChangeBuffer session = this.sessionRegistry.removeBuffer(projectId);
        this.sessionRegistry.close(projectId);
        this.clearSessionDiagnostics(projectId);
        if (session != null) {
            this.clearCurrentRunDraft(projectId, trackedProject);
            LumaMod.LOGGER.info("Consumed in-memory working draft for project {} with {} pending changes", projectId, session.size());
            if (trackedProject != null) {
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Consumed in-memory working draft {} for project {} with {} pending changes",
                        session.id(),
                        trackedProject.project().name(),
                        session.size()
                );
            }
            return session.isEmpty() ? Optional.empty() : Optional.of(session);
        }

        if (trackedProject == null) {
            return Optional.empty();
        }
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "No live working draft for project {}. Loading persisted draft for save/amend.",
                trackedProject.project().name()
        );
        Optional<TrackedChangeBuffer> persistedDraft = this.recoveryRepository.loadDraft(trackedProject.layout())
                .map(draft -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), draft))
                .filter(buffer -> !buffer.isEmpty());
        if (persistedDraft.isPresent()) {
            this.clearCurrentRunDraft(projectId, trackedProject);
        }
        return persistedDraft;
    }

    void discard(String projectId, TrackedProject trackedProject) throws IOException {
        this.sessionRegistry.close(projectId);
        this.clearSessionDiagnostics(projectId);
        if (trackedProject != null) {
            this.clearCurrentRunDraft(projectId, trackedProject);
            this.persistenceCoordinator.deleteDraft(
                    trackedProject.layout(),
                    projectId,
                    trackedProject.project().name()
            );
            LumaMod.LOGGER.info("Discarded persisted working draft for project {}", trackedProject.project().name());
        }
    }

    void rebaseBaseVersion(
            TrackedProject trackedProject,
            String expectedBaseVersionId,
            String newBaseVersionId,
            Instant now
    ) throws IOException {
        if (trackedProject == null || newBaseVersionId == null || newBaseVersionId.isBlank()) {
            return;
        }
        String projectId = trackedProject.project().id().toString();
        boolean changed = false;
        TrackedChangeBuffer buffer = this.sessionRegistry.buffer(projectId);
        if (buffer != null) {
            changed = buffer.rebaseBaseVersion(expectedBaseVersionId, newBaseVersionId, now);
            if (changed && !buffer.isEmpty()) {
                this.sessionRegistry.markDirty(projectId);
                this.persistenceCoordinator.drainDraftFlushes(projectId, trackedProject.project().name());
                this.recoveryRepository.saveDraft(trackedProject.layout(), buffer.toDraft());
            }
        }

        if (!changed) {
            Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(trackedProject.layout())
                    .filter(draft -> projectId.equals(draft.projectId()))
                    .filter(draft -> sameBase(draft.baseVersionId(), expectedBaseVersionId));
            if (persistedDraft.isPresent()) {
                this.recoveryRepository.saveDraft(trackedProject.layout(), rebaseDraft(persistedDraft.get(), newBaseVersionId, now));
                changed = true;
            }
        }

        if (changed) {
            LumaMod.LOGGER.info(
                    "Rebased working draft for project {} from {} to {}",
                    trackedProject.project().name(),
                    expectedBaseVersionId,
                    newBaseVersionId
            );
        }
    }

    private static RecoveryDraft rebaseDraft(RecoveryDraft draft, String newBaseVersionId, Instant now) {
        return new RecoveryDraft(
                draft.projectId(),
                draft.variantId(),
                newBaseVersionId,
                draft.actor(),
                draft.mutationSource(),
                draft.startedAt(),
                now == null ? draft.updatedAt() : now,
                draft.changes(),
                draft.entityChanges()
        );
    }

    private static boolean sameBase(String left, String right) {
        String resolvedLeft = left == null ? "" : left;
        String resolvedRight = right == null ? "" : right;
        return resolvedLeft.equals(resolvedRight);
    }

    private static String defaultActor(WorldMutationSource source) {
        return new MutationSourcePolicy().defaultActor(source);
    }

    private enum PersistenceDrainMode {
        ALL,
        DRAFT_FLUSHES_ONLY
    }
}
