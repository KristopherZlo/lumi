package io.github.luma.domain.service;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewCaptureRequest;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PreviewCaptureRequestRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public final class PreviewCaptureRequestService {

    public static final int MAX_CAPTURE_ATTEMPTS = 3;
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 160;

    private final PreviewCaptureRequestRepository repository = new PreviewCaptureRequestRepository();

    public void queue(ProjectLayout layout, String versionId, String dimensionId, Bounds3i bounds) throws IOException {
        if (bounds == null || versionId == null || versionId.isBlank()) {
            return;
        }

        this.repository.save(layout, new PreviewCaptureRequest(
                versionId,
                dimensionId == null ? "minecraft:overworld" : dimensionId,
                bounds,
                Instant.now()
        ));
    }

    public void clear(ProjectLayout layout, String versionId) throws IOException {
        this.repository.delete(layout, versionId);
    }

    public boolean shouldAttempt(PreviewCaptureRequest request, Instant now) {
        return request != null && request.dueAt(now);
    }

    public boolean recordFailure(ProjectLayout layout, PreviewCaptureRequest request, String failure) throws IOException {
        if (layout == null || request == null || request.versionId() == null || request.versionId().isBlank()) {
            return false;
        }

        PreviewCaptureRequest failed = request.withFailure(
                Instant.now(),
                this.retryDelay(request.attempts()),
                this.summarizeFailure(failure)
        );
        if (failed.attempts() >= MAX_CAPTURE_ATTEMPTS) {
            this.repository.delete(layout, request.versionId());
            return false;
        }

        this.repository.save(layout, failed);
        return true;
    }

    private Duration retryDelay(int previousAttempts) {
        long multiplier = 1L << Math.min(4, Math.max(0, previousAttempts));
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private String summarizeFailure(String failure) {
        if (failure == null || failure.isBlank()) {
            return "";
        }
        String singleLine = failure.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }
}
