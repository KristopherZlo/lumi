package io.github.luma.domain.service;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewCaptureRequest;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PreviewCaptureRequestRepository;
import java.io.IOException;
import java.time.Instant;

public final class PreviewCaptureRequestService {

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
}
