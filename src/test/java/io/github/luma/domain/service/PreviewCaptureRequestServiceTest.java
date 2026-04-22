package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PreviewCaptureRequestRepository;
import io.github.luma.storage.repository.ProjectRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCaptureRequestServiceTest {

    @TempDir
    Path tempDir;

    private final PreviewCaptureRequestService service = new PreviewCaptureRequestService();
    private final PreviewCaptureRequestRepository repository = new PreviewCaptureRequestRepository();
    private final ProjectRepository projectRepository = new ProjectRepository();

    @Test
    void queuesAndClearsPreviewRequests() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("tower.mbp"));
        this.projectRepository.initializeLayout(layout);

        this.service.queue(
                layout,
                "v0001",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15))
        );

        var request = this.repository.load(layout, "v0001").orElseThrow();
        assertEquals("v0001", request.versionId());
        assertEquals("minecraft:overworld", request.dimensionId());
        assertTrue(request.requestedAt() != null);

        this.service.clear(layout, "v0001");

        assertFalse(this.repository.load(layout, "v0001").isPresent());
    }

    @Test
    void ignoresRequestsWithoutBoundsOrVersionId() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("tower.mbp"));
        this.projectRepository.initializeLayout(layout);

        this.service.queue(layout, "", "minecraft:overworld", null);

        assertTrue(this.repository.loadAll(layout).isEmpty());
    }
}
