package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.TrackedChangeBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CaptureSessionRegistry {

    private final Map<String, TrackedChangeBuffer> activeBuffers = new HashMap<>();
    private final Map<String, CaptureSessionState> activeSessions = new HashMap<>();
    private final Map<String, Instant> lastDraftFlushes = new HashMap<>();
    private final Map<String, Integer> lastDraftFingerprints = new HashMap<>();
    private final Set<String> dirtySessions = new HashSet<>();

    TrackedChangeBuffer buffer(String projectId) {
        return this.activeBuffers.get(projectId);
    }

    CaptureSessionState session(String projectId) {
        return this.activeSessions.get(projectId);
    }

    CaptureSessionState ensureSession(String projectId, TrackedChangeBuffer buffer) {
        return this.activeSessions.computeIfAbsent(projectId, ignored -> CaptureSessionState.create(buffer));
    }

    void open(String projectId, TrackedChangeBuffer buffer, CaptureSessionState session) {
        this.activeBuffers.put(projectId, buffer);
        this.activeSessions.put(projectId, session);
    }

    TrackedChangeBuffer removeBuffer(String projectId) {
        return this.activeBuffers.remove(projectId);
    }

    boolean hasBuffer(String projectId) {
        return this.activeBuffers.containsKey(projectId);
    }

    void close(String projectId) {
        this.activeBuffers.remove(projectId);
        this.activeSessions.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        this.lastDraftFingerprints.remove(projectId);
    }

    void markDirty(String projectId) {
        this.dirtySessions.add(projectId);
    }

    void clearDirty(String projectId) {
        this.dirtySessions.remove(projectId);
    }

    boolean isDirty(String projectId) {
        return this.dirtySessions.contains(projectId);
    }

    Instant lastDraftFlush(String projectId) {
        return this.lastDraftFlushes.get(projectId);
    }

    void recordUnchangedFlush(String projectId, Instant flushedAt) {
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.put(projectId, flushedAt);
    }

    void recordDraftFlush(String projectId, Instant flushedAt, int fingerprint) {
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.put(projectId, flushedAt);
        this.lastDraftFingerprints.put(projectId, fingerprint);
    }

    boolean matchesPersistedDraft(String projectId, TrackedChangeBuffer buffer) {
        return buffer != null
                && !buffer.isEmpty()
                && !this.dirtySessions.contains(projectId)
                && this.lastDraftFingerprints.get(projectId) instanceof Integer persistedFingerprint
                && persistedFingerprint == buffer.contentFingerprint();
    }

    boolean hasDraftFingerprint(String projectId, int fingerprint) {
        return this.lastDraftFingerprints.get(projectId) instanceof Integer lastFingerprint
                && lastFingerprint == fingerprint;
    }

    List<Map.Entry<String, TrackedChangeBuffer>> activeBufferEntries() {
        return this.activeBuffers.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    List<String> activeProjectIds() {
        return List.copyOf(this.activeBuffers.keySet());
    }
}
